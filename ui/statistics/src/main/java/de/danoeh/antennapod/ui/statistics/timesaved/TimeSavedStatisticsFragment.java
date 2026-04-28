package de.danoeh.antennapod.ui.statistics.timesaved;

import android.os.Bundle;
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

import java.text.DateFormatSymbols;
import java.util.Locale;

import de.danoeh.antennapod.event.StatisticsEvent;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.ui.statistics.R;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class TimeSavedStatisticsFragment extends Fragment {

    private TextView totalSavedValue;
    private TextView periodTodayValue;
    private TextView periodWeekValue;
    private TextView periodMonthValue;
    private ProgressBar barIntro;
    private ProgressBar barOutro;
    private ProgressBar barAds;
    private ProgressBar barSilence;
    private ProgressBar barSpeed;
    private TextView valueIntro;
    private TextView valueOutro;
    private TextView valueAds;
    private TextView valueSilence;
    private TextView valueSpeed;
    private LinearLayout monthlyContainer;
    private TextView emptyState;
    private Disposable disposable;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_time_saved_statistics, container, false);

        totalSavedValue   = root.findViewById(R.id.total_saved_value);
        periodTodayValue  = root.findViewById(R.id.period_today_value);
        periodWeekValue   = root.findViewById(R.id.period_week_value);
        periodMonthValue  = root.findViewById(R.id.period_month_value);
        barIntro          = root.findViewById(R.id.bar_intro);
        barOutro          = root.findViewById(R.id.bar_outro);
        barAds            = root.findViewById(R.id.bar_ads);
        barSilence        = root.findViewById(R.id.bar_silence);
        barSpeed          = root.findViewById(R.id.bar_speed);
        valueIntro        = root.findViewById(R.id.value_intro);
        valueOutro        = root.findViewById(R.id.value_outro);
        valueAds          = root.findViewById(R.id.value_ads);
        valueSilence      = root.findViewById(R.id.value_silence);
        valueSpeed        = root.findViewById(R.id.value_speed);
        monthlyContainer  = root.findViewById(R.id.monthly_container);
        emptyState        = root.findViewById(R.id.empty_state);

        refreshStatistics();
        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
        refreshStatistics();
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
        if (disposable != null) {
            disposable.dispose();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void statisticsEvent(StatisticsEvent event) {
        refreshStatistics();
    }

    private void refreshStatistics() {
        if (disposable != null) {
            disposable.dispose();
        }
        disposable = Observable.fromCallable(DBReader::getSkipStatistics)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::showStatistics, error -> emptyState.setVisibility(View.VISIBLE));
    }

    private void showStatistics(DBReader.SkipStatistics stats) {
        android.content.Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        emptyState.setVisibility(stats.totalMs <= 0 ? View.VISIBLE : View.GONE);
        if (stats.totalMs <= 0) {
            String zero = formatExactDuration(0);
            totalSavedValue.setText(zero);
            periodTodayValue.setText(zero);
            periodWeekValue.setText(zero);
            periodMonthValue.setText(zero);
            setTypeRow(barIntro, valueIntro, 0, 1);
            setTypeRow(barOutro, valueOutro, 0, 1);
            setTypeRow(barAds, valueAds, 0, 1);
            setTypeRow(barSilence, valueSilence, 0, 1);
            setTypeRow(barSpeed, valueSpeed, 0, 1);
            return;
        }

        totalSavedValue.setText(formatExactDuration(stats.totalMs));
        periodTodayValue.setText(formatExactDuration(stats.todayMs));
        periodWeekValue.setText(formatExactDuration(stats.weekMs));
        periodMonthValue.setText(formatExactDuration(stats.monthMs));

        // Type breakdown bars — scale relative to max type
        long maxType = Math.max(1, Math.max(stats.introMs,
                Math.max(stats.outroMs, Math.max(stats.adMs,
                        Math.max(stats.silenceMs, stats.speedMs)))));

        setTypeRow(barIntro, valueIntro, stats.introMs, maxType);
        setTypeRow(barOutro, valueOutro, stats.outroMs, maxType);
        setTypeRow(barAds, valueAds, stats.adMs, maxType);
        setTypeRow(barSilence, valueSilence, stats.silenceMs, maxType);
        setTypeRow(barSpeed, valueSpeed, stats.speedMs, maxType);

        // Monthly history
        monthlyContainer.removeAllViews();
        String[] monthNames = new DateFormatSymbols().getShortMonths();
        for (DBReader.MonthlySkipItem item : stats.monthly) {
            View row = buildMonthlyRow(item, monthNames, ctx);
            monthlyContainer.addView(row);
        }
    }

    private void setTypeRow(ProgressBar bar, TextView value, long ms, long maxMs) {
        bar.setProgress(maxMs > 0 ? (int) (ms * 1000 / maxMs) : 0);
        value.setText(ms > 0 ? formatExactDuration(ms) : "—");
    }

    private View buildMonthlyRow(DBReader.MonthlySkipItem item, String[] monthNames, android.content.Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 4, 0, 4);

        String label = String.format(Locale.getDefault(), "%s %d",
                monthNames[item.month - 1], item.year);

        TextView labelView = new TextView(ctx);
        labelView.setText(label);
        labelView.setTextSize(13);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        labelView.setLayoutParams(lp);

        TextView valueView = new TextView(ctx);
        valueView.setText(formatExactDuration(item.totalMs));
        valueView.setTextSize(13);
        valueView.setGravity(android.view.Gravity.END);
        valueView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        row.addView(labelView);
        row.addView(valueView);
        return row;
    }

    private static String formatExactDuration(long ms) {
        long totalSeconds = ms / 1000;
        long seconds = totalSeconds % 60;
        long totalMinutes = totalSeconds / 60;
        long minutes = totalMinutes % 60;
        long totalHours = totalMinutes / 60;
        long hours = totalHours % 24;
        long days = totalHours / 24;

        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("d ");
        }
        if (days > 0 || hours > 0) {
            sb.append(hours).append("h ");
        }
        if (days > 0 || hours > 0 || minutes > 0) {
            sb.append(minutes).append("m ");
        }
        sb.append(seconds).append("s");
        return sb.toString();
    }
}
