package de.danoeh.antennapod.ui.screen.home.sections;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.databinding.HomeSectionMonthlyStatsBinding;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.StatisticsItem;
import de.danoeh.antennapod.ui.common.Converter;
import de.danoeh.antennapod.ui.echo.EchoConfig;
import de.danoeh.antennapod.ui.screen.home.HomeFragment;
import de.danoeh.antennapod.ui.statistics.StatisticsFragment;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

import java.util.Calendar;

public class MonthlyStatsSection extends Fragment {
    public static final String TAG = "MonthlyStatsSection";
    private HomeSectionMonthlyStatsBinding viewBinding;
    private Disposable disposable;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        viewBinding = HomeSectionMonthlyStatsBinding.inflate(inflater);
        viewBinding.statsCard.setOnClickListener(v ->
                ((MainActivity) requireActivity()).loadChildFragment(new StatisticsFragment()));
        return viewBinding.getRoot();
    }

    @Override
    public void onStart() {
        super.onStart();
        boolean echoVisible = EchoConfig.isCurrentlyVisible()
                && requireContext().getSharedPreferences(HomeFragment.PREF_NAME, Context.MODE_PRIVATE)
                        .getInt(HomeFragment.PREF_HIDE_ECHO, 0) != EchoConfig.RELEASE_YEAR;
        if (echoVisible) {
            viewBinding.getRoot().setVisibility(View.GONE);
            return;
        }
        viewBinding.getRoot().setVisibility(View.VISIBLE);
        loadStats();
    }

    @Override
    public void onStop() {
        super.onStop();
        if (disposable != null) {
            disposable.dispose();
        }
    }

    private void loadStats() {
        if (disposable != null) {
            disposable.dispose();
        }
        long start = startOfMonth();
        long end = startOfNextMonth();
        disposable = Observable.fromCallable(
                () -> DBReader.getStatistics(false, start, end))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    long played = 0;
                    long skipped = 0;
                    for (StatisticsItem item : result.feedTime) {
                        played += item.timePlayed;
                        skipped += item.timeSkipped;
                    }
                    viewBinding.listenedValue.setText(
                            Converter.shortLocalizedDuration(requireContext(), played));
                    viewBinding.trimmedValue.setText(
                            Converter.shortLocalizedDuration(requireContext(), skipped));
                }, Throwable::printStackTrace);
    }

    private static long startOfMonth() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.set(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    private static long startOfNextMonth() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.add(Calendar.MONTH, 1);
        return c.getTimeInMillis();
    }
}
