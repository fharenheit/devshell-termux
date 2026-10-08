package com.termux.app.terminal.sftp;

import com.termux.shared.locale.AppLocale;
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
import android.provider.DocumentsContract;
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
import com.termux.app.terminal.ssh.SshHostKeyDialog;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.CharacterCodingException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;
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

    /** Show the app in the language chosen in it, see {@link AppLocale}. */
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLocale.wrap(newBase));
    }

    public static final String EXTRA_HOST = "com.termux.app.terminal.sftp.extra.HOST";
    public static final String EXTRA_CONTROL_PATH = "com.termux.app.terminal.sftp.extra.CONTROL_PATH";
    private static final String EXTRA_SHOW_TRANSFERS = "com.termux.app.terminal.sftp.extra.SHOW_TRANSFERS";

    private static final int MODE_BROWSER = 0;
    private static final int MODE_IMAGE = 1;
    private static final int MODE_EDITOR = 2;

    private static final int SORT_NAME = 0;
    private static final int SORT_SIZE = 1;
    private static final int SORT_TIME = 2;

    private static final int REQUEST_UPLOAD = 1;
    private static final int REQUEST_UPLOAD_FOLDER = 2;

    private static final String LOG_TAG = "SftpBrowserActivity";

    /** Open the files of a host, over the connection of an ssh tab if its socket is given. */
    public static void start(@NonNull Context context, @NonNull String hostName, @Nullable String controlPath) {
        context.startActivity(newIntent(context, hostName, controlPath, false));
    }

    /** An intent to open the files of a host, showing its transfers if {@code showTransfers}. */
    @NonNull
    static Intent newIntent(@NonNull Context context, @NonNull String hostName, @Nullable String controlPath, boolean showTransfers) {
        Intent intent = new Intent(context, SftpBrowserActivity.class);
        intent.putExtra(EXTRA_HOST, hostName);
        if (controlPath != null) intent.putExtra(EXTRA_CONTROL_PATH, controlPath);
        if (showTransfers) intent.putExtra(EXTRA_SHOW_TRANSFERS, true);
        return intent;
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
    @Nullable private String mControlPath;
    private SftpTransfers mTransfers;
    private final SftpTransfers.Listener mTransfersListener = new SftpTransfers.Listener() {
        @Override
        public void onTransfersChanged() {
            invalidateOptionsMenu();
        }

        @Override
        public void onTransferFinished(@NonNull SftpTransfers.Transfer transfer) {
            onTransferDone(transfer);
        }
    };
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
    /** The paths of the selected items of the directory, in the order they were selected. */
    private final Set<String> mSelection = new LinkedHashSet<>();
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
    private SftpEditorView mEditor;
    @Nullable private String mEditPath;
    private long mEditMtime;
    private long mEditSize;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mHostName = getIntent().getStringExtra(EXTRA_HOST);
        if (mHostName == null) {
            finish();
            return;
        }
        mControlPath = getIntent().getStringExtra(EXTRA_CONTROL_PATH);
        mConnection = new SftpConnection(this, mHostName, mControlPath);
        mTransfers = SftpTransfers.get(this);
        mTransfers.addListener(mTransfersListener);
        setTitle(mHostName);
        buildViews();
        showMode(MODE_BROWSER);
        loadDirectory(".");
        if (savedInstanceState == null && getIntent().getBooleanExtra(EXTRA_SHOW_TRANSFERS, false)) showTransfers();
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
        if (mTransfers != null) mTransfers.removeListener(mTransfersListener);
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
        mListView.setOnItemClickListener((parent, view, position, id) -> {
            if (!mSelection.isEmpty()) toggleSelection(mItems.get(position));
            else openItem(mItems.get(position));
        });
        mListView.setOnItemLongClickListener((parent, view, position, id) -> {
            if (!mSelection.isEmpty()) toggleSelection(mItems.get(position));
            else showItemMenu(mItems.get(position));
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

        mEditor = new SftpEditorView(this, getTerminalTypeface());
        mEditor.setListener(() -> {
            updateTitle();
            invalidateOptionsMenu();
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
                getActionBar().setTitle(mSelection.isEmpty() ? mHostName : getString(R.string.sftp_selected_count, mSelection.size()));
                getActionBar().setSubtitle(mPath);
                break;
            case MODE_IMAGE:
                getActionBar().setTitle(mImages.isEmpty() ? mHostName : mImages.get(mImageIndex).entry.name);
                getActionBar().setSubtitle(mHostName);
                break;
            case MODE_EDITOR:
                String name = mEditPath == null ? "" : SftpPaths.name(mEditPath);
                getActionBar().setTitle((mEditor.isDirty() ? "● " : "") + name);
                SyntaxHighlighter.Language language = mEditor.getTextView().getLanguage();
                String subtitle = mEditor.isReadOnly() ? getString(R.string.sftp_read_only) : mHostName;
                if (mEditor.isHex()) subtitle += " · HEX";
                else if (language != null) subtitle += " · " + language.name;
                getActionBar().setSubtitle(subtitle);
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
        if (e instanceof SftpConnection.HostKeyException) {
            new AlertDialog.Builder(this)
                .setTitle(R.string.sftp_error_title)
                .setMessage(e.getMessage())
                .setPositiveButton(R.string.action_ssh_host_key_check, (d, which) ->
                    SshHostKeyDialog.show(this, mHostName, () -> loadDirectory(mPath != null ? mPath : ".")))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return;
        }
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
            mSelection.clear();
            invalidateOptionsMenu();
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
        labels.add(getString(R.string.sftp_action_select));
        actions.add(() -> toggleSelection(item));
        if (!item.isDirectory) {
            labels.add(getString(R.string.sftp_action_open_as_text));
            actions.add(() -> openText(item.path, true));
            labels.add(getString(R.string.sftp_action_download));
            actions.add(() -> download(item));
        } else {
            labels.add(getString(R.string.sftp_action_download_folder));
            actions.add(() -> downloadItems(Collections.singletonList(item)));
            labels.add(getString(R.string.sftp_action_bookmark));
            actions.add(() -> addBookmark(item.path));
        }
        labels.add(getString(R.string.sftp_action_rename));
        actions.add(() -> rename(item));
        labels.add(getString(R.string.sftp_action_permissions));
        actions.add(() -> changePermissions(Collections.singletonList(item)));
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
            row.setBackgroundColor(mSelection.contains(item.path) ? 0xFF1E3A5F : Color.TRANSPARENT);
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
    private static final int OPEN_AUTO = 0;
    private static final int OPEN_TEXT = 1;
    private static final int OPEN_HEX = 2;

    /** Open a file in the editor, or offer to download it if it is binary, unless {@code force}. */
    private void openText(@NonNull String path, boolean force) {
        openFile(path, force ? OPEN_TEXT : OPEN_AUTO);
    }

    /**
     * Open a file in the editor: as text, in hex, or as text unless it looks binary, then ask how.
     * Up to {@link SftpText#MAX_EDIT_SIZE} is read for text, and {@link SftpText#MAX_HEX_SIZE}
     * for hex.
     */
    private void openFile(@NonNull String path, int how) {
        runTask(client -> {
            SftpClient.Attributes attributes = client.stat(path);
            int max = how == OPEN_HEX ? SftpText.MAX_HEX_SIZE : SftpText.MAX_EDIT_SIZE;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            long read = client.read(path, out, max + 1L, null, null);
            byte[] content = out.toByteArray();
            int count = (int) Math.min(read, max);
            if (how == OPEN_AUTO && !SftpText.isProbablyText(content, count)) return BINARY;
            return new Object[]{attributes, content, count, read > max};
        }, result -> {
            if (result == BINARY) {
                CharSequence[] actions = {getString(R.string.sftp_action_open_hex), getString(R.string.sftp_action_open_as_text),
                    getString(R.string.sftp_action_download)};
                new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.sftp_binary_file_title, SftpPaths.name(path)))
                    .setItems(actions, (d, which) -> {
                        if (which == 0) openFile(path, OPEN_HEX);
                        else if (which == 1) openFile(path, OPEN_TEXT);
                        else downloadPath(path, -1);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
                return;
            }
            Object[] loaded = (Object[]) result;
            mEditPath = path;
            SftpClient.Attributes attributes = (SftpClient.Attributes) loaded[0];
            mEditMtime = attributes.mtime;
            mEditSize = attributes.size;
            mEditor.load(SftpPaths.name(path), (byte[]) loaded[1], (Integer) loaded[2], (Boolean) loaded[3], how == OPEN_HEX);
            showMode(MODE_EDITOR);
        });
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
        if (mEditPath == null || mEditor.isReadOnly()) return;
        final String path = mEditPath;
        final byte[] content;
        try {
            content = mEditor.getContent();
        } catch (CharacterCodingException e) {
            showError(new IOException(getString(R.string.sftp_cannot_encode_save)));
            return;
        }
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
                    .setNeutralButton(R.string.sftp_action_reload, (d, which) -> openFile(path, OPEN_TEXT))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
                return;
            }
            SftpClient.Attributes saved = (SftpClient.Attributes) result;
            if (path.equals(mEditPath)) {
                mEditMtime = saved.mtime;
                mEditSize = saved.size;
                mEditor.markSaved(content);
            }
            Toast.makeText(this, getString(R.string.sftp_saved, SftpPaths.name(path)), Toast.LENGTH_SHORT).show();
        });
    }

    /** Leave the editor, asking first if the text has unsaved changes. */
    private void closeEditor() {
        if (!mEditor.isDirty()) {
            mEditPath = null;
            mEditor.clear();
            showMode(MODE_BROWSER);
            loadDirectory(mPath != null ? mPath : ".");
            return;
        }
        new AlertDialog.Builder(this)
            .setMessage(getString(R.string.sftp_unsaved_changes, mEditPath == null ? "" : SftpPaths.name(mEditPath)))
            .setPositiveButton(R.string.sftp_action_discard, (d, which) -> {
                mEditor.clear();
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

    /** Download a file to the Download directory in the background. */
    private void downloadPath(@NonNull String path, long size) {
        if (!ensureDownloadPermission()) return;
        String name = SftpPaths.name(path);
        enqueue(getString(R.string.sftp_downloading, name), size, null, Collections.emptyList(),
            SftpTransfers.download(Collections.singletonList(new SftpTransfers.DownloadItem(path, name, false, size))));
    }

    /** Queue a transfer of this host, and say so. */
    private void enqueue(@NonNull String title, long total, @Nullable String remoteDir, @NonNull List<Uri> grantedUris,
                         @NonNull SftpTransfers.Job job) {
        mTransfers.enqueue(mHostName, mControlPath, title, total, remoteDir, grantedUris, job);
        Toast.makeText(this, getString(R.string.sftp_transfer_queued_toast, title), Toast.LENGTH_SHORT).show();
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
        if (requestCode == REQUEST_UPLOAD_FOLDER && resultCode == RESULT_OK && data != null && data.getData() != null && mPath != null) {
            uploadFolder(data.getData(), mPath);
            return;
        }
        if (requestCode != REQUEST_UPLOAD || resultCode != RESULT_OK || data == null || mPath == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null)
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null)
            uris.add(data.getData());
        if (!uris.isEmpty()) upload(uris, mPath);
    }

    private void upload(@NonNull List<Uri> uris, @NonNull String dir) {
        List<SftpTransfers.Upload> uploads = new ArrayList<>();
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
            uploads.add(new SftpTransfers.Upload(uri, name, size));
        }
        // Ask before replacing existing files.
        runTask(client -> {
            List<String> existing = new ArrayList<>();
            for (SftpTransfers.Upload upload : uploads) {
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
                    List<SftpTransfers.Upload> remaining = new ArrayList<>();
                    for (SftpTransfers.Upload upload : uploads) if (!existing.contains(upload.name)) remaining.add(upload);
                    if (!remaining.isEmpty()) doUpload(remaining, dir);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        });
    }

    private void doUpload(@NonNull List<SftpTransfers.Upload> uploads, @NonNull String dir) {
        long total = 0;
        for (SftpTransfers.Upload upload : uploads) total = upload.size >= 0 && total >= 0 ? total + upload.size : -1;
        String title = uploads.size() == 1 ? getString(R.string.sftp_uploading, uploads.get(0).name)
            : getString(R.string.sftp_uploading_files, uploads.size());
        List<Uri> uris = new ArrayList<>();
        for (SftpTransfers.Upload upload : uploads) uris.add(upload.uri);
        enqueue(title, total, dir, uris, SftpTransfers.upload(uploads, dir));
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

    // Selection and operations on several items

    private void toggleSelection(@NonNull Item item) {
        if (!mSelection.remove(item.path)) mSelection.add(item.path);
        onSelectionChanged();
    }

    private void onSelectionChanged() {
        mAdapter.notifyDataSetChanged();
        updateTitle();
        invalidateOptionsMenu();
    }

    @NonNull
    private List<Item> getSelectedItems() {
        List<Item> items = new ArrayList<>();
        for (Item item : mItems) if (mSelection.contains(item.path)) items.add(item);
        return items;
    }

    /** Download files, and folders with their contents into folders of the same names, to the Download directory. */
    private void downloadItems(@NonNull List<Item> items) {
        if (items.isEmpty() || !ensureDownloadPermission()) return;
        List<SftpTransfers.DownloadItem> downloads = new ArrayList<>();
        long total = 0;
        for (Item item : items) {
            boolean folder = item.isDirectory && !item.entry.attributes.isSymlink();
            // A symlink to a folder is not followed.
            if (item.isDirectory && !folder) continue;
            downloads.add(new SftpTransfers.DownloadItem(item.path, item.entry.name, folder, item.entry.attributes.size));
            total = folder || total < 0 ? -1 : total + item.entry.attributes.size;
        }
        if (downloads.isEmpty()) return;
        String title = downloads.size() == 1 ? getString(R.string.sftp_downloading, downloads.get(0).name)
            : getString(R.string.sftp_downloading_items, downloads.size());
        enqueue(title, total, null, Collections.emptyList(), SftpTransfers.download(downloads));
        mSelection.clear();
        onSelectionChanged();
    }

    private void deleteItems(@NonNull List<Item> items) {
        if (items.isEmpty()) return;
        if (items.size() == 1) {
            delete(items.get(0));
            return;
        }
        new AlertDialog.Builder(this)
            .setMessage(getString(R.string.sftp_delete_items, items.size()))
            .setPositiveButton(R.string.sftp_action_delete, (d, which) ->
                runTransfer(getString(R.string.sftp_deleting_items, items.size()), 0, (client, listener, cancelled) -> {
                    for (Item item : items) client.removeRecursively(item.path, cancelled);
                }, () -> loadDirectory(mPath)))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Change the permissions of items, starting from those of the first one. */
    private void changePermissions(@NonNull List<Item> items) {
        if (items.isEmpty()) return;
        int permissions = items.get(0).entry.attributes.permissions;
        String title = items.size() == 1 ? items.get(0).entry.name : getString(R.string.sftp_selected_count, items.size());
        SftpPermissionsDialog.show(this, title, permissions == -1 ? 0644 : permissions & 07777, mode ->
            runTask(client -> {
                for (Item item : items) client.chmod(item.path, mode);
                return true;
            }, result -> loadDirectory(mPath)));
    }

    // Folder upload

    private void startUploadFolder() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_UPLOAD_FOLDER);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, R.string.msg_settings_no_file_picker, Toast.LENGTH_LONG).show();
        }
    }

    /** Upload a picked folder with its contents into a directory, merging with a folder of the same name there. */
    private void uploadFolder(@NonNull Uri treeUri, @NonNull String dir) {
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);
        String name = null;
        try (Cursor cursor = getContentResolver().query(DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId),
            new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (RuntimeException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to query " + treeUri, e);
        }
        if (name == null || !SftpPaths.isValidName(name)) name = "upload";
        final String folderName = name;
        String target = SftpPaths.join(dir, folderName);
        runTask(client -> {
            try {
                return client.stat(target).isDirectory() ? Boolean.TRUE : Boolean.FALSE;
            } catch (SftpClient.SftpException e) {
                return null;
            }
        }, exists -> {
            if (exists == Boolean.FALSE) {
                showError(new IOException(getString(R.string.sftp_upload_folder_file_exists, folderName)));
                return;
            }
            Runnable upload = () -> doUploadFolder(treeUri, rootId, folderName, target);
            if (exists == null) {
                upload.run();
                return;
            }
            new AlertDialog.Builder(this)
                .setMessage(getString(R.string.sftp_upload_folder_merge, folderName))
                .setPositiveButton(R.string.sftp_action_upload, (d, which) -> upload.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        });
    }

    private void doUploadFolder(@NonNull Uri treeUri, @NonNull String rootId, @NonNull String folderName, @NonNull String target) {
        enqueue(getString(R.string.sftp_uploading, folderName), -1, SftpPaths.parent(target),
            Collections.singletonList(treeUri), SftpTransfers.uploadFolder(treeUri, rootId, target));
    }

    // Transfers

    /** Tell about a finished transfer of this host, and show the changes of an upload to the shown folder. */
    private void onTransferDone(@NonNull SftpTransfers.Transfer transfer) {
        if (!transfer.hostName.equals(mHostName) || isFinishing()) return;
        if (transfer.getState() == SftpTransfers.DONE && transfer.getMessage() != null)
            Toast.makeText(this, transfer.getMessage(), Toast.LENGTH_LONG).show();
        else if (transfer.getState() == SftpTransfers.FAILED)
            Toast.makeText(this, transfer.describe(this), Toast.LENGTH_LONG).show();
        if (transfer.remoteDir != null && transfer.remoteDir.equals(mPath) && mMode == MODE_BROWSER) loadDirectory(mPath);
    }

    /** Show the transfers of all hosts, to follow and cancel them. */
    private void showTransfers() {
        List<SftpTransfers.Transfer> shown = new ArrayList<>();
        BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return shown.size();
            }

            @Override
            public Object getItem(int position) {
                return shown.get(position);
            }

            @Override
            public long getItemId(int position) {
                return shown.get(position).id;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout layout = (LinearLayout) convertView;
                if (layout == null) {
                    layout = new LinearLayout(SftpBrowserActivity.this);
                    layout.setOrientation(LinearLayout.VERTICAL);
                    layout.setPadding(dp(20), dp(8), dp(20), dp(8));
                    TextView title = new TextView(SftpBrowserActivity.this);
                    title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                    title.setSingleLine();
                    title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                    layout.addView(title);
                    ProgressBar progress = new ProgressBar(SftpBrowserActivity.this, null, android.R.attr.progressBarStyleHorizontal);
                    progress.setMax(1000);
                    layout.addView(progress);
                    TextView status = new TextView(SftpBrowserActivity.this);
                    status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                    status.setAlpha(0.7f);
                    layout.addView(status);
                }
                SftpTransfers.Transfer transfer = shown.get(position);
                ((TextView) layout.getChildAt(0)).setText(transfer.title);
                ProgressBar progress = (ProgressBar) layout.getChildAt(1);
                progress.setVisibility(transfer.getState() == SftpTransfers.RUNNING ? View.VISIBLE : View.GONE);
                progress.setIndeterminate(transfer.getTotal() <= 0);
                if (transfer.getTotal() > 0) progress.setProgress((int) Math.min(1000, transfer.getDone() * 1000 / transfer.getTotal()));
                ((TextView) layout.getChildAt(2)).setText(transfer.hostName + " · " + transfer.describe(SftpBrowserActivity.this));
                return layout;
            }
        };
        ListView list = new ListView(this);
        list.setAdapter(adapter);
        TextView empty = new TextView(this);
        empty.setText(R.string.sftp_transfers_empty);
        empty.setPadding(dp(20), dp(16), dp(20), dp(16));
        FrameLayout frame = new FrameLayout(this);
        frame.addView(list);
        frame.addView(empty);
        list.setEmptyView(empty);
        Runnable update = () -> {
            shown.clear();
            // The newest first.
            List<SftpTransfers.Transfer> transfers = mTransfers.getTransfers();
            for (int i = transfers.size() - 1; i >= 0; i--) shown.add(transfers.get(i));
            adapter.notifyDataSetChanged();
        };
        update.run();
        SftpTransfers.Listener listener = update::run;
        mTransfers.addListener(listener);
        list.setOnItemClickListener((parent, view, position, id) -> {
            SftpTransfers.Transfer transfer = shown.get(position);
            if (!transfer.isFinished()) {
                new AlertDialog.Builder(this)
                    .setMessage(getString(R.string.sftp_transfer_cancel_confirm, transfer.title))
                    .setPositiveButton(R.string.sftp_transfer_cancel, (d, which) -> mTransfers.cancel(transfer))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            } else if (transfer.getState() == SftpTransfers.FAILED) {
                new AlertDialog.Builder(this)
                    .setTitle(transfer.title)
                    .setMessage(transfer.getMessage())
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            }
        });
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(R.string.sftp_action_transfers)
            .setView(frame)
            .setPositiveButton(R.string.sftp_action_close, null)
            .setNeutralButton(R.string.sftp_transfers_clear, null)
            .setNegativeButton(R.string.sftp_transfers_cancel_all, null)
            .setOnDismissListener(d -> mTransfers.removeListener(listener))
            .show();
        // Keep the dialog open for these.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> mTransfers.clearFinished());
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            if (mTransfers.getActiveCount() == 0) return;
            new AlertDialog.Builder(this)
                .setMessage(R.string.sftp_transfers_cancel_all_confirm)
                .setPositiveButton(R.string.sftp_transfers_cancel_all, (d, which) -> mTransfers.cancelAll())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        });
    }

    // Bookmarks

    private void addBookmark(@NonNull String path) {
        SftpBookmarks.add(this, mHostName, path);
        Toast.makeText(this, getString(R.string.sftp_bookmark_added, path), Toast.LENGTH_SHORT).show();
    }

    /** Show the bookmarks of the host, with the home and root folders, and adding the current folder. */
    private void showBookmarks() {
        List<String> bookmarks = SftpBookmarks.load(this, mHostName);
        List<CharSequence> labels = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        if (mPath != null && !bookmarks.contains(mPath)) {
            labels.add(getString(R.string.sftp_bookmark_add_current, mPath));
            paths.add(null);
        }
        labels.add(getString(R.string.sftp_bookmark_home));
        paths.add(".");
        labels.add("/");
        paths.add("/");
        for (String bookmark : bookmarks) {
            labels.add("★ " + bookmark);
            paths.add(bookmark);
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(R.string.sftp_action_bookmarks)
            .setItems(labels.toArray(new CharSequence[0]), (d, which) -> {
                String path = paths.get(which);
                if (path == null) addBookmark(mPath);
                else loadDirectory(path);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.show();
        dialog.getListView().setOnItemLongClickListener((parent, view, position, id) -> {
            String path = paths.get(position);
            if (path == null || !bookmarks.contains(path)) return false;
            dialog.dismiss();
            new AlertDialog.Builder(this)
                .setMessage(getString(R.string.sftp_bookmark_remove, path))
                .setPositiveButton(R.string.sftp_action_delete, (d, which) -> SftpBookmarks.remove(this, mHostName, path))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return true;
        });
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
    private static final int MENU_GO_TO_LINE = 15;
    private static final int MENU_DOWNLOAD_IMAGE = 20;
    private static final int MENU_UPLOAD_FOLDER = 30;
    private static final int MENU_BOOKMARKS = 31;
    private static final int MENU_SELECT_ALL = 32;
    private static final int MENU_SELECTION_DOWNLOAD = 33;
    private static final int MENU_SELECTION_DELETE = 34;
    private static final int MENU_SELECTION_PERMISSIONS = 35;
    private static final int MENU_SELECTION_CLEAR = 36;
    private static final int MENU_TRANSFERS = 37;

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.clear();
        switch (mMode) {
            case MODE_BROWSER:
                if (!mSelection.isEmpty()) {
                    menu.add(Menu.NONE, MENU_SELECTION_DOWNLOAD, Menu.NONE, R.string.sftp_action_download);
                    menu.add(Menu.NONE, MENU_SELECTION_DELETE, Menu.NONE, R.string.sftp_action_delete);
                    menu.add(Menu.NONE, MENU_SELECTION_PERMISSIONS, Menu.NONE, R.string.sftp_action_permissions);
                    menu.add(Menu.NONE, MENU_SELECT_ALL, Menu.NONE, R.string.sftp_action_select_all);
                    menu.add(Menu.NONE, MENU_SELECTION_CLEAR, Menu.NONE, R.string.sftp_action_clear_selection);
                    break;
                }
                menu.add(Menu.NONE, MENU_BOOKMARKS, Menu.NONE, R.string.sftp_action_bookmarks);
                int active = mTransfers.getActiveCount();
                menu.add(Menu.NONE, MENU_TRANSFERS, Menu.NONE, active > 0
                    ? getString(R.string.sftp_action_transfers_count, active) : getString(R.string.sftp_action_transfers));
                menu.add(Menu.NONE, MENU_REFRESH, Menu.NONE, R.string.sftp_action_refresh);
                menu.add(Menu.NONE, MENU_GO_TO, Menu.NONE, R.string.sftp_action_go_to);
                menu.add(Menu.NONE, MENU_NEW_FOLDER, Menu.NONE, R.string.sftp_action_new_folder);
                menu.add(Menu.NONE, MENU_NEW_FILE, Menu.NONE, R.string.sftp_action_new_file);
                menu.add(Menu.NONE, MENU_UPLOAD, Menu.NONE, R.string.sftp_action_upload);
                menu.add(Menu.NONE, MENU_UPLOAD_FOLDER, Menu.NONE, R.string.sftp_action_upload_folder);
                menu.add(Menu.NONE, MENU_SELECT_ALL, Menu.NONE, R.string.sftp_action_select_all);
                menu.add(Menu.NONE, MENU_SHOW_HIDDEN, Menu.NONE, R.string.sftp_action_show_hidden).setCheckable(true).setChecked(mShowHidden);
                menu.add(Menu.NONE, MENU_SORT, Menu.NONE, R.string.sftp_action_sort);
                menu.add(Menu.NONE, MENU_CLOSE, Menu.NONE, R.string.sftp_action_close);
                break;
            case MODE_EDITOR:
                if (!mEditor.isReadOnly()) menu.add(Menu.NONE, MENU_SAVE, Menu.NONE, R.string.sftp_action_save).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                if (!mEditor.isHex()) {
                    menu.add(Menu.NONE, MENU_FIND, Menu.NONE, R.string.sftp_action_find);
                    menu.add(Menu.NONE, MENU_FIND_NEXT, Menu.NONE, R.string.sftp_action_find_next);
                    menu.add(Menu.NONE, MENU_GO_TO_LINE, Menu.NONE, R.string.sftp_action_go_to_line);
                }
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
            case MENU_UPLOAD_FOLDER: startUploadFolder(); return true;
            case MENU_BOOKMARKS: showBookmarks(); return true;
            case MENU_TRANSFERS: showTransfers(); return true;
            case MENU_SELECT_ALL:
                for (Item selected : mItems) mSelection.add(selected.path);
                onSelectionChanged();
                return true;
            case MENU_SELECTION_DOWNLOAD: downloadItems(getSelectedItems()); return true;
            case MENU_SELECTION_DELETE: deleteItems(getSelectedItems()); return true;
            case MENU_SELECTION_PERMISSIONS: changePermissions(getSelectedItems()); return true;
            case MENU_SELECTION_CLEAR:
                mSelection.clear();
                onSelectionChanged();
                return true;
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
            case MENU_FIND: mEditor.showFindDialog(); return true;
            case MENU_FIND_NEXT: mEditor.findNext(); return true;
            case MENU_GO_TO_LINE: mEditor.showGoToLineDialog(); return true;
            case MENU_SYNTAX: mEditor.showSyntaxDialog(); return true;
            case MENU_RELOAD:
                if (mEditPath == null) return true;
                String path = mEditPath;
                int how = mEditor.isHex() ? OPEN_HEX : OPEN_TEXT;
                if (!mEditor.isDirty()) {
                    openFile(path, how);
                    return true;
                }
                new AlertDialog.Builder(this)
                    .setMessage(getString(R.string.sftp_unsaved_changes, SftpPaths.name(path)))
                    .setPositiveButton(R.string.sftp_action_discard, (d, which) -> openFile(path, how))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
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
                if (!mSelection.isEmpty()) {
                    mSelection.clear();
                    onSelectionChanged();
                } else if (mPath != null && !mPath.equals("/")) loadDirectory(SftpPaths.parent(mPath));
                else super.onBackPressed();
        }
    }

}
