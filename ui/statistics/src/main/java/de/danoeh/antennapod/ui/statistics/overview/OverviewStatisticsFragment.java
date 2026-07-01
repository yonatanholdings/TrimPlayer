package de.danoeh.antennapod.ui.statistics.overview;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.bumptech.glide.Glide;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.ui.statistics.DemoStats;
import de.danoeh.antennapod.ui.statistics.R;
import de.danoeh.antennapod.ui.statistics.StatisticsViewModel;
import de.danoeh.antennapod.ui.statistics.editorial.EditorialTheme;
import de.danoeh.antennapod.ui.statistics.editorial.HeatmapView;
import de.danoeh.antennapod.ui.statistics.editorial.SparklineView;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class OverviewStatisticsFragment extends Fragment {

    private TextView heroHours;
    private TextView heroMinutes;
    private TextView hangTrimmed;
    private TextView hangFinished;
    private TextView hangStreak;
    private SparklineView sparkline;
    private HeatmapView heatmap;
    private TextView heatmapDetail;
    private View playedSection;
    private LinearLayout playedList;
    private TextView playedEmpty;
    /** Last bound editorial stats — needed by heatmap tap to look up per-cell ms. */
    private DBReader.EditorialStats currentStats;
    /** In-flight per-day episode query; cancelled when a new cell is tapped. */
    private Disposable playedDisposable;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_overview_statistics, container, false);

        heroHours = root.findViewById(R.id.hero_hours);
        heroMinutes = root.findViewById(R.id.hero_minutes);
        hangTrimmed = root.findViewById(R.id.hang_trimmed_val);
        hangFinished = root.findViewById(R.id.hang_finished_val);
        hangStreak = root.findViewById(R.id.hang_streak_val);
        sparkline = root.findViewById(R.id.hero_sparkline);
        heatmap = root.findViewById(R.id.heatmap);
        heatmapDetail = root.findViewById(R.id.heatmap_detail);
        playedSection = root.findViewById(R.id.played_episodes_section);
        playedList = root.findViewById(R.id.played_episodes_list);
        playedEmpty = root.findViewById(R.id.played_episodes_empty);

        heatmap.setOnCellClickListener(this::onHeatmapCellTap);

        heroHours.setTypeface(EditorialTheme.getSerif(requireContext()));
        hangTrimmed.setTypeface(EditorialTheme.getSerif(requireContext()));
        hangFinished.setTypeface(EditorialTheme.getSerif(requireContext()));
        hangStreak.setTypeface(EditorialTheme.getSerif(requireContext()));

        new ViewModelProvider(requireParentFragment())
                .get(StatisticsViewModel.class)
                .editorial()
                .observe(getViewLifecycleOwner(), s -> { if (s != null) bind(s); });

        return root;
    }

    /** Heatmap cell tap → "Mar 5 · 2h 14m" (or "Mar 5 · no listening"). Date computed
     *  from the cached `heatmapStartMs` so no DB lookup is needed. Also loads the
     *  list of episodes played that day into the section below. */
    private void onHeatmapCellTap(int weekIdx, int dayIdx) {
        if (currentStats == null) {
            return;
        }
        long dayMs = currentStats.heatmapStartMs + (weekIdx * 7L + dayIdx) * 86_400_000L;
        java.text.SimpleDateFormat dateFmt = new java.text.SimpleDateFormat("MMM d", Locale.getDefault());
        String date = dateFmt.format(new Date(dayMs));
        long listenedMs = currentStats.heatmapMs[weekIdx][dayIdx];
        if (listenedMs <= 0) {
            heatmapDetail.setText(date + " · no listening");
        } else {
            long minutes = listenedMs / 60_000L;
            long hours = minutes / 60;
            long mins = minutes % 60;
            String hm = hours > 0 ? hours + "h " + mins + "m" : mins + "m";
            heatmapDetail.setText(date + " · " + hm);
        }
        loadEpisodesForDay(dayMs, date, weekIdx, dayIdx, listenedMs);
    }

    private void loadEpisodesForDay(long dayStartMs, String dateLabel,
                                    int weekIdx, int dayIdx, long listenedMs) {
        if (playedDisposable != null) {
            playedDisposable.dispose();
        }
        playedSection.setVisibility(View.VISIBLE);
        playedList.removeAllViews();
        playedEmpty.setVisibility(View.GONE);
        ((TextView) playedSection.findViewById(R.id.played_episodes_title))
                .setText("Episodes played · " + dateLabel);

        // Demo mode: heatmap data is synthetic so the DB is empty — render
        // synthetic episodes that match the cell's listening total.
        if (DemoStats.ENABLED) {
            renderDemoEpisodes(DemoStats.fakeEpisodesForDay(weekIdx, dayIdx, listenedMs));
            return;
        }

        long dayEndMs = dayStartMs + 86_400_000L;
        playedDisposable = Observable.fromCallable(
                        () -> DBReader.getEpisodesPlayedInPeriod(dayStartMs, dayEndMs))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::renderPlayedEpisodes,
                        e -> playedEmpty.setVisibility(View.VISIBLE));
    }

    private void renderPlayedEpisodes(List<FeedItem> items) {
        playedList.removeAllViews();
        if (items.isEmpty()) {
            playedEmpty.setVisibility(View.VISIBLE);
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (FeedItem item : items) {
            View row = inflater.inflate(R.layout.row_played_episode, playedList, false);
            ImageView cover = row.findViewById(R.id.cover_image);
            TextView title = row.findViewById(R.id.episode_title);
            TextView feedTitle = row.findViewById(R.id.feed_title);
            TextView played = row.findViewById(R.id.played_val);

            title.setText(item.getTitle());
            feedTitle.setText(item.getFeed() != null ? item.getFeed().getTitle() : "");
            played.setText(formatPlayed(item.getMedia() != null
                    ? item.getMedia().getPlayedDuration() : 0));

            Glide.with(requireContext())
                    .load(item.getImageLocation())
                    .placeholder(new android.graphics.drawable.ColorDrawable(0xFFE9E4DA))
                    .error(new android.graphics.drawable.ColorDrawable(0xFFE9E4DA))
                    .into(cover);

            playedList.addView(row);
        }
    }

    private void renderDemoEpisodes(List<DemoStats.FakeEpisode> fakes) {
        playedList.removeAllViews();
        if (fakes.isEmpty()) {
            playedEmpty.setVisibility(View.VISIBLE);
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (DemoStats.FakeEpisode ep : fakes) {
            View row = inflater.inflate(R.layout.row_played_episode, playedList, false);
            ImageView cover = row.findViewById(R.id.cover_image);
            TextView title = row.findViewById(R.id.episode_title);
            TextView feedTitle = row.findViewById(R.id.feed_title);
            TextView played = row.findViewById(R.id.played_val);

            title.setText(ep.episodeTitle);
            feedTitle.setText(ep.feedTitle);
            played.setText(formatPlayed(ep.playedMs));
            cover.setImageDrawable(new android.graphics.drawable.ColorDrawable(0xFFE9E4DA));

            playedList.addView(row);
        }
    }

    private static String formatPlayed(long playedMs) {
        long minutes = playedMs / 60_000L;
        long hours = minutes / 60;
        long mins = minutes % 60;
        if (hours > 0) return hours + "h " + mins + "m";
        return mins + "m";
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (playedDisposable != null) {
            playedDisposable.dispose();
        }
    }

    private void bind(DBReader.EditorialStats s) {
        currentStats = s;
        // Year-to-date — show days if ≥ 24 h, else hours
        long ytdMs = 0;
        int curYear = Calendar.getInstance().get(Calendar.YEAR);
        for (DBReader.EditorialStats.YearItem y : s.yearly) {
            if (y.year == curYear) { ytdMs = Math.round(y.hrs * 3_600_000f); break; }
        }
        long ytdHours = ytdMs / 3_600_000L;
        long ytdDays  = ytdHours / 24;
        long remHours = ytdHours % 24;
        long ytdMins  = (ytdMs % 3_600_000L) / 60_000L;
        if (ytdDays > 0) {
            heroHours.setText(ytdDays + "d");
            heroMinutes.setText(String.format(Locale.getDefault(),
                    "%d days, %d hours of audio.", ytdDays, remHours));
        } else {
            heroHours.setText(String.valueOf(ytdHours));
            heroMinutes.setText(String.format(Locale.getDefault(),
                    "and %d %s.", ytdMins, ytdMins == 1 ? "minute" : "minutes"));
        }

        hangTrimmed.setText(formatHM(s.totalSavedMs));
        hangFinished.setText(String.valueOf(s.episodesCompleted));
        hangStreak.setText(String.valueOf(s.streakDays));

        sparkline.setData(s.weekly);
        heatmap.setData(s.heatmap);
    }

    private static String formatHM(long ms) {
        long h = ms / 3_600_000L;
        long m = (ms % 3_600_000L) / 60_000L;
        if (h > 0) return h + "h";
        return m + "m";
    }
}
