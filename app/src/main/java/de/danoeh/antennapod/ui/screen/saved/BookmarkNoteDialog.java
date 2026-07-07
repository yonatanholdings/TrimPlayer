package de.danoeh.antennapod.ui.screen.saved;

import android.content.Context;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.Bookmark;
import de.danoeh.antennapod.storage.database.DBWriter;

/**
 * Note editor for a bookmark, shared by the player's bookmark sheet and the
 * Saved screen's bookmarks tab. Saving writes through DBWriter, which posts
 * {@code BookmarksChangedEvent} so every open list refreshes.
 */
public abstract class BookmarkNoteDialog {

    public static void show(Context context, Bookmark bookmark) {
        EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHint(R.string.bookmark_note_hint);
        input.setText(bookmark.getNote());
        input.setSelection(bookmark.getNote().length());
        FrameLayout wrapper = new FrameLayout(context);
        int padding = (int) (20 * context.getResources().getDisplayMetrics().density);
        wrapper.setPadding(padding, 0, padding, 0);
        wrapper.addView(input);
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.bookmark_edit_note)
                .setView(wrapper)
                .setPositiveButton(R.string.confirm_label, (dialog, which) ->
                        DBWriter.updateBookmarkNote(bookmark.getId(), bookmark.getFeedItemId(),
                                input.getText().toString().trim()))
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }
}
