package com.odoo.addons.notes.widgets;

import android.app.LoaderManager.LoaderCallbacks;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.Loader;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBarActivity;
import android.util.SparseBooleanArray;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.odoo.R;
import com.odoo.addons.notes.Notes;
import com.odoo.addons.notes.models.NoteStage;
import com.odoo.core.orm.fields.OColumn;
import com.odoo.core.support.OUser;
import com.odoo.core.support.list.OCursorListAdapter;
import com.odoo.core.utils.OActionBarUtils;
import com.odoo.core.utils.OControls;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import odoo.controls.OControlHelper;


public class NotesWidgetConfigure extends ActionBarActivity implements
        LoaderCallbacks<Cursor> {

    private static final String PREFS_NAME = "com.odoo.widgetsWidgetProvider";

    List<String> mOptionsList = new ArrayList<String>();
    private static Context mContext = null;
    private OCursorListAdapter mAdapter = null;
    ListView mListView = null;
    private NoteStage note_stage;
    private ActionBar actionBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContext = this;

        setContentView(R.layout.widget_note_configure_layout);
        OActionBarUtils.setActionBar(this, false);
        actionBar = getSupportActionBar();
        actionBar.setTitle(getString(R.string.widget_configure));
        actionBar.setHomeButtonEnabled(true);
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setBackgroundDrawable(
                new ColorDrawable(R.color.odoo_primary));
        setResult(RESULT_CANCELED);
        note_stage = new NoteStage(this, null);
        mListView = (ListView) findViewById(android.R.id.list);
        mListView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        mAdapter = new OCursorListAdapter(this, null,
                android.R.layout.simple_list_item_multiple_choice) {
            @Override
            public void bindView(View view, Context context, Cursor cursor) {
                TextView txv = (TextView) view.findViewById(android.R.id.text1);
                int padd = (int) getResources().getDimension(
                        R.dimen.odoo_padding);
                txv.setPadding(padd, padd, padd, padd);
                txv.setTypeface(OControlHelper.lightFont());
                OControls.setText(view, android.R.id.text1,
                        cursor.getString(cursor.getColumnIndex("name")));
            }
        };
        if (OUser.current(this) == null) {
            Toast.makeText(this, getString(R.string.no_acct_found),
                    Toast.LENGTH_LONG).show();
            finish();
        } else {
            mListView.setAdapter(mAdapter);
            registerForContextMenu(mListView);
            getLoaderManager().initLoader(0, null, (LoaderCallbacks<Cursor>) this);
        }
    }

    static void savePref(Context context, int appWidgetId, String key,
                         Set<String> value) {
        mContext = context;
        SharedPreferences.Editor prefs = context.getSharedPreferences(
                PREFS_NAME, 0).edit();
        prefs.putStringSet(key + "_" + appWidgetId, value);
        prefs.commit();
    }

    public static List<Integer> getPref(Context context, int appWidgetId,
                                        String key) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        Set<String> selectedIdSet = prefs.getStringSet(key + "_" + appWidgetId,
                null);
        List<Integer> selectedIdList = new ArrayList<Integer>();
        for (String id : selectedIdSet)
            selectedIdList.add(Integer.parseInt(id));
        return selectedIdList;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_selection_done:
                SparseBooleanArray checkedItems = mListView
                        .getCheckedItemPositions();
                Set<String> selected_ids = new HashSet<String>();
                List<String> selectedIds = new ArrayList<String>();
                Cursor c = mAdapter.getCursor();
                for (int i = 0; i < checkedItems.size(); i++) {
                    int position = checkedItems.keyAt(i);
                    c.moveToPosition(position);
                    selectedIds.add(c.getString(c.getColumnIndex(OColumn.ROW_ID)));
                }
                selected_ids.addAll(selectedIds);
                Intent intent = getIntent();
                Bundle extras = intent.getExtras();
                int mAppWidgetId = 0;
                if (extras != null) {
                    mAppWidgetId = extras.getInt(
                            AppWidgetManager.EXTRA_APPWIDGET_ID,
                            AppWidgetManager.INVALID_APPWIDGET_ID);
                }
                savePref(this, mAppWidgetId, Notes.KEY_NOTE_FILTER, selected_ids);
                AppWidgetManager appWidgetManager = AppWidgetManager
                        .getInstance(this);
                NotesWidget.updateNoteWidget(this, appWidgetManager,
                        new int[]{mAppWidgetId});
                Intent resultValue = new Intent();
                resultValue.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                        mAppWidgetId);
                setResult(RESULT_OK, resultValue);
                finish();
            case R.id.menu_select_all:
                for (int i = 0; i < mListView.getCount(); i++) {
                    mListView.setItemChecked(i, true);
                }

        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_widget_configure, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        return new CursorLoader(this, note_stage.uri(), new String[]{"name",
                "id"}, null, null, "sequence");
    }

    @Override
    public void onLoadFinished(Loader<Cursor> arg0, Cursor arg1) {
        mAdapter.changeCursor(arg1);
        if (mAdapter.getCount() < 1) {
            Toast.makeText(mContext, R.string.label_no_notes_found, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    public void onLoaderReset(Loader<Cursor> arg0) {
        mAdapter.changeCursor(null);
    }

}