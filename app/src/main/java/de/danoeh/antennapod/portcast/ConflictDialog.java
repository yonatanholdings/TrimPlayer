package de.danoeh.antennapod.portcast;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

import de.danoeh.antennapod.R;

/**
 * Wraps the grouped {@link ConflictAdapter} in a Material dialog. One call
 * site from both {@code ImportExportPreferencesFragment} (the SAF-picker
 * import path) and {@code PortcastImportActivity} (the tap-to-import
 * path), so a user reviewing import conflicts sees the same UI regardless
 * of how the file got into the app.
 */
public final class ConflictDialog {

    private ConflictDialog() {
    }

    /**
     * @param sourceLabel short name of the source ("PortCast",
     *                    "PodcastAddict") — labels the "use {source}"
     *                    switch in section headers.
     * @param onConfirm   invoked when the user taps the positive button.
     *                    By that point each row's {@link ConflictRow#useIncoming}
     *                    holds the user's final choice.
     */
    public static void show(Context context,
                            List<ConflictRow> rows,
                            String sourceLabel,
                            String title,
                            Runnable onConfirm) {
        View dialogView = LayoutInflater.from(context)
                .inflate(R.layout.dialog_podcast_addict_conflicts, null);

        TextView summaryView = dialogView.findViewById(R.id.conflictSummary);
        RecyclerView recyclerView = dialogView.findViewById(R.id.conflictList);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));

        ConflictAdapter adapter = new ConflictAdapter(context, rows, sourceLabel, summaryView);
        recyclerView.setAdapter(adapter);

        ChipGroup chips = dialogView.findViewById(R.id.groupByChips);
        chips.setOnCheckedStateChangeListener((group, checkedIds) -> {
            boolean byDate = checkedIds.contains(R.id.chipByDate);
            adapter.setGroupMode(byDate);
        });

        new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setView(dialogView)
                .setNegativeButton(R.string.cancel_label, null)
                .setPositiveButton(R.string.confirm_label, (d, w) -> onConfirm.run())
                .show();
    }
}
