package de.danoeh.antennapod.ui.statistics.years;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import de.danoeh.antennapod.event.StatisticsEvent;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.ui.statistics.R;
import de.danoeh.antennapod.ui.statistics.editorial.EditorialTheme;
import de.danoeh.antennapod.ui.statistics.editorial.StreamgraphView;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class YearsStatisticsFragment extends Fragment {
    private static final String TAG = "YearsStatsFragment";
    private Disposable disposable;

    private TextView allTimeHours;
    private TextView allTimeDays;
    private StreamgraphView streamgraph;
    private LinearLayout yearRows;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_years_editorial, container, false);
        allTimeHours = root.findViewById(R.id.all_time_hours);
        allTimeDays  = root.findViewById(R.id.all_time_days);
        streamgraph  = root.findViewById(R.id.streamgraph);
        yearRows     = root.findViewById(R.id.year_rows);
        allTimeHours.setTypeface(EditorialTheme.getSerif(requireContext()));
        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
        refresh();
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
        if (disposable != null) disposable.dispose();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onStatisticsEvent(StatisticsEvent event) { refresh(); }

    private void refresh() {
        if (disposable != null) disposable.dispose();
        disposable = Observable.fromCallable(DBReader::getEditorialStats)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::bind, e -> Log.e(TAG, Log.getStackTraceString(e)));
    }

    private void bind(DBReader.EditorialStats s) {
        if (s.yearly.isEmpty()) return;

        float totalHrs = 0;
        for (DBReader.EditorialStats.YearItem y : s.yearly) totalHrs += y.hrs;
        long totalDays = (long) (totalHrs / 24);
        long remHours = Math.round(totalHrs % 24);
        allTimeHours.setText(String.format(Locale.getDefault(), "%dd", totalDays));
        allTimeDays.setText(String.format(Locale.getDefault(),
                "%d days, %d hours of audio.", totalDays, remHours));

        streamgraph.setData(s.yearly);

        // Build per-year rows (descending)
        yearRows.removeAllViews();
        List<DBReader.EditorialStats.YearItem> desc = new java.util.ArrayList<>(s.yearly);
        Collections.reverse(desc);
        float maxYearHrs = 0.01f;
        for (DBReader.EditorialStats.YearItem y : desc) if (y.hrs > maxYearHrs) maxYearHrs = y.hrs;

        for (int i = 0; i < desc.size(); i++) {
            DBReader.EditorialStats.YearItem cur = desc.get(i);
            float prevHrs = i < desc.size() - 1 ? desc.get(i + 1).hrs : -1;
            View row = buildYearRow(requireContext(), cur, prevHrs, maxYearHrs);
            yearRows.addView(row);
            // divider
            View div = new View(requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            lp.setMarginStart(dpToPx(24));
            lp.setMarginEnd(dpToPx(24));
            div.setLayoutParams(lp);
            div.setBackgroundColor(EditorialTheme.FAINT);
            yearRows.addView(div);
        }
    }

    private View buildYearRow(Context ctx, DBReader.EditorialStats.YearItem y,
                               float prevHrs, float maxYearHrs) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dpToPx(24), dpToPx(12), dpToPx(24), dpToPx(12));
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        // Year label
        TextView tvYear = new TextView(ctx);
        tvYear.setText(String.valueOf(y.year));
        tvYear.setTextSize(15);
        tvYear.setTextColor(EditorialTheme.INK);
        tvYear.setMinWidth(dpToPx(48));
        row.addView(tvYear);

        // Bar
        ProgressBar bar = new ProgressBar(ctx, null,
                android.R.attr.progressBarStyleHorizontal);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(0,
                dpToPx(3), 1f);
        bLp.setMarginStart(dpToPx(8));
        bLp.setMarginEnd(dpToPx(8));
        bar.setLayoutParams(bLp);
        bar.setMax(1000);
        bar.setProgress((int) (y.hrs / maxYearHrs * 1000));
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(EditorialTheme.ACCENT));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(EditorialTheme.FAINT));
        row.addView(bar);

        // Hours
        TextView tvHrs = new TextView(ctx);
        long days = (long) (y.hrs / 24);
        long hrs  = Math.round(y.hrs % 24);
        tvHrs.setText(days > 0
                ? String.format(Locale.getDefault(), "%dd %dh", days, hrs)
                : String.format(Locale.getDefault(), "%dh", Math.round(y.hrs)));
        tvHrs.setTextSize(16);
        tvHrs.setTextColor(EditorialTheme.INK);
        tvHrs.setTypeface(EditorialTheme.getSerif(ctx));
        tvHrs.setMinWidth(dpToPx(48));
        tvHrs.setGravity(android.view.Gravity.END);
        row.addView(tvHrs);

        // YoY delta
        TextView tvDelta = new TextView(ctx);
        tvDelta.setTextSize(11);
        tvDelta.setMinWidth(dpToPx(48));
        tvDelta.setGravity(android.view.Gravity.END);
        if (prevHrs < 0) {
            tvDelta.setText("FIRST");
            tvDelta.setTextColor(EditorialTheme.INK_MUTE);
        } else if (prevHrs == 0) {
            tvDelta.setText("");
        } else {
            int delta = Math.round((y.hrs - prevHrs) / prevHrs * 100);
            if (delta > 0) {
                tvDelta.setText("▲ " + delta + "%");
                tvDelta.setTextColor(0xFF3a7a3a);
            } else {
                tvDelta.setText("▼ " + Math.abs(delta) + "%");
                tvDelta.setTextColor(EditorialTheme.ACCENT);
            }
        }
        row.addView(tvDelta);

        return row;
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
