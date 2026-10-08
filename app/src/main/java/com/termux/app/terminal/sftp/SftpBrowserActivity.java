package com.termux.app.terminal.sftp;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.terminal.backup.DownloadsDirectory;
import com.termux.app.terminal.fonts.TerminalFonts;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Browses the files of an SSH host over SFTP, shows images, and views and edits text files.
 *
 * It has three modes in one activity, which share the {@link SftpConnection}: the file browser,
 * the image viewer and the text editor. The SFTP operations run one at a time on a background
 * thread. The connection is closed while the activity is not visible, so that it does not keep an
 * ssh tab it shares the connection of from exiting, and opened again when needed.
 */
public class SftpBrowserActivity extends Activity {

    public static final String EXTRA_HOST = "com.termux.app.terminal.sftp.extra.HOST";
    public static final String EXTRA_CONTROL_PATH = "com.termux.app.terminal.sftp.extra.CONTROL_PATH";

    private static final int MODE_BROWSER = 0;
    private static final int MODE_IMAGE = 1;
    private static final int MODE_EDITOR = 2;

    private static final int SORT_NAME = 0;
    private static final int SORT_SIZE = 1;
    private static final int SORT_TIME = 2;

    private static final int REQUEST_UPLOAD = 1;

    private static final String LOG_TAG = "SftpBrowserActivity";

    /** Open the files of a host, over the connection of an ssh tab if its socket is given. */
    public static void start(@NonNull Context context, @NonNull String hostName, @Nullable String controlPath) {
        Intent intent = new Intent(context, SftpBrowserActivity.class);
        intent.putExtra(EXTRA_HOST, hostName);
        if (controlPath != null) intent.putExtra(EXTRA_CONTROL_PATH, controlPath);
        context.startActivity(intent);
    }

    /** A file of the listed directory. */
    private static final class Item {
        final SftpClient.Entry entry;
        final String path;
        /** Whether it is a directory or a symlink to one. */
        final boolean isDirectory;

        Item(SftpClient.Entry entry, String path, boolean isDirectory) {
            this.entry = entry;
            this.path = path;
            this.isDirectory = isDirectory;
        }
    }

    private String mHostName;
    private SftpConnection mConnection;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private int mMode = MODE_BROWSER;
    private int mBusyCount;

    // Browser
    private FrameLayout mRoot;
    private LinearLayout mBrowserView;
    private ProgressBar mProgressBar;
    private TextView mMessageView;
    private ListView mListView;
    private final ItemAdapter mAdapter = new ItemAdapter();
    @Nullable private String mPath;
    private List<Item> mAllItems = new ArrayList<>();
    private final List<Item> mItems = new ArrayList<>();
    private boolean mShowHidden;
    private int mSort = SORT_NAME;

    // Image viewer
    private LinearLayout mImageLayout;
    private ZoomImageView mImageView;
    private TextView mImageName;
    private Button mPreviousButton;
    private Button mNextButton;
    private List<Item> mImages = new ArrayList<>();
    private int mImageIndex;

