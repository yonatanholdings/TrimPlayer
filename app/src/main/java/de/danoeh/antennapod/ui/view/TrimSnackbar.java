package de.danoeh.antennapod.ui.view;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;

import de.danoeh.antennapod.R;

/**
 * TrimPlayer's branded snackbar: icon in a tinted badge + bold title +
 * optional supporting body, overlaid on the standard Material rounded
 * background. The default TextView is left in place (invisible) so the
 * SnackbarContentLayout still measures itself correctly.
 */
public abstract class TrimSnackbar {

    public static Snackbar make(View parent, String title, @Nullable String body,
                                @DrawableRes int iconRes, @ColorRes int badgeTintRes, int duration) {
        Snackbar snackbar = Snackbar.make(parent, " ", duration);
        Snackbar.SnackbarLayout snackbarLayout = (Snackbar.SnackbarLayout) snackbar.getView();
        TextView defaultText = snackbarLayout.findViewById(com.google.android.material.R.id.snackbar_text);
        if (defaultText != null) {
            defaultText.setVisibility(View.INVISIBLE);
        }
        View custom = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.snackbar_trim_analyze, snackbarLayout, false);
        ((TextView) custom.findViewById(R.id.snackTitle)).setText(title);
        TextView bodyView = custom.findViewById(R.id.snackBody);
        if (body == null || body.isEmpty()) {
            bodyView.setVisibility(View.GONE);
        } else {
            bodyView.setText(body);
        }
        ((ImageView) custom.findViewById(R.id.snackIcon)).setImageResource(iconRes);
        custom.findViewById(R.id.snackBadge).setBackgroundTintList(
                ContextCompat.getColorStateList(parent.getContext(), badgeTintRes));
        snackbarLayout.addView(custom, 0);
        return snackbar;
    }

    /**
     * Show over the expanded audio player. A plain {@code Snackbar.make} from a
     * view inside the player climbs to the activity's CoordinatorLayout and
     * renders behind the 8dp-elevated player sheet (main.xml), so it is never
     * seen — attach to {@code android.R.id.content} instead, which sits above
     * that CoordinatorLayout entirely.
     */
    public static void showOverPlayer(View viewInsidePlayer, String title, @Nullable String body,
                                      @DrawableRes int iconRes, @ColorRes int badgeTintRes, int duration) {
        View content = viewInsidePlayer.getRootView().findViewById(android.R.id.content);
        make(content != null ? content : viewInsidePlayer,
                title, body, iconRes, badgeTintRes, duration).show();
    }
}
