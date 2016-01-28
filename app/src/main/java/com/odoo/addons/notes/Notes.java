/**
 * Odoo, Open Source Management Solution
 * Copyright (C) 2012-today Odoo SA (<http:www.odoo.com>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http:www.gnu.org/licenses/>
 *
 * Created on 25/2/15 6:30 PM
 */
package com.odoo.addons.notes;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.widget.SwipeRefreshLayout;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Toast;

import com.odoo.R;
import com.odoo.addons.notes.dialogs.NoteColorDialog;
import com.odoo.addons.notes.dialogs.NoteStagesDialog;
import com.odoo.addons.notes.models.NoteNote;
import com.odoo.addons.notes.utils.NoteUtil;
import com.odoo.addons.notes.widgets.NotesWidget;
import com.odoo.addons.notes.widgets.WidgetHelper;
import com.odoo.base.addons.config.BaseConfigSettings;
import com.odoo.core.orm.ODataRow;
import com.odoo.core.orm.OValues;
import com.odoo.core.orm.fields.OColumn;
import com.odoo.core.support.addons.fragment.BaseFragment;
import com.odoo.core.support.addons.fragment.IOnSearchViewChangeListener;
import com.odoo.core.support.addons.fragment.ISyncStatusObserverListener;
import com.odoo.core.support.drawer.ODrawerItem;
import com.odoo.core.support.list.IOnItemClickListener;
import com.odoo.core.support.list.OCursorListAdapter;
import com.odoo.core.utils.IntentUtils;
import com.odoo.core.utils.OAlert;
import com.odoo.core.utils.OControls;
import com.odoo.core.utils.OCursorUtils;
import com.odoo.core.utils.ODateUtils;
import com.odoo.core.utils.StringUtils;
import com.odoo.core.utils.sys.IOnBackPressListener;
import com.odoo.widgets.bottomsheet.BottomSheet;
import com.odoo.widgets.bottomsheet.BottomSheetListeners;
import com.odoo.widgets.snackbar.SnackBar;
import com.odoo.widgets.snackbar.SnackbarBuilder;
import com.odoo.widgets.snackbar.listeners.ActionClickListener;
import com.odoo.widgets.snackbar.listeners.EventListener;

import java.util.ArrayList;
import java.util.List;

