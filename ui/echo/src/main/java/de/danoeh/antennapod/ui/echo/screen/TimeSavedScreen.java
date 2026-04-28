package de.danoeh.antennapod.ui.echo.screen;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.ui.echo.R;
import de.danoeh.antennapod.ui.echo.background.WaveformBackground;
import de.danoeh.antennapod.ui.echo.databinding.SimpleEchoScreenBinding;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

import java.util.ArrayList;
import java.util.List;

public class TimeSavedScreen extends EchoScreen {
    private static final String TAG = "TimeSavedScreen";
    private final SimpleEchoScreenBinding viewBinding;
    private Disposable disposable;

    public TimeSavedScreen(Context context, LayoutInflater layoutInflater) {
        super(context);
        viewBinding = SimpleEchoScreenBinding.inflate(layoutInflater);
        viewBinding.aboveLabel.setText(R.string.echo_time_saved_title);
        viewBinding.backgroundImage.setImageDrawable(new WaveformBackground(context));
    }

    private void display(DBReader.SkipStatistics stats) {
        if (stats.totalMs <= 0) {
            viewBinding.largeLabel.setText("0");
            viewBinding.belowLabel.setText(R.string.echo_time_saved_subtitle_none);
            viewBinding.smallLabel.setText(R.string.echo_time_saved_comment_none);
            return;
        }

        long totalMinutes = stats.totalMs / (1000 * 60);
        long totalHours = totalMinutes / 60;

        if (totalHours == 0) {
            viewBinding.largeLabel.setText(String.format(getEchoLanguage(), "%d", totalMinutes));
            viewBinding.belowLabel.setText(R.string.echo_time_saved_unit_minutes);
        } else {
            viewBinding.largeLabel.setText(String.format(getEchoLanguage(), "%d", totalHours));
            viewBinding.belowLabel.setText(R.string.echo_time_saved_unit_hours);
        }
        viewBinding.smallLabel.setText(buildBreakdown(stats));
    }

    private String buildBreakdown(DBReader.SkipStatistics stats) {
        List<String> parts = new ArrayList<>();
        if (stats.introMs > 0) {
            parts.add(formatMinutes(stats.introMs) + " from intros");
        }
        if (stats.adMs > 0) {
            parts.add(formatMinutes(stats.adMs) + " from ads");
        }
        if (stats.outroMs > 0) {
            parts.add(formatMinutes(stats.outroMs) + " from outros");
        }
        if (stats.silenceMs > 0) {
            parts.add(formatMinutes(stats.silenceMs) + " from silence");
        }
        if (stats.speedMs > 0) {
            parts.add(formatMinutes(stats.speedMs) + " from speed");
        }
        return android.text.TextUtils.join(" · ", parts);
    }

    private String formatMinutes(long ms) {
        long minutes = ms / (1000 * 60);
        long hours = minutes / 60;
        if (hours == 0) {
            return String.format(getEchoLanguage(), "%d min", minutes);
        } else {
            long remainingMin = minutes % 60;
            if (remainingMin == 0) {
                return String.format(getEchoLanguage(), "%dh", hours);
            }
            return String.format(getEchoLanguage(), "%dh %dm", hours, remainingMin);
        }
    }

    @Override
    public View getView() {
        return viewBinding.getRoot();
    }

    @Override
    public void postInvalidate() {
        viewBinding.backgroundImage.postInvalidate();
    }

    @Override
    public void startLoading(DBReader.StatisticsResult statisticsResult) {
        if (disposable != null) {
            disposable.dispose();
        }
        disposable = Observable.fromCallable(DBReader::getSkipStatistics)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::display, error -> Log.e(TAG, Log.getStackTraceString(error)));
    }
}
