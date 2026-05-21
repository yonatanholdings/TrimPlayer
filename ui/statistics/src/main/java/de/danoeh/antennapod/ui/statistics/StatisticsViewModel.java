package de.danoeh.antennapod.ui.statistics;

import android.util.Log;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import de.danoeh.antennapod.storage.database.DBReader;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Parent-fragment-scoped ViewModel that owns the two heavy stats queries
 * ({@link DBReader#getEditorialStats()} and {@link DBReader#getSkipStatistics()})
 * and exposes them as {@link LiveData} so the 5 tab fragments can observe
 * shared results instead of each running its own query on every visit.
 *
 * Lifecycle: scoped to {@code StatisticsFragment} via
 * {@code new ViewModelProvider(requireParentFragment()).get(...)}. Child
 * fragments observe with {@code getViewLifecycleOwner()}; their disposal is
 * handled automatically when the tab is detached. The underlying RxJava
 * subscriptions are cleared in {@link #onCleared()} when the parent Stats
 * screen is finally destroyed.
 */
public class StatisticsViewModel extends ViewModel {
    private static final String TAG = "StatisticsViewModel";

    private final MutableLiveData<DBReader.EditorialStats> editorial = new MutableLiveData<>();
    private final MutableLiveData<DBReader.SkipStatistics> skip = new MutableLiveData<>();
    private final CompositeDisposable disposables = new CompositeDisposable();
    private boolean loaded = false;

    /** First call kicks off the queries lazily; subsequent calls just return the cached LiveData. */
    public LiveData<DBReader.EditorialStats> editorial() {
        ensureLoaded();
        return editorial;
    }

    public LiveData<DBReader.SkipStatistics> skip() {
        ensureLoaded();
        return skip;
    }

    private void ensureLoaded() {
        if (!loaded) {
            loaded = true;
            refresh();
        }
    }

    /** Re-run both queries (e.g. after a reset, or filter change). Idempotent. */
    public void refresh() {
        disposables.clear();
        // Demo-data short-circuit for marketing screenshots. Toggle in DemoStats.
        if (DemoStats.ENABLED) {
            editorial.setValue(DemoStats.fakeEditorial());
            skip.setValue(DemoStats.fakeSkip());
            return;
        }
        disposables.add(Observable.fromCallable(DBReader::getEditorialStats)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(editorial::setValue,
                        e -> Log.e(TAG, "editorial query failed", e)));
        disposables.add(Observable.fromCallable(DBReader::getSkipStatistics)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(skip::setValue,
                        e -> Log.e(TAG, "skip query failed", e)));
    }

    @Override
    protected void onCleared() {
        disposables.dispose();
    }
}
