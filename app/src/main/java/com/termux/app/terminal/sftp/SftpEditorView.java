package com.termux.app.terminal.sftp;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;

import java.nio.charset.CharacterCodingException;
import java.util.List;
import java.util.Locale;

/**
 * The editor of a remote file: a toolbar, and the text with line numbers and syntax highlighting
 * or a hex dump of the bytes. It keeps the format of the file, its encoding, byte order mark and
 * line endings, and the undo history. Reading and writing the file is left to the activity.
 */
public class SftpEditorView extends LinearLayout {

    /** Listens for changes of the state shown in the title and menu, like unsaved changes. */
    public interface Listener {
        void onEditorStateChanged();
    }

    private static final String PREFERENCES = "sftp_editor";
    private static final String KEY_WORD_WRAP = "word_wrap";

    private final LineNumberEditText mText;
    private final ListView mHexList;
    private final HexAdapter mHexAdapter = new HexAdapter();
    private final TextView mHexNote;
    private final EditHistory mHistory;
    private final Typeface mTypeface;
    private Listener mListener;

    private final Button mUndoButton;
    private final Button mRedoButton;
    private final Button mFindButton;
    private final Button mGoToButton;
    private final Button mWrapButton;
    private final Button mEncodingButton;
    private final Button mLineEndingButton;
    private final Button mHexButton;

    /** The bytes of the file as read or last saved, possibly only its start. */
    private byte[] mBytes = new byte[0];
    private int mByteCount;
    /** Whether the file is larger than {@link #mBytes}. */
    private boolean mFileTruncated;
    private String mName = "";
    /** Whether the text was decoded from {@link #mBytes}, which is done when it is first shown. */
    private boolean mTextLoaded;
    private SftpText.Encoding mEncoding = SftpText.UTF_8;
    private boolean mBom;
    private boolean mCrlf;
    private boolean mReadOnly;
    /** Whether the encoding or line endings were changed since the file was read or saved. */
    private boolean mFormatChanged;
    private boolean mHex;
    private byte[] mHexBytes = new byte[0];
    private int mHexByteCount;
    @Nullable private String mFindQuery;

    public SftpEditorView(Context context, Typeface typeface) {
        super(context);
        setOrientation(VERTICAL);
        mTypeface = typeface;

        HorizontalScrollView toolbarScroll = new HorizontalScrollView(context);
        toolbarScroll.setHorizontalScrollBarEnabled(false);
        toolbarScroll.setBackgroundColor(0xFF202020);
        LinearLayout toolbar = new LinearLayout(context);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbarScroll.addView(toolbar);
        addView(toolbarScroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mUndoButton = addToolbarButton(toolbar, "↶", R.string.sftp_action_undo, v -> undo());
        mRedoButton = addToolbarButton(toolbar, "↷", R.string.sftp_action_redo, v -> redo());
        mFindButton = addToolbarButton(toolbar, getContext().getString(R.string.sftp_action_find), R.string.sftp_action_find, v -> showFindDialog());
        mGoToButton = addToolbarButton(toolbar, getContext().getString(R.string.sftp_toolbar_go_to_line), R.string.sftp_action_go_to_line, v -> showGoToLineDialog());
        mWrapButton = addToolbarButton(toolbar, getContext().getString(R.string.sftp_toolbar_word_wrap), R.string.sftp_action_word_wrap, v -> toggleWordWrap());
        mEncodingButton = addToolbarButton(toolbar, "UTF-8", R.string.sftp_action_encoding, v -> showEncodingDialog());
        mLineEndingButton = addToolbarButton(toolbar, "LF", R.string.sftp_action_line_ending, v -> showLineEndingDialog());
        mHexButton = addToolbarButton(toolbar, "HEX", R.string.sftp_action_hex, v -> setHex(!mHex));

        FrameLayout content = new FrameLayout(context);
        addView(content, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        mText = new LineNumberEditText(context);
        mText.setTypeface(typeface);
        content.addView(mText);
        LinearLayout hexLayout = new LinearLayout(context);
        hexLayout.setOrientation(VERTICAL);
        hexLayout.setBackgroundColor(Color.BLACK);
        mHexList = new ListView(context);
        mHexList.setAdapter(mHexAdapter);
        mHexList.setFastScrollEnabled(true);
        mHexList.setDivider(null);
        hexLayout.addView(mHexList, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        mHexNote = new TextView(context);
        mHexNote.setPadding(dp(8), dp(4), dp(8), dp(4));
        mHexNote.setTextColor(Color.GRAY);
        hexLayout.addView(mHexNote);
        content.addView(hexLayout);
        hexLayout.setVisibility(GONE);
        mHexList.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) mHexAdapter.updateBytesPerLine(r - l);
        });

        mHistory = new EditHistory(mText);
        mHistory.setListener(this::onStateChanged);
        setWordWrap(getPreferences().getBoolean(KEY_WORD_WRAP, false));
        updateToolbar();
    }

