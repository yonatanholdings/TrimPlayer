package de.danoeh.antennapod.ui.statistics.subscriptions;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.List;
import java.util.Locale;

import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.ui.statistics.R;
import de.danoeh.antennapod.ui.statistics.StatisticsViewModel;
import de.danoeh.antennapod.ui.statistics.editorial.DonutView;
import de.danoeh.antennapod.ui.statistics.editorial.EditorialTheme;
import de.danoeh.antennapod.ui.statistics.editorial.FeedColorCache;
import de.danoeh.antennapod.ui.statistics.feed.FeedDossierDialogFragment;

public class SubscriptionStatisticsFragment extends Fragment {

    private DonutView donutChart;
    private TextView donutKicker;
    private TextView donutCenter;
    private TextView donutCaption;
    private TextView topShow1;
    private TextView topShow2;
    private TextView topShow3;
    private RecyclerView recycler;
    private ShowAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_subscriptions_editorial, container, false);

        donutChart  = root.findViewById(R.id.donut_chart);
        donutKicker = root.findViewById(R.id.donut_kicker);
        donutCenter = root.findViewById(R.id.donut_center);
        donutCaption= root.findViewById(R.id.donut_caption);
        topShow1    = root.findViewById(R.id.top_show_1);
        topShow2    = root.findViewById(R.id.top_show_2);
        topShow3    = root.findViewById(R.id.top_show_3);
        recycler    = root.findViewById(R.id.subscriptions_list);

        donutCenter.setTypeface(EditorialTheme.getSerif(requireContext()));

        adapter = new ShowAdapter(requireContext(),
                (feedId, position) -> {
                    // Row tap: open dossier AND mirror selection on the donut.
                    FeedDossierDialogFragment.newInstance(feedId)
                            .show(getChildFragmentManager(), "dossier");
                    donutChart.setSelectedIndex(position);
                });
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setAdapter(adapter);
        recycler.setNestedScrollingEnabled(false);

        // Donut tap: select that segment, highlight + scroll to the matching
        // row in the list. Inverse of the row-tap path above.
        donutChart.setOnSegmentClickListener(index -> {
            if (index < 0 || index >= adapter.getItemCount()) {
                return;
            }
            adapter.setHighlightedIndex(donutChart.getSelectedIndex());
            recycler.smoothScrollToPosition(index);
        });

        new ViewModelProvider(requireParentFragment())
                .get(StatisticsViewModel.class)
                .editorial()
                .observe(getViewLifecycleOwner(), s -> { if (s != null) bind(s); });

        return root;
    }

    private void bind(DBReader.EditorialStats s) {
        donutChart.setData(s.shows);

        // Donut center text
        float totalHrs = 0;
        for (DBReader.EditorialStats.ShowItem sh : s.shows) {
            totalHrs += sh.hrs;
        }
        donutKicker.setText("Total");
        donutCenter.setText(String.format(Locale.getDefault(), "%.0fh", totalHrs));
        donutCaption.setText(s.shows.size() + " shows");

        // Top 3 side labels
        bindTopLabel(topShow1, s.shows, 0);
        bindTopLabel(topShow2, s.shows, 1);
        bindTopLabel(topShow3, s.shows, 2);

        adapter.setData(s.shows);
    }

    private static void bindTopLabel(TextView tv, List<DBReader.EditorialStats.ShowItem> shows, int idx) {
        if (idx < shows.size() && shows.get(idx).feedId != -1) {
            DBReader.EditorialStats.ShowItem sh = shows.get(idx);
            tv.setText(String.format(Locale.getDefault(),
                    "%02d  %s  %.1fh", idx + 1, truncate(sh.title, 18), sh.hrs));
            tv.setVisibility(View.VISIBLE);
        } else {
            tv.setVisibility(View.GONE);
        }
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    // ─── Show row adapter ──────────────────────────────────────────────────────

    interface OnShowClickListener { void onShowClick(long feedId, int position); }

    static class ShowAdapter extends RecyclerView.Adapter<ShowAdapter.VH> {
        private List<DBReader.EditorialStats.ShowItem> data = java.util.Collections.emptyList();
        private float maxHrs = 1f;
        private final Context ctx;
        private final OnShowClickListener listener;
        /** Index that should paint with a subtle accent background (driven by a
         *  donut-segment tap from the sibling chart). -1 = no highlight. */
        private int highlightedIndex = -1;

        ShowAdapter(Context ctx, OnShowClickListener listener) {
            this.ctx = ctx;
            this.listener = listener;
        }

        void setHighlightedIndex(int index) {
            int old = highlightedIndex;
            highlightedIndex = index;
            if (old >= 0) {
                notifyItemChanged(old);
            }
            if (index >= 0) {
                notifyItemChanged(index);
            }
        }

        void setData(List<DBReader.EditorialStats.ShowItem> items) {
            this.data = items;
            maxHrs = 0.01f;
            for (DBReader.EditorialStats.ShowItem it : items) if (it.hrs > maxHrs) maxHrs = it.hrs;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(ctx).inflate(R.layout.show_row_editorial, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            DBReader.EditorialStats.ShowItem sh = data.get(pos);
            h.boundFeedId = sh.feedId;
            h.ordinal.setText(String.format(Locale.getDefault(), "%02d", pos + 1));
            h.title.setText(sh.title);
            h.hours.setText(String.format(Locale.getDefault(), "%.1fh", sh.hrs));
            h.pct.setText(sh.pct + "%");
            h.bar.setProgress((int) (sh.hrs / maxHrs * 1000));
            // Apply the fallback color immediately so the row doesn't flash blank
            // while Palette runs. Real cover color overwrites it once extracted.
            applyAccentColor(h, sh.color);

            if (sh.feedId != -1) {
                Glide.with(ctx).load(sh.imageUrl)
                        .placeholder(colorDrawable(sh.color))
                        .error(colorDrawable(sh.color))
                        .into(h.cover);
                // Async palette extraction; guard against view recycle by
                // checking the bound feedId still matches before applying.
                final long requested = sh.feedId;
                FeedColorCache.apply(ctx, sh.feedId, sh.imageUrl, sh.color, color -> {
                    if (h.boundFeedId == requested) {
                        applyAccentColor(h, color);
                    }
                });
                final int rowPos = pos;
                h.itemView.setOnClickListener(v -> listener.onShowClick(sh.feedId, rowPos));
            } else {
                h.cover.setImageDrawable(colorDrawable(sh.color));
                h.itemView.setOnClickListener(null);
            }

            // Background flash for the donut-driven highlighted row.
            h.itemView.setBackgroundColor(pos == highlightedIndex
                    ? androidx.core.content.ContextCompat.getColor(ctx,
                            de.danoeh.antennapod.ui.statistics.R.color.editorial_vermilion_tint)
                    : 0);
        }

        private void applyAccentColor(VH h, int color) {
            h.bar.setProgressTintList(android.content.res.ColorStateList.valueOf(color));
        }

        private android.graphics.drawable.ColorDrawable colorDrawable(int color) {
            return new android.graphics.drawable.ColorDrawable(color);
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            TextView ordinal;
            TextView title;
            TextView hours;
            TextView pct;
            ImageView cover;
            ProgressBar bar;
            /** Tracks which feed this holder is currently bound to, so async
             *  Palette callbacks don't paint the wrong row after recycling. */
            long boundFeedId;

            VH(View v) {
                super(v);
                ordinal = v.findViewById(R.id.ordinal);
                title   = v.findViewById(R.id.show_title);
                hours   = v.findViewById(R.id.hours_val);
                pct     = v.findViewById(R.id.pct_val);
                cover   = v.findViewById(R.id.cover_image);
                bar     = v.findViewById(R.id.bar);
            }
        }
    }
}
