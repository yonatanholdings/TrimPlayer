package de.danoeh.antennapod.ui.statistics.insights;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.List;
import java.util.Locale;

import de.danoeh.antennapod.event.StatisticsEvent;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.ui.statistics.R;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class InsightsStatisticsFragment extends Fragment {

    private LinearLayout rowsContainer;
    private TextView emptyState;
    private Disposable disposable;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_insights_statistics, container, false);
        rowsContainer = root.findViewById(R.id.rows_container);
        emptyState = root.findViewById(R.id.empty_state);
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
        if (disposable != null) {
            disposable.dispose();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onStatisticsEvent(StatisticsEvent event) {
        refresh();
    }

    private void refresh() {
        if (disposable != null) {
            disposable.dispose();
        }
        disposable = Observable.fromCallable(DBReader::getInsightsData)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::showData,
                        error -> emptyState.setVisibility(View.VISIBLE));
    }

    private void showData(List<DBReader.InsightPeriod> periods) {
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        rowsContainer.removeAllViews();

        boolean hasAnyData = false;
        for (DBReader.InsightPeriod p : periods) {
            if (p.playedMs > 0 || p.savedMs > 0) {
                hasAnyData = true;
            }
            rowsContainer.addView(buildRow(ctx, p));
        }

        emptyState.setVisibility(hasAnyData ? View.GONE : View.VISIBLE);
    }

    private View buildRow(Context ctx, DBReader.InsightPeriod period) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int vPad = dpToPx(ctx, 7);
        row.setPadding(0, vPad, 0, vPad);

        boolean hasData = period.playedMs > 0 || period.savedMs > 0;
        float alpha = hasData ? 1f : 0.4f;

        TextView labelView = makeText(ctx, period.label, 2f, Gravity.START);
        labelView.setAlpha(alpha);

        String playedStr = period.playedMs > 0 ? formatDuration(period.playedMs) : "—";
        TextView playedView = makeText(ctx, playedStr, 2f, Gravity.END);
        playedView.setAlpha(alpha);

        String savedStr = period.savedMs > 0 ? formatDuration(period.savedMs) : "—";
        TextView savedView = makeText(ctx, savedStr, 2f, Gravity.END);
        savedView.setAlpha(alpha);

        String pctStr;
        if (period.playedMs > 0 && period.savedMs > 0) {
            double pct = (double) period.savedMs / period.playedMs * 100.0;
            pctStr = String.format(Locale.getDefault(), "%.2f%%", pct);
        } else {
            pctStr = "—";
        }
        TextView pctView = makeText(ctx, pctStr, 1f, Gravity.END);
        pctView.setAlpha(alpha);

        row.addView(labelView);
        row.addView(playedView);
        row.addView(savedView);
        row.addView(pctView);
        return row;
    }

    private TextView makeText(Context ctx, String text, float weight, int gravity) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setGravity(gravity);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        tv.setLayoutParams(lp);
        return tv;
    }

    private static String formatDuration(long ms) {
        long totalSeconds = ms / 1000;
        long seconds = totalSeconds % 60;
        long totalMinutes = totalSeconds / 60;
        long minutes = totalMinutes % 60;
        long totalHours = totalMinutes / 60;
        long hours = totalHours % 24;
        long days = totalHours / 24;

        if (days > 0) {
            return String.format(Locale.getDefault(), "%dd %dh", days, hours);
        } else if (hours > 0) {
            return String.format(Locale.getDefault(), "%dh %dm", hours, minutes);
        } else if (minutes > 0) {
            return String.format(Locale.getDefault(), "%dm %ds", minutes, seconds);
        }
        return String.format(Locale.getDefault(), "%ds", seconds);
    }

    private static int dpToPx(Context ctx, int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