    private SharedPreferences getPreferences() {
        return getContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    private Button addToolbarButton(LinearLayout toolbar, String text, int descriptionId, View.OnClickListener listener) {
        Button button = new Button(getContext(), null, android.R.attr.borderlessButtonStyle);
        button.setText(text);
        button.setAllCaps(false);
        button.setMinWidth(dp(44));
        button.setMinimumWidth(dp(44));
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, text.length() == 1 ? 20 : 14);
        button.setContentDescription(getContext().getString(descriptionId));
        button.setOnClickListener(listener);
        button.setOnLongClickListener(v -> {
            Toast.makeText(getContext(), descriptionId, Toast.LENGTH_SHORT).show();
            return true;
        });
        toolbar.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        return button;
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    public LineNumberEditText getTextView() {
        return mText;
    }

    // State

    /**
     * Show a file. {@code count} bytes of it are in {@code bytes}, and {@code fileTruncated} tells
     * whether the file is larger. It is shown in hex if {@code hex}, otherwise as text.
     */
    public void load(@NonNull String name, @NonNull byte[] bytes, int count, boolean fileTruncated, boolean hex) {
        mName = name;
        mBytes = bytes;
        mByteCount = count;
        mFileTruncated = fileTruncated;
        mTextLoaded = false;
        mFindQuery = null;
        mHex = false;
        if (hex) {
            // Detect the encoding for the toolbar, the text is decoded when it is shown.
            SftpText.Text text = SftpText.decode(bytes, Math.min(count, 64 * 1024), true);
            mEncoding = text.encoding;
            mBom = text.bom;
            mCrlf = text.crlf;
            mReadOnly = true;
            mText.setText("");
            mHistory.clear();
            setHex(true);
        } else {
            decodeText(null);
            showText();
        }
        mText.setLanguage(SyntaxHighlighter.detect(name, getFirstLine(bytes, count)));
        onStateChanged();
    }

    private static String getFirstLine(byte[] bytes, int count) {
        int end = 0;
        while (end < count && end < 256 && bytes[end] != '\n') end++;
        return new String(bytes, 0, end, SftpText.ISO_8859_1.charset);
    }

    /** Whether only the start of the file could be read, so the text can't be saved. */
    private boolean isTextTruncated() {
        return mFileTruncated || mByteCount > SftpText.MAX_EDIT_SIZE;
    }

    /** Decode the bytes as text with an encoding, or a detected one if {@code null}, and show it. */
    private void decodeText(@Nullable SftpText.Encoding encoding) {
        int length = Math.min(mByteCount, SftpText.MAX_EDIT_SIZE);
        boolean truncated = isTextTruncated();
        SftpText.Text text = encoding == null ? SftpText.decode(mBytes, length, truncated)
            : SftpText.decode(mBytes, length, truncated, encoding);
        mEncoding = text.encoding;
        mBom = text.bom;
        mCrlf = text.crlf;
        mReadOnly = truncated || !text.lossless;
        mFormatChanged = false;
        mText.setText(text.text);
        mHistory.clear();
        mTextLoaded = true;
        // A read-only editor can still be scrolled and its text selected and copied.
        mText.setShowSoftInputOnFocus(!mReadOnly);
        mText.setKeyListener(mReadOnly ? null : new EditText(getContext()).getKeyListener());
        mText.setTextIsSelectable(true);
        mText.setSelection(0);
        mText.scrollTo(0, 0);
        if (truncated)
            Toast.makeText(getContext(), getContext().getString(R.string.sftp_file_truncated, SftpText.formatSize(SftpText.MAX_EDIT_SIZE)), Toast.LENGTH_LONG).show();
        else if (!text.lossless)
            Toast.makeText(getContext(), getContext().getString(R.string.sftp_not_lossless, mEncoding.label), Toast.LENGTH_LONG).show();
    }

    public boolean isDirty() {
        return mTextLoaded && (!mHistory.isAtSaved() || mFormatChanged);
    }

    public boolean isReadOnly() {
        return mReadOnly;
    }

    /** The bytes to save: the text in the encoding and with the line endings of the file. */
    @NonNull
    public byte[] getContent() throws CharacterCodingException {
        return SftpText.encode(mText.getText().toString(), mCrlf, mEncoding, mBom);
    }

    /** Take the saved bytes as the content of the file. */
    public void markSaved(@NonNull byte[] content) {
        mBytes = content;
        mByteCount = content.length;
        mFormatChanged = false;
        mHistory.markSaved();
        onStateChanged();
    }

    /** Forget the file. */
    public void clear() {
        mBytes = new byte[0];
        mByteCount = 0;
        mHexBytes = new byte[0];
        mHexByteCount = 0;
        mText.setText("");
        mHistory.clear();
        mTextLoaded = false;
    }

    private void onStateChanged() {
        updateToolbar();
        if (mListener != null) mListener.onEditorStateChanged();
    }

    private void updateToolbar() {
        boolean text = !mHex;
        boolean editable = text && !mReadOnly;
        setEnabled(mUndoButton, editable && mHistory.canUndo());
        setEnabled(mRedoButton, editable && mHistory.canRedo());
        setEnabled(mFindButton, text);
        setEnabled(mGoToButton, text);
        setEnabled(mWrapButton, text);
        setChecked(mWrapButton, mText.isWordWrap());
        mEncodingButton.setText(mEncoding.label + (mBom ? " BOM" : ""));
        setEnabled(mEncodingButton, text);
        mLineEndingButton.setText(mCrlf ? "CRLF" : "LF");
        setEnabled(mLineEndingButton, editable);
        setChecked(mHexButton, mHex);
    }

    private static void setEnabled(Button button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.35f);
    }

