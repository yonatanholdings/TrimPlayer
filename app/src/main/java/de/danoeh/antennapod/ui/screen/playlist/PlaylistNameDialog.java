package de.danoeh.antennapod.ui.screen.playlist;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.util.function.Consumer;

import de.danoeh.antennapod.R;

/**
 * Shared name-input dialog for creating/renaming playlists: an outlined text
 * field plus one-tap suggestion chips for the common listening contexts
 * (Running, Driving, ...). Chips fill the field AND submit, so creating a
 * standard playlist is a single tap; typing stays available for custom names.
 */
public final class PlaylistNameDialog {

    private static final int[] SUGGESTIONS = {
            R.string.playlist_suggestion_running,
            R.string.playlist_suggestion_driving,
            R.string.playlist_suggestion_workout,
            R.string.playlist_suggestion_commute,
            R.string.playlist_suggestion_bedtime,
    };

    private PlaylistNameDialog() {
    }

    /**
     * @param titleRes  dialog title (create vs rename)
     * @param prefill   current name when renaming, null when creating
     * @param onConfirm called with the trimmed, non-empty name
     */
    public static void show(@NonNull Context context, int titleRes, @Nullable String prefill,
                            @NonNull Consumer<String> onConfirm) {
        View content = LayoutInflater.from(context).inflate(R.layout.playlist_name_dialog, null);
        TextInputEditText input = content.findViewById(R.id.name_input);
        ChipGroup chips = content.findViewById(R.id.suggestion_chips);

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(titleRes)
                .setView(content)
                .setPositiveButton(R.string.confirm_label, (d, which) -> {
                    String name = input.getText() == null ? "" : input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        onConfirm.accept(name);
                    }
                })
                .setNegativeButton(R.string.cancel_label, null)
                .create();

        if (prefill != null) {
            input.setText(prefill);
            input.setSelection(prefill.length());
            chips.setVisibility(View.GONE); // renaming: suggestions are just noise
        } else {
            for (int labelRes : SUGGESTIONS) {
                Chip chip = new Chip(context);
                chip.setText(labelRes);
                chip.setOnClickListener(v -> {
                    onConfirm.accept(chip.getText().toString());
                    dialog.dismiss();
                });
                chips.addView(chip);
            }
        }

        // Enter on the keyboard confirms, same as the positive button.
        input.setOnEditorActionListener((v, actionId, event) -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            return true;
        });
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        dialog.show();
        input.requestFocus();
    }
}
