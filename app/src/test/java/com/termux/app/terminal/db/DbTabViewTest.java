package com.termux.app.terminal.db;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

/** The database tab UI on an SQLite file: opening a tab, running statements, completion and editing rows. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28})
public class DbTabViewTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File mOldStore;
    private DbTabs mTabs;
    private int mChanges;
    private boolean mShown;

    @Before
    public void setUp() throws Exception {
        mOldStore = DbConnectionStore.sFile;
        DbConnectionStore.sFile = new File(folder.getRoot(), "db_connections.json");
    }

    @After
    public void tearDown() {
        DbConnectionStore.sFile = mOldStore;
        if (mTabs != null) for (DbTab tab : new java.util.ArrayList<>(mTabs.getTabs())) mTabs.close(tab);
    }

    /** Run the posted work of tabs until the condition holds. */
    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long end = System.currentTimeMillis() + 10000;
        while (!condition.getAsBoolean()) {
            Assert.assertTrue("timed out", System.currentTimeMillis() < end);
            Thread.sleep(10);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }

    @Test
    public void testTab() throws Exception {
        DbConnectionConfig config = new DbConnectionConfig();
        config.type = DbType.SQLITE;
        config.database = new File(folder.getRoot(), "test.db").getPath();
        DbConnectionStore.save(Collections.singletonList(config));

        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        View terminal = new View(activity);
        root.addView(terminal, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        activity.setContentView(root);
        mTabs = new DbTabs(activity, new DbTabs.Host() {
            @Override
            public void onDbTabsChanged() {
                mChanges++;
            }

            @Override
            public void onDbTabsShown() {
                mShown = true;
            }

            @Override
            public void onDbTabsHidden() {
                mShown = false;
            }

            @Override
            public void switchTab(boolean forward) {
            }

            @Override
            public void openLocalShell() {
            }
        }, terminal);

        // Opening a tab connects it.
        mTabs.open(config);
        Assert.assertTrue(mShown);
        DbTab tab = mTabs.getSelected();
        Assert.assertNotNull(tab);
        waitFor(() -> tab.state == DbTab.READY);
        Assert.assertTrue(tab.serverVersion, tab.serverVersion.startsWith("SQLite"));
        Assert.assertEquals("main", tab.defaultSchema);
        DbTabView view = mTabs.getView();
        Assert.assertNotNull(view);

        // Statements run one after the other, and the results of the last are shown.
        view.mEditor.setText("create table t (id integer primary key, name text);\n" +
            "insert into t (name) values ('alpha');\ninsert into t (name) values ('beta');\nselect * from t order by id;");
        view.run(true);
        waitFor(() -> tab.state == DbTab.READY && tab.results.size() == 4);
        Assert.assertNull(tab.results.get(1).error);
        Assert.assertEquals(1, tab.results.get(1).updateCount);
        QueryResult select = tab.results.get(3);
        Assert.assertEquals(2, select.rows.size());
        Assert.assertSame(select, view.getGrid().getResult());
        // The new table is in the schema tree.
        waitFor(() -> tab.state == DbTab.READY && tab.tables.containsKey("main"));
        Assert.assertEquals("t", tab.tables.get("main").get(0).name);

        // An error stops the statements after it.
        view.mEditor.setText("select * from nothing; select 1;");
        view.run(true);
        waitFor(() -> tab.state == DbTab.READY && !tab.results.isEmpty());
        Assert.assertEquals(1, tab.results.size());
        Assert.assertNotNull(tab.results.get(0).error);

        // Completion of tables, and of the columns of an alias.
        view.mEditor.requestFocus();
        view.mEditor.setText("select * from t x where x.");
        view.mEditor.setSelection(view.mEditor.length());
        view.mCompletion.show(true);
        waitFor(() -> tab.columns.containsKey(DbTab.key("main", "t")) && view.mCompletion.getItems().contains("name"));
        Assert.assertEquals(java.util.Arrays.asList("id", "name"), view.mCompletion.getItems());
        view.mCompletion.show(true);
        Assert.assertTrue(view.mCompletion.onKey(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_DOWN)));
        Assert.assertTrue(view.mCompletion.onKey(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER)));
        Assert.assertEquals("select * from t x where x.name", view.mEditor.getText().toString());

        // Editing rows: a change, a new row and a deleted row, applied together.
        DbObjects.Table table = tab.tables.get("main").get(0);
        DbDataEditor.open(view, table);
        waitFor(() -> tab.state == DbTab.READY && tab.dataEditor != null && view.getGrid().getResult() != null);
        DbDataEditor editor = tab.dataEditor;
        QueryResult rows = view.getGrid().getResult();
        Assert.assertEquals(2, rows.rows.size());
        editor.setValue(0, 1, "ALPHA");
        editor.addRow(rows.rows.get(1));
        editor.deleteRows(1);
        Assert.assertEquals(3, editor.getChangeCount());
        List<DbDataEditor.Change> changes = editor.getChanges(DbType.SQLITE);
        Assert.assertEquals("DELETE FROM \"main\".\"t\" WHERE \"id\" = 2", changes.get(0).preview);
        Assert.assertEquals("UPDATE \"main\".\"t\" SET \"name\" = 'ALPHA' WHERE \"id\" = 1", changes.get(1).preview);
        Assert.assertEquals("INSERT INTO \"main\".\"t\" (\"name\") VALUES ('beta')", changes.get(2).preview);
        editor.run(changes);
        waitFor(() -> tab.state == DbTab.READY);
        android.app.AlertDialog error = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        if (error != null && error.isShowing()) Assert.fail(String.valueOf(Shadows.shadowOf(error).getMessage()));
        waitFor(() -> tab.state == DbTab.READY && !editor.hasChanges() && view.getGrid().getResult() != rows);
        waitFor(() -> tab.state == DbTab.READY);
        QueryResult after = tab.getSession().execute("select id, name from t order by id", 0, 0);
        Assert.assertEquals(2, after.rows.size());
        Assert.assertEquals("ALPHA", after.rows.get(0)[1]);
        // Without AUTOINCREMENT, SQLite gives the deleted id again.
        Assert.assertEquals("2", after.rows.get(1)[0]);
        Assert.assertEquals("beta", after.rows.get(1)[1]);

        // Running a statement leaves editing.
        view.runStatements(Collections.singletonList("select count(*) from t"));
        Assert.assertNull(tab.dataEditor);
        waitFor(() -> tab.state == DbTab.READY && tab.results.size() == 1);
        Assert.assertEquals("2", tab.results.get(0).rows.get(0)[0]);

        // Closing the last tab hides the tabs.
        mTabs.close(tab);
        Assert.assertFalse(mShown);
        Assert.assertNull(mTabs.getSelected());
        Assert.assertTrue(mChanges > 0);
    }

}