    private static void setChecked(Button button, boolean checked) {
        button.setTextColor(checked ? 0xFF4FC3F7 : Color.WHITE);
        button.setPaintFlags(checked ? button.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG : button.getPaintFlags() & ~Paint.UNDERLINE_TEXT_FLAG);
    }

    private void undo() {
        mHistory.undo();
    }

    private void redo() {
        mHistory.redo();
    }

    // Word wrap

    private void toggleWordWrap() {
        setWordWrap(!mText.isWordWrap());
    }

    private void setWordWrap(boolean wordWrap) {
        mText.setWordWrap(wordWrap);
        getPreferences().edit().putBoolean(KEY_WORD_WRAP, wordWrap).apply();
        updateToolbar();
    }

    // Hex

    public boolean isHex() {
        return mHex;
    }

    private void setHex(boolean hex) {
        if (hex) {
            if (isDirty()) {
                try {
                    mHexBytes = getContent();
                } catch (CharacterCodingException e) {
                    Toast.makeText(getContext(), getContext().getString(R.string.sftp_cannot_encode, mEncoding.label), Toast.LENGTH_LONG).show();
                    return;
                }
                mHexByteCount = mHexBytes.length;
            } else {
                mHexBytes = mBytes;
                mHexByteCount = mByteCount;
            }
            mHex = true;
            mHexAdapter.notifyDataSetChanged();
            mHexList.setSelection(0);
            mHexNote.setText(mFileTruncated ? getContext().getString(R.string.sftp_hex_truncated, SftpText.formatSize(mHexByteCount))
                : SftpText.formatSize(mHexByteCount) + (isDirty() ? " · " + getContext().getString(R.string.sftp_hex_unsaved) : ""));
            mText.setVisibility(GONE);
            ((View) mHexList.getParent()).setVisibility(VISIBLE);
        } else {
            mHex = false;
            if (!mTextLoaded) decodeText(null);
            showText();
        }
        onStateChanged();
    }