    // Editor
    private LineNumberEditText mEditor;
    @Nullable private String mEditPath;
    private long mEditMtime;
    private long mEditSize;
    private boolean mEditCrlf;
    private boolean mEditReadOnly;
    private boolean mDirty;
    @Nullable private String mFindQuery;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mHostName = getIntent().getStringExtra(EXTRA_HOST);
        if (mHostName == null) {
            finish();
            return;
        }
        mConnection = new SftpConnection(this, mHostName, getIntent().getStringExtra(EXTRA_CONTROL_PATH));
        setTitle(mHostName);
        buildViews();
        showMode(MODE_BROWSER);
        loadDirectory(".");
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Do not keep an ssh tab that the connection is shared with from exiting.
        mExecutor.execute(() -> mConnection.disconnect());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mConnection != null) mExecutor.execute(() -> mConnection.disconnect());
        mExecutor.shutdown();
    }

    // Views

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    private void buildViews() {
        mRoot = new FrameLayout(this);
        mRoot.setBackgroundColor(Color.BLACK);

        mBrowserView = new LinearLayout(this);
        mBrowserView.setOrientation(LinearLayout.VERTICAL);
        mProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mProgressBar.setIndeterminate(true);
        mProgressBar.setVisibility(View.INVISIBLE);
        mBrowserView.addView(mProgressBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));
        mMessageView = new TextView(this);
        mMessageView.setPadding(dp(16), dp(16), dp(16), dp(16));
        mMessageView.setVisibility(View.GONE);
        mBrowserView.addView(mMessageView);
        mListView = new ListView(this);
        mListView.setAdapter(mAdapter);
        mListView.setOnItemClickListener((parent, view, position, id) -> openItem(mItems.get(position)));
        mListView.setOnItemLongClickListener((parent, view, position, id) -> {
            showItemMenu(mItems.get(position));
            return true;
        });
        mBrowserView.addView(mListView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        mRoot.addView(mBrowserView);

        mImageLayout = new LinearLayout(this);
        mImageLayout.setOrientation(LinearLayout.VERTICAL);
        mImageView = new ZoomImageView(this);
        mImageLayout.addView(mImageView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout imageBar = new LinearLayout(this);
        imageBar.setGravity(Gravity.CENTER_VERTICAL);
        mPreviousButton = new Button(this);
        mPreviousButton.setText("◀");
        mPreviousButton.setOnClickListener(v -> showImage(mImageIndex - 1));
        mImageName = new TextView(this);
        mImageName.setGravity(Gravity.CENTER);
        mImageName.setSingleLine();
        mImageName.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        mNextButton = new Button(this);
        mNextButton.setText("▶");
        mNextButton.setOnClickListener(v -> showImage(mImageIndex + 1));
        imageBar.addView(mPreviousButton);
        imageBar.addView(mImageName, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        imageBar.addView(mNextButton);
        mImageLayout.addView(imageBar);
        mRoot.addView(mImageLayout);

        mEditor = new LineNumberEditText(this);
        mEditor.setTypeface(getTerminalTypeface());
        mEditor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (!mDirty && mEditPath != null && !mEditReadOnly) {
                    mDirty = true;
                    updateTitle();
                }
            }
        });
        mRoot.addView(mEditor);

        setContentView(mRoot);
    }

    /** The font of the terminal, so that Korean and other wide characters line up like in it. */
    @NonNull
    private Typeface getTerminalTypeface() {
        try {
            if (TermuxConstants.TERMUX_FONT_FILE.isFile()) return Typeface.createFromFile(TermuxConstants.TERMUX_FONT_FILE);
        } catch (RuntimeException ignored) {
        }
        Typeface typeface = TerminalFonts.createTypeface(this, TerminalFonts.DEFAULT_FONT);
        return typeface != null ? typeface : Typeface.MONOSPACE;
    }

    private void showMode(int mode) {
        mMode = mode;
        mBrowserView.setVisibility(mode == MODE_BROWSER ? View.VISIBLE : View.GONE);
        mImageLayout.setVisibility(mode == MODE_IMAGE ? View.VISIBLE : View.GONE);
        mEditor.setVisibility(mode == MODE_EDITOR ? View.VISIBLE : View.GONE);
        if (mode != MODE_IMAGE) mImageView.setImageBitmap(null);
        updateTitle();
        invalidateOptionsMenu();
    }

    private void updateTitle() {
        if (getActionBar() == null) return;
        switch (mMode) {
            case MODE_BROWSER:
                getActionBar().setTitle(mHostName);
                getActionBar().setSubtitle(mPath);
                break;
            case MODE_IMAGE:
                getActionBar().setTitle(mImages.isEmpty() ? mHostName : mImages.get(mImageIndex).entry.name);
                getActionBar().setSubtitle(mHostName);
                break;
            case MODE_EDITOR:
                String name = mEditPath == null ? "" : SftpPaths.name(mEditPath);
                getActionBar().setTitle((mDirty ? "● " : "") + name);
                SyntaxHighlighter.Language language = mEditor.getLanguage();
                String subtitle = mEditReadOnly ? getString(R.string.sftp_read_only) : mHostName;
                getActionBar().setSubtitle(language == null ? subtitle : subtitle + " · " + language.name);
                break;
        }
    }

    private void setBusy(boolean busy) {
        mBusyCount += busy ? 1 : -1;
        mProgressBar.setVisibility(mBusyCount > 0 ? View.VISIBLE : View.INVISIBLE);
    }

    // Background tasks

    private interface Task<T> {
        T run(SftpClient client) throws IOException;
    }

    private interface Done<T> {
        void done(T result);
    }

    /** Run an SFTP operation in the background, then {@code done} with its result on the main thread, or show its error. */
    private <T> void runTask(Task<T> task, Done<T> done) {
        setBusy(true);
        mExecutor.execute(() -> {
            try {
                T result = task.run(mConnection.getClient());
                mHandler.post(() -> {
                    setBusy(false);
                    if (!isFinishing()) done.done(result);
                });
            } catch (IOException | RuntimeException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "SFTP operation failed", e);
                mHandler.post(() -> {
                    setBusy(false);
                    if (!isFinishing()) showError(e);
                });
            }
        });
    }

    private interface TransferTask {
        void run(SftpClient client, SftpClient.ProgressListener listener, AtomicBoolean cancelled) throws IOException;
    }

    /** Run a long operation in the background with a progress dialog that can cancel it. */
    private void runTransfer(String title, long total, TransferTask task, Runnable done) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(8), dp(20), 0);
        ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        progressBar.setIndeterminate(total <= 0);
        layout.addView(progressBar);
        TextView progressText = new TextView(this);
        layout.addView(progressText);
        AtomicBoolean cancelled = new AtomicBoolean();
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(layout)
            .setNegativeButton(android.R.string.cancel, (d, which) -> cancelled.set(true))
            .setCancelable(false)
            .show();
        SftpClient.ProgressListener listener = (doneBytes, totalBytes) -> mHandler.post(() -> {
            long max = totalBytes > 0 ? totalBytes : total;
            if (max > 0) progressBar.setProgress((int) (doneBytes * 1000 / max));
            progressText.setText(SftpText.formatSize(doneBytes) + (max > 0 ? " / " + SftpText.formatSize(max) : ""));
        });
        mExecutor.execute(() -> {
            IOException error = null;
            try {
                task.run(mConnection.getClient(), listener, cancelled);
            } catch (IOException e) {
                error = e;
            }
            final IOException finalError = error;
            mHandler.post(() -> {
                if (dialog.isShowing()) dialog.dismiss();
                if (isFinishing()) return;
                if (finalError instanceof InterruptedIOException && cancelled.get())
                    Toast.makeText(this, R.string.sftp_cancelled, Toast.LENGTH_SHORT).show();
                else if (finalError != null) showError(finalError);
                if (done != null) done.run();
            });
        });
    }

    private void showError(@NonNull Exception e) {
        String message = e.getMessage();
        if (e instanceof SftpClient.SftpException) {
            int status = ((SftpClient.SftpException) e).status;
            if (status == SftpClient.SSH_FX_PERMISSION_DENIED) message = getString(R.string.sftp_error_permission, message);
            else if (status == SftpClient.SSH_FX_NO_SUCH_FILE) message = getString(R.string.sftp_error_no_such_file, message);
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.sftp_error_title)
            .setMessage(message != null ? message : e.toString())
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    // Browser

    private void loadDirectory(@NonNull String path) {
        runTask(client -> {
            String realPath = client.realPath(path);
            List<Item> items = new ArrayList<>();
            for (SftpClient.Entry entry : client.list(realPath)) {
                String itemPath = SftpPaths.join(realPath, entry.name);
                boolean isDirectory = entry.attributes.isDirectory();
                if (entry.attributes.isSymlink()) {
                    try {
                        isDirectory = client.stat(itemPath).isDirectory();
                    } catch (IOException ignored) {
                        // A broken symlink.
                    }
                }
                items.add(new Item(entry, itemPath, isDirectory));
            }
            return new Object[]{realPath, items};
        }, result -> {
            mPath = (String) result[0];
            //noinspection unchecked
            mAllItems = (List<Item>) result[1];
            showItems();
            mListView.setSelection(0);
            updateTitle();
        });
    }

    /** Show the items, sorted with directories first, with or without hidden files. */
    private void showItems() {
        mItems.clear();
        for (Item item : mAllItems)
            if (mShowHidden || !item.entry.name.startsWith(".")) mItems.add(item);
        Comparator<Item> byName = (a, b) -> a.entry.name.compareToIgnoreCase(b.entry.name);
        Comparator<Item> comparator;
        switch (mSort) {
            case SORT_SIZE: comparator = (a, b) -> Long.compare(b.entry.attributes.size, a.entry.attributes.size); break;
            case SORT_TIME: comparator = (a, b) -> Long.compare(b.entry.attributes.mtime, a.entry.attributes.mtime); break;
            default: comparator = byName; break;
        }
        final Comparator<Item> sort = comparator;
        Collections.sort(mItems, (a, b) -> {
            if (a.isDirectory != b.isDirectory) return a.isDirectory ? -1 : 1;
            int result = sort.compare(a, b);
            return result != 0 ? result : byName.compare(a, b);
        });
        mAdapter.notifyDataSetChanged();
        mMessageView.setVisibility(mItems.isEmpty() ? View.VISIBLE : View.GONE);
        mMessageView.setText(R.string.sftp_empty_directory);
    }

    private void openItem(@NonNull Item item) {
        if (item.isDirectory) loadDirectory(item.path);
        else if (SftpText.isImage(item.entry.name)) openImage(item);
        else openText(item.path, false);
    }

    private void showItemMenu(@NonNull Item item) {
        List<CharSequence> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(getString(R.string.sftp_action_open));
        actions.add(() -> openItem(item));
        if (!item.isDirectory) {
            labels.add(getString(R.string.sftp_action_open_as_text));
            actions.add(() -> openText(item.path, true));
            labels.add(getString(R.string.sftp_action_download));
            actions.add(() -> download(item));
        }
        labels.add(getString(R.string.sftp_action_rename));
        actions.add(() -> rename(item));
        labels.add(getString(R.string.sftp_action_delete));
        actions.add(() -> delete(item));
        labels.add(getString(R.string.sftp_action_properties));
        actions.add(() -> showProperties(item));
        new AlertDialog.Builder(this)
            .setTitle(item.entry.name)
            .setItems(labels.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run())
            .show();
    }

    private class ItemAdapter extends BaseAdapter {
        @Override public int getCount() { return mItems.size(); }
        @Override public Object getItem(int position) { return mItems.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            TextView icon;
            TextView name;
            TextView details;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                icon = (TextView) row.getChildAt(0);
                LinearLayout texts = (LinearLayout) row.getChildAt(1);
                name = (TextView) texts.getChildAt(0);
                details = (TextView) texts.getChildAt(1);
            } else {
                row = new LinearLayout(SftpBrowserActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(8), dp(12), dp(8));
                icon = new TextView(SftpBrowserActivity.this);
                icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
                icon.setMinWidth(dp(36));
                row.addView(icon);
                LinearLayout texts = new LinearLayout(SftpBrowserActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                name = new TextView(SftpBrowserActivity.this);
                name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                name.setTextColor(Color.WHITE);
                name.setSingleLine();
                name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                details = new TextView(SftpBrowserActivity.this);
                details.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                details.setTextColor(Color.GRAY);
                details.setSingleLine();
                texts.addView(name);
                texts.addView(details);
                row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            }
            Item item = mItems.get(position);
            SftpClient.Attributes attributes = item.entry.attributes;
            String symbol = item.isDirectory ? "📁" : SftpText.isImage(item.entry.name) ? "🖼" : "📄";
            icon.setText(attributes.isSymlink() ? symbol + "↗" : symbol);
            name.setText(item.entry.name);
            StringBuilder text = new StringBuilder();
            if (!item.isDirectory) text.append(SftpText.formatSize(attributes.size)).append(" · ");
            if (attributes.mtime > 0) text.append(formatTime(attributes.mtime)).append(" · ");
            text.append(SftpPaths.formatPermissions(attributes.permissions));
            details.setText(text);
            return row;
        }
    }

    @NonNull
    private String formatTime(long seconds) {
        Date date = new Date(seconds * 1000);
        return DateFormat.getDateFormat(this).format(date) + " " + DateFormat.getTimeFormat(this).format(date);
    }

    // Image viewer

    private void openImage(@NonNull Item item) {
        mImages = new ArrayList<>();
        for (Item other : mItems)
            if (!other.isDirectory && SftpText.isImage(other.entry.name)) mImages.add(other);
        showImage(Math.max(0, mImages.indexOf(item)));
    }

    private void showImage(int index) {
        if (index < 0 || index >= mImages.size()) return;
        Item item = mImages.get(index);
        if (item.entry.attributes.size > SftpText.MAX_IMAGE_SIZE) {
            Toast.makeText(this, getString(R.string.sftp_image_too_large, SftpText.formatSize(SftpText.MAX_IMAGE_SIZE)), Toast.LENGTH_LONG).show();
            return;
        }
        int maxDimension = Math.max(2048, Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels));
        runTask(client -> {
            byte[] data = client.readAll(item.path, SftpText.MAX_IMAGE_SIZE);
            // Decode at a reduced size if the image is larger than the screen needs, to save memory.
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, options);
            options.inSampleSize = 1;
            while (options.outWidth / options.inSampleSize > maxDimension || options.outHeight / options.inSampleSize > maxDimension)
                options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (bitmap == null) throw new IOException(getString(R.string.sftp_image_unsupported));
            return bitmap;
        }, bitmap -> {
            mImageIndex = index;
            showMode(MODE_IMAGE);
            mImageView.setImageBitmap(bitmap);
            mImageName.setText((index + 1) + " / " + mImages.size());
            mPreviousButton.setEnabled(index > 0);
            mNextButton.setEnabled(index < mImages.size() - 1);
            updateTitle();
        });
    }

    // Editor

    private static final Object BINARY = new Object();

    /** Open a file in the editor, or offer to download it if it is binary, unless {@code force}. */
    private void openText(@NonNull String path, boolean force) {
        runTask(client -> {
            SftpClient.Attributes attributes = client.stat(path);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            long read = client.read(path, out, SftpText.MAX_EDIT_SIZE + 1L, null, null);
            byte[] content = out.toByteArray();
            boolean truncated = read > SftpText.MAX_EDIT_SIZE;
            int length = (int) Math.min(read, SftpText.MAX_EDIT_SIZE);
            if (!force && !SftpText.isProbablyText(content, length)) return BINARY;
            return new Object[]{attributes, SftpText.decode(content, length, truncated), truncated};
        }, result -> {
            if (result == BINARY) {
                new AlertDialog.Builder(this)
                    .setTitle(SftpPaths.name(path))
                    .setMessage(R.string.sftp_binary_file)
                    .setPositiveButton(R.string.sftp_action_download, (d, which) -> downloadPath(path, -1))
                    .setNeutralButton(R.string.sftp_action_open_as_text, (d, which) -> openText(path, true))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
                return;
            }
            Object[] loaded = (Object[]) result;
            SftpClient.Attributes attributes = (SftpClient.Attributes) loaded[0];
            SftpText.Text text = (SftpText.Text) loaded[1];
            boolean truncated = (Boolean) loaded[2];
            showEditor(path, attributes, text, truncated);
        });
    }

    private void showEditor(@NonNull String path, @NonNull SftpClient.Attributes attributes, @NonNull SftpText.Text text, boolean truncated) {
        mEditPath = null; // Do not mark the text set below as changed.
        int firstLineEnd = text.text.indexOf('\n');
        mEditor.setLanguage(SyntaxHighlighter.detect(SftpPaths.name(path),
            text.text.substring(0, firstLineEnd < 0 ? Math.min(text.text.length(), 256) : Math.min(firstLineEnd, 256))));
        mEditor.setText(text.text);
        mEditPath = path;
        mEditMtime = attributes.mtime;
        mEditSize = attributes.size;
        mEditCrlf = text.crlf;
        mEditReadOnly = truncated || !text.utf8;
        mDirty = false;
        // A read-only editor can still be scrolled and its text selected and copied.
        mEditor.setShowSoftInputOnFocus(!mEditReadOnly);
        mEditor.setKeyListener(mEditReadOnly ? null : new EditText(this).getKeyListener());
        mEditor.setTextIsSelectable(true);
        mEditor.setSelection(0);
        mEditor.scrollTo(0, 0);
        showMode(MODE_EDITOR);
        if (truncated)
            Toast.makeText(this, getString(R.string.sftp_file_truncated, SftpText.formatSize(SftpText.MAX_EDIT_SIZE)), Toast.LENGTH_LONG).show();
        else if (!text.utf8)
            Toast.makeText(this, R.string.sftp_not_utf8, Toast.LENGTH_LONG).show();
    }

    private static final Object CONFLICT = new Object();

    /**
     * Save the editor text. Unless {@code force}, the file is first checked to be unchanged on
     * the server since it was opened. The text is written to a temporary file in the same
     * directory, which then replaces the file atomically, so that the file is never left partially
     * written. The file is written in place instead if it is a symlink, if the server can't replace
     * files when renaming, or if the temporary file would have another owner than the file.
     */
    private void save(boolean force) {
        if (mEditPath == null || mEditReadOnly) return;
        final String path = mEditPath;
        final byte[] content = SftpText.encode(mEditor.getText().toString(), mEditCrlf);
        runTask(client -> {
            SftpClient.Attributes current = null;
            try {
                current = client.stat(path);
            } catch (SftpClient.SftpException e) {
                if (e.status != SftpClient.SSH_FX_NO_SUCH_FILE) throw e;
            }
            if (!force && current != null && (current.mtime != mEditMtime || current.size != mEditSize)) return CONFLICT;

            boolean inPlace = current == null || !client.hasExtension(SftpClient.EXTENSION_POSIX_RENAME) || client.lstat(path).isSymlink();
            if (!inPlace) {
                String tempPath = SftpPaths.join(SftpPaths.parent(path), "." + SftpPaths.name(path) + ".termux-" + UUID.randomUUID().toString().substring(0, 8) + ".tmp");
                int permissions = current.permissions != -1 ? current.permissions & 07777 : 0644;
                try {
                    client.write(tempPath, new ByteArrayInputStream(content), content.length, permissions, true, null, null);
                    SftpClient.Attributes temp = client.stat(tempPath);
                    if (current.uid != -1 && temp.uid != current.uid) {
                        // Replacing the file would change its owner, so write it in place.
                        client.remove(tempPath);
                        inPlace = true;
                    } else {
                        // The umask of the server may have removed bits of the permissions.
                        client.chmod(tempPath, permissions);
                        client.rename(tempPath, path, true);
                    }
                } catch (IOException e) {
                    try {
                        client.remove(tempPath);
                    } catch (IOException ignored) {
                    }
                    throw e;
                }
            }
            if (inPlace) client.write(path, new ByteArrayInputStream(content), content.length, -1, false, null, null);
            return client.stat(path);
        }, result -> {
            if (result == CONFLICT) {
                new AlertDialog.Builder(this)
                    .setTitle(R.string.sftp_conflict_title)
                    .setMessage(R.string.sftp_conflict_message)
                    .setPositiveButton(R.string.sftp_action_overwrite, (d, which) -> save(true))
                    .setNeutralButton(R.string.sftp_action_reload, (d, which) -> openText(path, true))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
                return;
            }
            SftpClient.Attributes saved = (SftpClient.Attributes) result;
            if (path.equals(mEditPath)) {
                mEditMtime = saved.mtime;
                mEditSize = saved.size;
                mDirty = false;
                updateTitle();
            }
            Toast.makeText(this, getString(R.string.sftp_saved, SftpPaths.name(path)), Toast.LENGTH_SHORT).show();
        });
    }

    private void showFindDialog() {
        EditText input = new EditText(this);
        input.setSingleLine();
        if (mFindQuery != null) {
            input.setText(mFindQuery);
            input.selectAll();
        }
        new AlertDialog.Builder(this)
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
    private void findNext() {
        if (TextUtils.isEmpty(mFindQuery)) return;
        String text = mEditor.getText().toString().toLowerCase(Locale.ROOT);
        String query = mFindQuery.toLowerCase(Locale.ROOT);
        int from = Math.max(0, mEditor.getSelectionEnd());
        int index = text.indexOf(query, from);
        if (index < 0) index = text.indexOf(query);
        if (index < 0) {
            Toast.makeText(this, getString(R.string.sftp_not_found, mFindQuery), Toast.LENGTH_SHORT).show();
            return;
        }
        mEditor.requestFocus();
        mEditor.setSelection(index, index + query.length());
    }

    /** Choose the language to highlight the editor text as, or plain text. */
    private void chooseSyntax() {
        List<SyntaxHighlighter.Language> languages = SyntaxHighlighter.getLanguages();
        CharSequence[] names = new CharSequence[languages.size() + 1];
        names[0] = getString(R.string.sftp_syntax_plain_text);
        int checked = 0;
        for (int i = 0; i < languages.size(); i++) {
            names[i + 1] = languages.get(i).name;
            if (languages.get(i) == mEditor.getLanguage()) checked = i + 1;
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.sftp_action_syntax)
            .setSingleChoiceItems(names, checked, (d, which) -> {
                mEditor.setLanguage(which == 0 ? null : languages.get(which - 1));
                updateTitle();
                d.dismiss();
            })
            .show();
    }

    /** Leave the editor, asking first if the text has unsaved changes. */
    private void closeEditor() {
        if (!mDirty) {
            mEditPath = null;
            mEditor.setText("");
            showMode(MODE_BROWSER);
            loadDirectory(mPath != null ? mPath : ".");
            return;
        }
        new AlertDialog.Builder(this)
            .setMessage(getString(R.string.sftp_unsaved_changes, mEditPath == null ? "" : SftpPaths.name(mEditPath)))
            .setPositiveButton(R.string.sftp_action_discard, (d, which) -> {
                mDirty = false;
                closeEditor();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // File operations

    private boolean ensureDownloadPermission() {
        if (!DownloadsDirectory.needsStoragePermission() ||
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) return true;
        requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 0);
        Toast.makeText(this, R.string.sftp_storage_permission, Toast.LENGTH_LONG).show();
        return false;
    }

    private void download(@NonNull Item item) {
        downloadPath(item.path, item.entry.attributes.size);
    }

    /** Download a file to the Download directory. */
    private void downloadPath(@NonNull String path, long size) {
        if (!ensureDownloadPermission()) return;
        String name = SftpPaths.name(path);
        String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(SftpText.getExtension(name));
        final String[] savedPath = new String[1];
        runTransfer(getString(R.string.sftp_downloading, name), size, (client, listener, cancelled) -> {
            DownloadsDirectory.NewFile file = DownloadsDirectory.createNewFile(this, name, mimeType != null ? mimeType : "application/octet-stream");
            try {
                client.read(path, file.out, Long.MAX_VALUE, listener, cancelled);
                file.out.close();
                savedPath[0] = file.displayPath;
            } catch (IOException e) {
                // Do not leave a partial file.
                file.delete();
                throw e;
            }
        }, () -> {
            if (savedPath[0] != null) Toast.makeText(this, getString(R.string.sftp_downloaded, savedPath[0]), Toast.LENGTH_LONG).show();
        });
    }

    private void startUpload() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, REQUEST_UPLOAD);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, R.string.msg_settings_no_file_picker, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_UPLOAD || resultCode != RESULT_OK || data == null || mPath == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null)
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null)
            uris.add(data.getData());
        if (!uris.isEmpty()) upload(uris, mPath);
    }

    /** A file to upload, with the name and size the document provider reports. */
    private static final class Upload {
        final Uri uri;
        final String name;
        final long size;

        Upload(Uri uri, String name, long size) {
            this.uri = uri;
            this.name = name;
            this.size = size;
        }
    }

    private void upload(@NonNull List<Uri> uris, @NonNull String dir) {
        List<Upload> uploads = new ArrayList<>();
        for (Uri uri : uris) {
            String name = null;
            long size = -1;
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (nameIndex >= 0) name = cursor.getString(nameIndex);
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex);
                }
            } catch (RuntimeException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to query " + uri, e);
            }
            if (name == null) name = uri.getLastPathSegment();
            if (name == null || !SftpPaths.isValidName(name)) name = name == null ? "upload" : name.replace('/', '_');
            uploads.add(new Upload(uri, name, size));
        }
        // Ask before replacing existing files.
        runTask(client -> {
            List<String> existing = new ArrayList<>();
            for (Upload upload : uploads) {
                try {
                    client.stat(SftpPaths.join(dir, upload.name));
                    existing.add(upload.name);
                } catch (SftpClient.SftpException ignored) {
                }
            }
            return existing;
        }, existing -> {
            if (existing.isEmpty()) {
                doUpload(uploads, dir);
                return;
            }
            new AlertDialog.Builder(this)
                .setTitle(R.string.sftp_replace_title)
                .setMessage(getString(R.string.sftp_replace_message, TextUtils.join(", ", existing)))
                .setPositiveButton(R.string.sftp_action_replace, (d, which) -> doUpload(uploads, dir))
                .setNeutralButton(R.string.sftp_action_skip, (d, which) -> {
                    List<Upload> remaining = new ArrayList<>();
                    for (Upload upload : uploads) if (!existing.contains(upload.name)) remaining.add(upload);
                    if (!remaining.isEmpty()) doUpload(remaining, dir);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        });
    }

    private void doUpload(@NonNull List<Upload> uploads, @NonNull String dir) {
        long total = 0;
        for (Upload upload : uploads) total = upload.size >= 0 && total >= 0 ? total + upload.size : -1;
        final long totalSize = total;
        String title = uploads.size() == 1 ? getString(R.string.sftp_uploading, uploads.get(0).name)
            : getString(R.string.sftp_uploading_files, uploads.size());
        runTransfer(title, totalSize, (client, listener, cancelled) -> {
            long done = 0;
            for (Upload upload : uploads) {
                final long before = done;
                try (InputStream in = getContentResolver().openInputStream(upload.uri)) {
                    if (in == null) throw new IOException("Failed to open " + upload.uri);
                    done += client.write(SftpPaths.join(dir, upload.name), in, upload.size, 0644, false,
                        (written, size) -> listener.onProgress(before + written, totalSize), cancelled);
                }
            }
        }, () -> loadDirectory(dir));
    }

    /** Ask for a name, then run {@code action} with it if it is valid. */
    private void askName(int titleId, @Nullable String initial, @NonNull Done<String> action) {
        EditText input = new EditText(this);
        input.setSingleLine();
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (initial != null) {
            input.setText(initial);
            int dot = initial.lastIndexOf('.');
            input.setSelection(0, dot > 0 ? dot : initial.length());
        }
        LinearLayout layout = new LinearLayout(this);
        layout.setPadding(dp(20), dp(8), dp(20), 0);
        layout.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
            .setTitle(titleId)
            .setView(layout)
            .setPositiveButton(android.R.string.ok, (d, which) -> {
                String name = input.getText().toString().trim();
                if (!SftpPaths.isValidName(name)) {
                    Toast.makeText(this, R.string.sftp_invalid_name, Toast.LENGTH_LONG).show();
                    return;
                }
                action.done(name);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void rename(@NonNull Item item) {
        askName(R.string.sftp_action_rename, item.entry.name, name -> {
            if (name.equals(item.entry.name)) return;
            String target = SftpPaths.join(SftpPaths.parent(item.path), name);
            runTask(client -> {
                client.rename(item.path, target, false);
                return true;
            }, result -> loadDirectory(mPath));
        });
    }

    private void delete(@NonNull Item item) {
        String message = item.isDirectory && !item.entry.attributes.isSymlink()
            ? getString(R.string.sftp_delete_directory, item.entry.name) : getString(R.string.sftp_delete_file, item.entry.name);
        new AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(R.string.sftp_action_delete, (d, which) ->
                runTransfer(getString(R.string.sftp_deleting, item.entry.name), 0,
                    (client, listener, cancelled) -> client.removeRecursively(item.path, cancelled),
                    () -> loadDirectory(mPath)))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void newFolder() {
        if (mPath == null) return;
        askName(R.string.sftp_action_new_folder, null, name -> runTask(client -> {
            client.mkdir(SftpPaths.join(mPath, name));
            return true;
        }, result -> loadDirectory(mPath)));
    }

    private void newFile() {
        if (mPath == null) return;
        askName(R.string.sftp_action_new_file, null, name -> {
            String path = SftpPaths.join(mPath, name);
            runTask(client -> {
                client.write(path, new ByteArrayInputStream(new byte[0]), 0, 0644, true, null, null);
                return true;
            }, result -> openText(path, true));
        });
    }

    private void goToPath() {
        EditText input = new EditText(this);
        input.setSingleLine();
        input.setText(mPath);
        new AlertDialog.Builder(this)
            .setTitle(R.string.sftp_action_go_to)
            .setView(input)
            .setPositiveButton(android.R.string.ok, (d, which) -> {
                String path = input.getText().toString().trim();
                if (!path.isEmpty()) loadDirectory(path);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showProperties(@NonNull Item item) {
        SftpClient.Attributes attributes = item.entry.attributes;
        StringBuilder text = new StringBuilder();
        text.append(getString(R.string.sftp_property_path)).append(": ").append(item.path).append('\n');
        if (!item.isDirectory) text.append(getString(R.string.sftp_property_size)).append(": ")
            .append(SftpText.formatSize(attributes.size)).append(" (").append(attributes.size).append(")\n");
        if (attributes.permissions != -1) text.append(getString(R.string.sftp_property_permissions)).append(": ")
            .append(SftpPaths.formatPermissions(attributes.permissions))
            .append(String.format(Locale.ROOT, " (%04o)", attributes.permissions & 07777)).append('\n');
        if (attributes.uid != -1) text.append(getString(R.string.sftp_property_owner)).append(": ")
            .append(attributes.uid).append(':').append(attributes.gid).append('\n');
        if (attributes.mtime > 0) text.append(getString(R.string.sftp_property_modified)).append(": ").append(formatTime(attributes.mtime)).append('\n');
        if (!TextUtils.isEmpty(item.entry.longName)) text.append('\n').append(item.entry.longName);
        new AlertDialog.Builder(this)
            .setTitle(item.entry.name)
            .setMessage(text.toString().trim())
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    // Menu and navigation

    private static final int MENU_REFRESH = 1;
    private static final int MENU_GO_TO = 2;
    private static final int MENU_NEW_FOLDER = 3;
    private static final int MENU_NEW_FILE = 4;
    private static final int MENU_UPLOAD = 5;
    private static final int MENU_SHOW_HIDDEN = 6;
    private static final int MENU_SORT = 7;
    private static final int MENU_CLOSE = 8;
    private static final int MENU_SAVE = 10;
    private static final int MENU_FIND = 11;
    private static final int MENU_FIND_NEXT = 12;
    private static final int MENU_RELOAD = 13;
    private static final int MENU_SYNTAX = 14;
    private static final int MENU_DOWNLOAD_IMAGE = 20;

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.clear();
        switch (mMode) {
            case MODE_BROWSER:
                menu.add(Menu.NONE, MENU_REFRESH, Menu.NONE, R.string.sftp_action_refresh);
                menu.add(Menu.NONE, MENU_GO_TO, Menu.NONE, R.string.sftp_action_go_to);
                menu.add(Menu.NONE, MENU_NEW_FOLDER, Menu.NONE, R.string.sftp_action_new_folder);
                menu.add(Menu.NONE, MENU_NEW_FILE, Menu.NONE, R.string.sftp_action_new_file);
                menu.add(Menu.NONE, MENU_UPLOAD, Menu.NONE, R.string.sftp_action_upload);
                menu.add(Menu.NONE, MENU_SHOW_HIDDEN, Menu.NONE, R.string.sftp_action_show_hidden).setCheckable(true).setChecked(mShowHidden);
                menu.add(Menu.NONE, MENU_SORT, Menu.NONE, R.string.sftp_action_sort);
                menu.add(Menu.NONE, MENU_CLOSE, Menu.NONE, R.string.sftp_action_close);
                break;
            case MODE_EDITOR:
                if (!mEditReadOnly) menu.add(Menu.NONE, MENU_SAVE, Menu.NONE, R.string.sftp_action_save).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                menu.add(Menu.NONE, MENU_FIND, Menu.NONE, R.string.sftp_action_find);
                menu.add(Menu.NONE, MENU_FIND_NEXT, Menu.NONE, R.string.sftp_action_find_next);
                menu.add(Menu.NONE, MENU_SYNTAX, Menu.NONE, R.string.sftp_action_syntax);
                menu.add(Menu.NONE, MENU_RELOAD, Menu.NONE, R.string.sftp_action_reload);
                break;
            case MODE_IMAGE:
                menu.add(Menu.NONE, MENU_DOWNLOAD_IMAGE, Menu.NONE, R.string.sftp_action_download);
                break;
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_REFRESH: loadDirectory(mPath != null ? mPath : "."); return true;
            case MENU_GO_TO: goToPath(); return true;
            case MENU_NEW_FOLDER: newFolder(); return true;
            case MENU_NEW_FILE: newFile(); return true;
            case MENU_UPLOAD: startUpload(); return true;
            case MENU_SHOW_HIDDEN:
                mShowHidden = !mShowHidden;
                showItems();
                invalidateOptionsMenu();
                return true;
            case MENU_SORT:
                new AlertDialog.Builder(this)
                    .setTitle(R.string.sftp_action_sort)
                    .setSingleChoiceItems(new CharSequence[]{getString(R.string.sftp_sort_name), getString(R.string.sftp_sort_size),
                        getString(R.string.sftp_sort_time)}, mSort, (d, which) -> {
                        mSort = which;
                        showItems();
                        d.dismiss();
                    })
                    .show();
                return true;
            case MENU_CLOSE: finish(); return true;
            case MENU_SAVE: save(false); return true;
            case MENU_FIND: showFindDialog(); return true;
            case MENU_FIND_NEXT:
                if (mFindQuery == null) showFindDialog();
                else findNext();
                return true;
            case MENU_SYNTAX:
                chooseSyntax();
                return true;
            case MENU_RELOAD:
                if (mEditPath != null) openText(mEditPath, true);
                return true;
            case MENU_DOWNLOAD_IMAGE:
                if (!mImages.isEmpty()) download(mImages.get(mImageIndex));
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onBackPressed() {
        switch (mMode) {
            case MODE_EDITOR:
                closeEditor();
                return;
            case MODE_IMAGE:
                showMode(MODE_BROWSER);
                return;
            default:
                if (mPath != null && !mPath.equals("/")) loadDirectory(SftpPaths.parent(mPath));
                else super.onBackPressed();
        }
    }

}
