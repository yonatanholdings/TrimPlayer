package de.danoeh.antennapod.ui.statistics.feed;

import android.app.Dialog;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.bumptech.glide.Glide;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.ui.appstartintent.MainActivityStarter;
import de.danoeh.antennapod.ui.statistics.R;
import de.danoeh.antennapod.ui.statistics.editorial.EditorialTheme;
import de.danoeh.antennapod.ui.statistics.editorial.SparklineView;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class FeedDossierDialogFragment extends DialogFragment {
    private static final String TAG = "FeedDossierDialog";
    private static final String ARG_FEED_ID = "feedId";
    private Disposable disposable;

    public static FeedDossierDialogFragment newInstance(long feedId) {
        FeedDossierDialogFragment f = new FeedDossierDialogFragment();
        Bundle args = new Bundle();
        args.putLong(ARG_FEED_ID, feedId);
        f.setArguments(args);
        return f;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_feed_dossier, null, false);

        // Filed date
        TextView filedDate = view.findViewById(R.id.filed_date);
        filedDate.setText("FILED " + new SimpleDateFormat("MMM d", Locale.getDefault())
                .format(new Date()).toUpperCase(Locale.getDefault()));

        // Bind typed numerals
        TextView statListened = view.findViewById(R.id.stat_listened);
        TextView statSaved    = view.findViewById(R.id.stat_saved);
        TextView statEpisodes = view.findViewById(R.id.stat_episodes);
        statListened.setTypeface(EditorialTheme.getSerif(requireContext()));
        statSaved.setTypeface(EditorialTheme.getSerif(requireContext()));
        statEpisodes.setTypeface(EditorialTheme.getSerif(requireContext()));

        SparklineView sparkline = view.findViewById(R.id.dossier_sparkline);
        ImageView cover = view.findViewById(R.id.dossier_cover);
        TextView title = view.findViewById(R.id.dossier_title);
        TextView subscribed = view.findViewById(R.id.subscribed_since);

        long feedId = getArguments() != null ? getArguments().getLong(ARG_FEED_ID) : -1;

        disposable = Observable.fromCallable(() -> DBReader.getFeedDetail(feedId))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(detail -> {
                    title.setText(detail.title);

                    if (detail.subscribedMs > 0) {
                        String since = new SimpleDateFormat("MMM yyyy", Locale.getDefault())
                                .format(new Date(detail.subscribedMs));
                        subscribed.setText("SUBSCRIBED SINCE " + since.toUpperCase(Locale.getDefault()));
                    }

                    if (detail.imageUrl != null) {
                        Glide.with(requireContext()).load(detail.imageUrl).into(cover);
                    } else {
                        cover.setBackgroundColor(detail.color);
                    }

                    statListened.setText(String.format(Locale.getDefault(), "%.1fh", detail.hrsListened));
                    statSaved.setText(String.format(Locale.getDefault(), "%.1fh", detail.hrsSaved));
                    statEpisodes.setText(String.format(Locale.getDefault(),
                            "%d/%d", detail.episodesPlayed, detail.episodesTotal));

                    sparkline.setData(detail.weekly);
                }, e -> Log.e(TAG, Log.getStackTraceString(e)));

        Button btnOpenFeed = view.findViewById(R.id.btn_open_feed);
        btnOpenFeed.setOnClickListener(v -> {
            new MainActivityStarter(requireContext()).withOpenFeed(feedId).start();
            dismissAllowingStateLoss();
        });

        Button btnDismiss = view.findViewById(R.id.btn_dismiss);
        btnDismiss.setOnClickListener(v -> dismissAllowingStateLoss());

        return new MaterialAlertDialogBuilder(requireContext())
                .setView(view)
                .create();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (disposable != null) disposable.dispose();
    }
}