    private void showText() {
        mHex = false;
        ((View) mHexList.getParent()).setVisibility(GONE);
        mText.setVisibility(VISIBLE);
    }

    private class HexAdapter extends BaseAdapter {
        int mBytesPerLine = 16;

        void updateBytesPerLine(int width) {
            Paint paint = new Paint();
            paint.setTypeface(mTypeface);
            paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12, getResources().getDisplayMetrics()));
            float available = width - dp(16);
            int bytesPerLine = paint.measureText("0") * SftpText.getHexLineLength(16) <= available ? 16 : 8;
            if (bytesPerLine != mBytesPerLine) {
                mBytesPerLine = bytesPerLine;
                notifyDataSetChanged();
            }
        }

        @Override public int getCount() { return (mHexByteCount + mBytesPerLine - 1) / mBytesPerLine; }
        @Override public Object getItem(int position) { return position; }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView view = convertView instanceof TextView ? (TextView) convertView : null;
            if (view == null) {
                view = new TextView(getContext());
                view.setTypeface(mTypeface);
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                view.setTextColor(0xFFDDDDDD);
                view.setSingleLine();
                view.setEllipsize(null);
                view.setPadding(dp(8), dp(1), dp(8), dp(1));
            }
            view.setText(SftpText.formatHexLine(mHexBytes, mHexByteCount, position * mBytesPerLine, mBytesPerLine));
            return view;
        }
    }

    // Encoding and line endings

    private void showEncodingDialog() {
        // Read-only text can only be decoded again with another encoding.
        if (mReadOnly) {
            chooseEncoding(true);
            return;
        }
        new AlertDialog.Builder(getContext())
            .setTitle(getContext().getString(R.string.sftp_encoding_title, mEncoding.label))
            .setItems(new CharSequence[]{getContext().getString(R.string.sftp_action_reopen_with_encoding),
                getContext().getString(R.string.sftp_action_save_with_encoding)}, (d, which) -> chooseEncoding(which == 0))
            .show();
    }

    /** Choose an encoding to decode the file with again if {@code reopen}, or to save the text with. */
    private void chooseEncoding(boolean reopen) {
        List<SftpText.Encoding> encodings = SftpText.getEncodings();
        CharSequence[] labels = new CharSequence[encodings.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = encodings.get(i).label;
        new AlertDialog.Builder(getContext())
            .setTitle(reopen ? R.string.sftp_action_reopen_with_encoding : R.string.sftp_action_save_with_encoding)
            .setSingleChoiceItems(labels, encodings.indexOf(mEncoding), (d, which) -> {
                d.dismiss();
                SftpText.Encoding encoding = encodings.get(which);
                if (reopen) reopenWithEncoding(encoding);
                else saveWithEncoding(encoding);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void reopenWithEncoding(SftpText.Encoding encoding) {
        if (!isDirty()) {
            decodeText(encoding);
            onStateChanged();
            return;
        }
        new AlertDialog.Builder(getContext())
            .setMessage(getContext().getString(R.string.sftp_unsaved_changes, mName))
            .setPositiveButton(R.string.sftp_action_discard, (d, which) -> {
                decodeText(encoding);
                onStateChanged();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void saveWithEncoding(SftpText.Encoding encoding) {
        if (encoding == mEncoding) return;
        try {
            SftpText.encode(mText.getText().toString(), mCrlf, encoding, false);
        } catch (CharacterCodingException e) {
            Toast.makeText(getContext(), getContext().getString(R.string.sftp_cannot_encode, encoding.label), Toast.LENGTH_LONG).show();
            return;
        }
        mEncoding = encoding;
        mBom = mBom && SftpText.hasBom(encoding);
        mFormatChanged = true;
        onStateChanged();
    }

    private void showLineEndingDialog() {
        CharSequence[] labels = {getContext().getString(R.string.sftp_line_ending_lf), getContext().getString(R.string.sftp_line_ending_crlf)};
        new AlertDialog.Builder(getContext())
            .setTitle(R.string.sftp_action_line_ending)
            .setSingleChoiceItems(labels, mCrlf ? 1 : 0, (d, which) -> {
                d.dismiss();
                setCrlf(which == 1);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void setCrlf(boolean crlf) {
        String text = mText.getText().toString();
        // Files with mixed line endings keep their CRs in the text, which would be doubled.
        boolean hasCr = text.indexOf('\r') >= 0;
        if (crlf == mCrlf && !hasCr) return;
        if (hasCr) mText.getText().replace(0, text.length(), text.replace("\r\n", "\n"));
        mCrlf = crlf;
        mFormatChanged = true;
        onStateChanged();
    }

    // Find and go to line

    public void showFindDialog() {
        EditText input = new EditText(getContext());
        input.setSingleLine();
        if (mFindQuery != null) {
            input.setText(mFindQuery);
            input.selectAll();
        }
        new AlertDialog.Builder(getContext())
            .setTitle(R.string.sftp_action_find)
            .setView(input)
            .setPositiveButton(R.string.sftp_action_find, (d, which) -> {
                mFindQuery = input.getText().toString();
                findNext();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Select the next match of the find query after the selection, wrapping around. */
    public void findNext() {
        if (mFindQuery == null) {
            showFindDialog();
            return;
        }
        if (TextUtils.isEmpty(mFindQuery)) return;
        String text = mText.getText().toString().toLowerCase(Locale.ROOT);
        String query = mFindQuery.toLowerCase(Locale.ROOT);
        int from = Math.max(0, mText.getSelectionEnd());
        int index = text.indexOf(query, from);
        if (index < 0) index = text.indexOf(query);
        if (index < 0) {
            Toast.makeText(getContext(), getContext().getString(R.string.sftp_not_found, mFindQuery), Toast.LENGTH_SHORT).show();
            return;
        }
        mText.requestFocus();
        mText.setSelection(index, index + query.length());
    }

    public void showGoToLineDialog() {
        EditText input = new EditText(getContext());
        input.setSingleLine();
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("1 - " + mText.getTextLineCount());
        input.setText(String.valueOf(mText.getCursorLine()));
        input.selectAll();
        new AlertDialog.Builder(getContext())
            .setTitle(R.string.sftp_action_go_to_line)
            .setView(input)
            .setPositiveButton(android.R.string.ok, (d, which) -> {
                try {
                    mText.goToLine(Math.max(1, Integer.parseInt(input.getText().toString().trim())));
                } catch (NumberFormatException ignored) {
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Choose the language to highlight the text as, or plain text. */
    public void showSyntaxDialog() {
        List<SyntaxHighlighter.Language> languages = SyntaxHighlighter.getLanguages();
        CharSequence[] names = new CharSequence[languages.size() + 1];
        names[0] = getContext().getString(R.string.sftp_syntax_plain_text);
        int checked = 0;
        for (int i = 0; i < languages.size(); i++) {
            names[i + 1] = languages.get(i).name;
            if (languages.get(i) == mText.getLanguage()) checked = i + 1;
        }
        new AlertDialog.Builder(getContext())
            .setTitle(R.string.sftp_action_syntax)
            .setSingleChoiceItems(names, checked, (d, which) -> {
                mText.setLanguage(which == 0 ? null : languages.get(which - 1));
                onStateChanged();
                d.dismiss();
            })
            .show();
    }

}