public class Notes extends BaseFragment implements ISyncStatusObserverListener,
        SwipeRefreshLayout.OnRefreshListener, LoaderManager.LoaderCallbacks<Cursor>,
        OCursorListAdapter.OnViewBindListener, IOnSearchViewChangeListener,
        View.OnClickListener, IOnItemClickListener, BottomSheetListeners.OnSheetItemClickListener,
        BottomSheetListeners.OnSheetActionClickListener, IOnBackPressListener,
        BottomSheetListeners.OnSheetMenuCreateListener, EventListener {
    public static final String TAG = Notes.class.getSimpleName();
    public static final String KEY_STAGE_ID = "stage_id";
    public static final String KEY_NOTE_ID = "note_id";
    public static final String KEY_NOTE_FILTER = "note_filter";
    private Type mCurrentKey = Type.Notes;
    private int mStageId = 0;
    private OCursorListAdapter mAdapter = null;
    private ListView mList = null;
    private View mView;
    private String mFilter = null;
    private BottomSheet mSheet;
    private Bundle extra;

    public enum Type {
        Notes, Archive, Reminders, Deleted, TagFilter
    }

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.notes_listview, container, false);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mView = view;
        extra = getArguments();
        if (extra != null) {
            if (extra.containsKey(KEY_STAGE_ID)) {
                mStageId = extra.getInt(KEY_STAGE_ID);
                parent().setHasActionBarSpinner(true);
            }
            if (extra.containsKey(WidgetHelper.EXTRA_WIDGET_ITEM_KEY)) {
                Bundle data = new Bundle();
                data.putInt(KEY_STAGE_ID, mStageId);
                if (extra.getString(WidgetHelper.EXTRA_WIDGET_ITEM_KEY).equals(NotesWidget.KEY_NOTE_COMPOSE))
                    quickCreateNote(data);
                if (extra.getString(WidgetHelper.EXTRA_WIDGET_ITEM_KEY).equals(NotesWidget.KEY_NOTE_VOICE_TO_TEXT)) {
                    data.putString("type", "speechToText");
                    quickCreateNote(data);
                }

            }
        }
        setHasOptionsMenu(true);
        setHasSwipeRefreshView(view, R.id.swipe_container, this);
        setHasSyncStatusObserver(TAG, this, db());
        initAdapter();

        if (BaseConfigSettings.padInstalled(getActivity())) {
            OAlert.showWarning(getActivity(), "Pad installed. Unable to work offline.");
        }
    }

    private void initAdapter() {
        if (getActivity() != null) {
            mCurrentKey = Type.valueOf(getArguments().getString(KEY_NOTE_FILTER));
            if (mCurrentKey == Type.Deleted)
                mView.findViewById(R.id.fabButton).setVisibility(View.GONE);
            mList = (ListView) mView.findViewById(R.id.gridView);
            setHasFloatingButton(mView, R.id.fabButton, mList, this);
            //setHeaderView();
            mAdapter = new OCursorListAdapter(getActivity(), null,
                    R.layout.note_custom_view_note);
            mAdapter.setOnViewBindListener(this);
            mList.setAdapter(mAdapter);
            mAdapter.handleItemClickListener(mList, this);
            getLoaderManager().initLoader(0, null, this);
        }
    }

    @Override
    public void onItemDoubleClick(View view, int position) {
        Cursor cr = (Cursor) mAdapter.getItem(position);
        Bundle bundle = new Bundle();
        bundle.putInt(KEY_NOTE_ID, cr.getInt(cr.getColumnIndex(OColumn.ROW_ID)));
        Intent intent = new Intent(getActivity(), NoteDetail.class);
        intent.putExtras(bundle);
        startActivity(intent);
    }

    @Override
    public void onItemClick(View view, int position) {
        Cursor cr = (Cursor) mAdapter.getItem(position);
        showSheet(cr);
    }

    private void showSheet(Cursor cr) {
        ODataRow data = OCursorUtils.toDatarow(cr);
        if (mSheet != null) {
            mSheet.dismiss();
        }
        BottomSheet.Builder builder = new BottomSheet.Builder(getActivity());
        builder.listener(this);
        builder.setIconColor(_c(R.color.body_text_2));
        builder.setTextColor(_c(R.color.body_text_1));
        builder.setData(cr);
        builder.actionListener(this);
        builder.setActionIcon(R.drawable.ic_action_edit);
        builder.title(data.getString("short_memo"));
        builder.setOnSheetMenuCreateListener(this);
        builder.menu(R.menu.menu_note_sheet);
        mSheet = builder.create();
        mSheet.show();
    }

    @Override
    public void onSheetMenuCreate(Menu menu, Object o) {
        if (mCurrentKey == Type.Deleted) {
            menu.findItem(R.id.menu_note_delete).setVisible(false);
        }
        if (mCurrentKey == Type.Archive || mCurrentKey == Type.Deleted) {
            menu.findItem(R.id.menu_note_archive).setIcon(R.drawable.ic_action_unarchive);
            menu.findItem(R.id.menu_note_archive).setTitle(R.string.label_unarchive);
        }
    }

    /**
     * Bottom Sheet click listener
     *
     * @param bottomSheet
     * @param menuItem
     * @param obj
     */
    @Override
    public void onItemClick(BottomSheet bottomSheet, MenuItem menuItem, Object obj) {
        ODataRow row = OCursorUtils.toDatarow((Cursor) obj);
        int row_id = row.getInt(OColumn.ROW_ID);
        switch (menuItem.getItemId()) {
            case R.id.menu_note_choose_color:
                chooseColor(row_id, row);
                break;
            case R.id.menu_note_archive:
                String open = (row.getString("open").equals("false")) ? "true"
                        : "false";
                showArchiveUndoBar(row_id, open, row.getString("trashed").equals("1"));
                break;
            case R.id.menu_note_delete:
                int trashed = (row.getInt("trashed") == 1) ? 0 : 1;
                showTrashUndoBar(row_id, trashed);
                break;
        }
        bottomSheet.dismiss();
    }

    /**
     * Bottom sheet action listener
     *
     * @param bottomSheet
     * @param obj
     */
    @Override
    public void onSheetActionClick(BottomSheet bottomSheet, Object obj) {
        mSheet.dismiss();
        Cursor cr = (Cursor) obj;
        Bundle bundle = new Bundle();
        bundle.putInt(KEY_NOTE_ID, cr.getInt(cr.getColumnIndex(OColumn.ROW_ID)));
        IntentUtils.startActivity(getActivity(), NoteDetail.class, bundle);
    }

    @Override
    public void onClick(View v) {
        Bundle data = new Bundle();
        data.putInt(KEY_STAGE_ID, mStageId);
        switch (v.getId()) {
            case R.id.fabButton:
                showFabSheet();
                break;
        }
    }

    private void showFabSheet() {
        if (mSheet != null) {
            mSheet.dismiss();
        }
        BottomSheet.Builder builder = new BottomSheet.Builder(getActivity());
        builder.listener(fabListeners);
        builder.setIconColor(_c(R.color.body_text_2));
        builder.setTextColor(_c(R.color.body_text_1));
        builder.title("New Note");
        builder.menu(R.menu.menu_notes_sheet);
        mSheet = builder.create();
        mSheet.show();
    }

    BottomSheetListeners.OnSheetItemClickListener fabListeners =
            new BottomSheetListeners.OnSheetItemClickListener() {

                @Override
                public void onItemClick(BottomSheet bottomSheet, MenuItem menuItem, Object o) {
                    if (mSheet != null) {
                        mSheet.dismiss();
                    }
                    Bundle data = new Bundle();
                    data.putInt(KEY_STAGE_ID, mStageId);
                    if (extra.containsKey("tag_id")) {
                        data.putInt("tag_id", extra.getInt("tag_id"));
                    }
                    switch (menuItem.getItemId()) {
                        case R.id.menu_note_create:
                            quickCreateNote(data);
                            break;
                        case R.id.menu_note_new_with_attachment:
                            CheckForDocumentModule checkForDocumentModule = new CheckForDocumentModule();
                            checkForDocumentModule.execute();
                            break;
                        case R.id.menu_note_speech_to_text:
                            data.putString("type", "speechToText");
                            quickCreateNote(data);
                            break;
                    }
                }
            };

    private class CheckForDocumentModule extends AsyncTask<Void, Void, Boolean> {

        ProgressDialog progressDialog;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            progressDialog = new ProgressDialog(getActivity());
            progressDialog.setTitle(R.string.title_please_wait);
            progressDialog.setMessage(_s(R.string.title_working));
            progressDialog.setCancelable(false);
            progressDialog.show();
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            return db().isInstalledOnServer("document", false);
        }

        @Override
        protected void onPostExecute(Boolean result) {
            super.onPostExecute(result);
            progressDialog.dismiss();
            if (result) {
                Bundle data = new Bundle();
                data.putInt(KEY_STAGE_ID, mStageId);
                if (extra.containsKey("tag_id")) {
                    data.putInt("tag_id", extra.getInt("tag_id"));
                }
                data.putBoolean(NoteDetail.REQUEST_FILE_ATTACHMENT, true);
                quickCreateNote(data);
            } else {
                OAlert.showWarning(getActivity(),
                        _s(R.string.warning_no_document_module_installed));
            }
        }
    }

    private void quickCreateNote(Bundle data) {
        IntentUtils.startActivity(getActivity(), NoteDetail.class, data);
    }

    @Override
    public List<ODrawerItem> drawerMenus(Context context) {
        return null;
    }

    @Override
    public Class<NoteNote> database() {
        return NoteNote.class;
    }

    @Override
    public void onStatusChange(Boolean refreshing) {
        if (getActivity() != null)
            getLoaderManager().restartLoader(0, null, this);
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle data) {
        String selection = "";
        List<String> args = new ArrayList<>();
        Uri uri = db().uri();
        switch (mCurrentKey) {
            case Notes:
                args.add(mStageId + "");
                selection += "stage_id = ? and open = ? and trashed = ?";
                args.add("true");
                args.add("0");
                break;
            case Reminders:
                selection += " reminder != ?";
                args.add("0");
                break;
            case Archive:
                selection += " open = ? and trashed = ?";
                args.add("false");
                args.add("0");
                break;
            case Deleted:
                selection += " trashed = ?";
                args.add("1");
                break;
            case TagFilter:
                uri = ((NoteNote) db()).filterTag();
                args.add(extra.getInt("tag_id") + "");
                break;
        }
        if (mFilter != null) {
            selection += " and name like ?";
            args.add("%" + mFilter + "%");
        }
        String[] arguments = args.toArray(new String[args.size()]);
        return new CursorLoader(getActivity(), uri, null, selection,
                arguments, " sequence");
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, final Cursor data) {
        mAdapter.changeCursor(data);
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (data.getCount() > 0) {
                    OControls.setGone(mView, R.id.loadingProgress);
                    OControls.setVisible(mView, R.id.swipe_container);
                    OControls.setGone(mView, R.id.notes_no_items);
                    setHasSwipeRefreshView(mView, R.id.swipe_container, Notes.this);
                } else {
                    OControls.setGone(mView, R.id.loadingProgress);
                    OControls.setGone(mView, R.id.swipe_container);
                    OControls.setVisible(mView, R.id.notes_no_items);
                    setHasSwipeRefreshView(mView, R.id.notes_no_items, Notes.this);
                    switch (mCurrentKey) {
                        case Notes:
                            OControls.setText(mView, R.id.title,
                                    R.string.label_no_notes_found);
                            OControls.setImage(mView, R.id.icon,
                                    R.drawable.ic_action_notes);
                            break;
                        case Archive:
                            OControls.setText(mView, R.id.title,
                                    getString(R.string.label_archived_note_here));
                            OControls.setImage(mView, R.id.icon,
                                    R.drawable.ic_action_archive);
                            break;
                        case Reminders:
                            OControls.setText(mView, R.id.title,
                                    getString(R.string.label_upcoming_reminder_note));
                            OControls.setImage(mView, R.id.icon,
                                    R.drawable.ic_action_reminder);
                            break;
                        case Deleted:
                            OControls.setText(mView, R.id.title,
                                    R.string.label_empty_trash);
                            OControls.setImage(mView, R.id.icon,
                                    R.drawable.ic_action_trash);
                            break;
                        case TagFilter:
                            OControls.setText(mView, R.id.title,
                                    R.string.label_empty_tag_list);
                            OControls.setImage(mView, R.id.icon,
                                    R.drawable.ic_action_label);
                            break;
                    }
                }
            }
        }, 500);

    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        mAdapter.changeCursor(null);
    }

    @Override
    public void onRefresh() {
        if (inNetwork()) {
            parent().sync().requestSync(NoteNote.AUTHORITY);
            setSwipeRefreshing(true);
        } else {
            hideRefreshingProgress();
            Toast.makeText(getActivity(), _s(R.string.toast_network_required), Toast.LENGTH_LONG)
                    .show();
        }
    }

    @Override
    public void onViewBind(View view, Cursor cursor, ODataRow row) {
        int color_number = row.getInt("color");
        view.findViewById(R.id.note_bg_color).setBackgroundColor(
                NoteUtil.getBackgroundColor(color_number));
        OControls.setText(view, R.id.note_memo,
                StringUtils.htmlToString(row.getString("short_memo")));
        OControls.setTextColor(view, R.id.note_memo, NoteUtil.getTextColor(color_number));
        OControls.setTextColor(view, R.id.note_reminder_status, NoteUtil.getTextColor(color_number));
        ImageView attachment = (ImageView) view.findViewById(R.id.noteHasAttachment);
        attachment.setColorFilter(NoteUtil.getTextColor(color_number));
        boolean showContainer = true;
        if (((NoteNote) db()).hasAttachment(row.getInt(OColumn.ROW_ID))) {
            attachment.setVisibility(View.VISIBLE);
        } else {
            showContainer = false;
            attachment.setVisibility(View.GONE);
        }
        ImageView reminderClock = (ImageView) view.findViewById(R.id.reminderClock);
        reminderClock.setColorFilter(NoteUtil.getTextColor(color_number));
        if (!row.getString("reminder").equals("0")) {
            view.findViewById(R.id.reminderClock).setVisibility(View.VISIBLE);
            OControls.setText(view, R.id.note_reminder_status, row.getString("reminder"));
        } else {
            if (!showContainer)
                showContainer = false;
            view.findViewById(R.id.reminderClock).setVisibility(View.GONE);
            OControls.setText(view, R.id.note_reminder_status, "");
        }
        view.findViewById(R.id.note_info_container).setVisibility((!showContainer) ? View.GONE : View.VISIBLE);
        bindRowControls(view, row);
    }

    private void bindRowControls(final View view, final ODataRow row) {
        ImageView imgMove = (ImageView) view.findViewById(R.id.note_move);
        if (mCurrentKey != Type.Notes)
            imgMove.setVisibility(View.GONE);
        imgMove.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                moveTo(row.getInt(OColumn.ROW_ID), row);
            }
        });
    }

    private void chooseColor(final int row_id, ODataRow row) {
        int color = row.getInt("color");
        String selected_color = NoteUtil
                .getBackgroundColors()[color];
        NoteUtil.colorDialog(getActivity(), selected_color,
                new NoteColorDialog.OnColorSelectListener() {
                    @Override
                    public void colorSelected(
                            ODataRow color_data) {
                        int index = color_data
                                .getInt("index");
                        OValues values = new OValues();
                        values.put("color", index);
                        values.put("trashed", 0);
                        values.put("is_dirty", true);
                        db().update(row_id, values);
                        restartLoader();
                    }
                }).show();
    }

    private void showArchiveUndoBar(final int row_id, final String open, final boolean fromTrash) {
        OValues values = new OValues();
        values.put("open", open);
        values.put("trashed", 0);
        db().update(row_id, values);
        restartLoader();
        String toast = (open.equals("true")) ? _s(R.string.snack_note_unarchived) :
                _s(R.string.snack_note_archived);
        if (fromTrash) {
            toast = _s(R.string.snack_note_restored);
        }
        SnackBar.get(getActivity())
                .text(toast)
                .duration(SnackbarBuilder.SnackbarDuration.LENGTH_LONG)
                .actionColor(_c(R.color.theme_secondary_light))
                .withAction(R.string.undo,
                        new ActionClickListener() {
                            @Override
                            public void onActionClicked(SnackbarBuilder snackbarBuilder) {
                                OValues value = new OValues();
                                if (!fromTrash) {
                                    value.put("open", (open.equals("true")) ? "false" : "true");
                                }
                                value.put("trashed", (fromTrash) ? 1 : 0);
                                db().update(row_id, value);
                            }
                        })
                .withEventListener(this).show();
    }

    private void showTrashUndoBar(final int row_id, final int trashed) {
        final OValues values = new OValues();
        values.put("trashed", trashed);
        values.put("_is_dirty", false);
        values.put("trashed_date", ODateUtils.getUTCDate());
        if (mCurrentKey == Type.Deleted) {
            values.put("open", "false");
        }
        db().update(row_id, values);
        restartLoader();
        SnackBar.get(getActivity())
                .text(R.string.snack_note_moved_to_recycle_bin)
                .duration(SnackbarBuilder.SnackbarDuration.LENGTH_LONG)
                .actionColor(_c(R.color.theme_secondary_light))
                .withAction(R.string.undo,
                        new ActionClickListener() {
                            @Override
                            public void onActionClicked(SnackbarBuilder snackbarBuilder) {
                                OValues value = new OValues();
                                value.put("trashed", 0);
                                value.put("_is_dirty", false);
                                db().update(row_id, value);
                            }
                        })
                
                .withEventListener(this).show();
    }

    private void moveTo(final int row_id, ODataRow row) {
        NoteUtil.noteStages(getActivity(), row.getInt("stage_id"),
                new NoteStagesDialog.OnStageSelectListener() {
                    @Override
                    public void stageSelected(ODataRow row) {
                        int stage_id = row
                                .getInt(OColumn.ROW_ID);
                        OValues values = new OValues();
                        values.put("stage_id", stage_id);
                        values.put("is_dirty", true);
                        db().update(row_id, values);
                        restartLoader();
                        String toast = String.format(_s(R.string.snack_note_moved_to),
                                row.getString("name"));
                        Toast.makeText(getActivity(), toast
                                , Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void restartLoader() {
        getLoaderManager().restartLoader(0, null, this);
    }


    @Override
    protected void onNavSpinnerDestroy() {
        if (mStageId == 0) {
            super.onNavSpinnerDestroy();
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.clear();
        inflater.inflate(R.menu.menu_notes, menu);
        setHasSearchView(this, menu, R.id.menu_note_search);
    }

    @Override
    public boolean onSearchViewTextChange(String newFilter) {
        mFilter = newFilter;
        getLoaderManager().restartLoader(0, null, this);
        return true;
    }

    @Override
    public void onSearchViewClose() {
        // nothing to do
    }

    @Override
    public boolean onBackPressed() {
        if (mSheet != null && mSheet.isShowing()) {
            mSheet.dismiss();
            return false;
        }
        return true;
    }

    // Snack bar listeners
    @Override
    public void onShow(int i) {
        hideFab();
    }

    @Override
    public void onDismiss(int i) {
        showFab();
    }
}
