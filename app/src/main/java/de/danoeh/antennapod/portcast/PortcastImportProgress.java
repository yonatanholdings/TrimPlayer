package de.danoeh.antennapod.portcast;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.work.Data;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import java.util.List;

import de.danoeh.antennapod.storage.importexport.PortcastStateWorker;
import de.danoeh.antennapod.storage.importexport.PortcastSubscribeWorker;

/**
 * Combines the two PortCast / Spotify import workers'
 * ({@link PortcastSubscribeWorker} then {@link PortcastStateWorker}) WorkManager
 * state into a single value the in-app import status banner observes.
 *
 * <p>Emits {@code null} when no import is in flight — both unique works are
 * finished or were never enqueued — so an observer can simply hide the banner on
 * a {@code null} value. While the subscribe worker runs, the value carries an
 * {@code X of Y} feed count; the (potentially multi-minute, retrying) apply phase
 * is reported as indeterminate.
 */
public final class PortcastImportProgress {
    public enum Phase { SUBSCRIBING, APPLYING }

    private final Phase phase;
    private final int current;
    private final int total;

    private PortcastImportProgress(Phase phase, int current, int total) {
        this.phase = phase;
        this.current = current;
        this.total = total;
    }

    public Phase getPhase() {
        return phase;
    }

    /**
     * Feeds subscribed so far. Only meaningful for {@link Phase#SUBSCRIBING}.
     */
    public int getCurrent() {
        return current;
    }

    /** Total feeds to subscribe; 0 until the worker reports it. */
    public int getTotal() {
        return total;
    }

    /**
     * True when there is a concrete {@code X of Y} to show.
     */
    public boolean hasCount() {
        return phase == Phase.SUBSCRIBING && total > 0;
    }

    /**
     * Observe the combined import status. The returned LiveData emits a fresh
     * value whenever either worker changes state, and {@code null} once the
     * whole import has finished.
     */
    public static LiveData<PortcastImportProgress> observe(Context context) {
        WorkManager wm = WorkManager.getInstance(context);
        LiveData<List<WorkInfo>> subscribe =
                wm.getWorkInfosForUniqueWorkLiveData(PortcastSubscribeWorker.WORK_ID);
        LiveData<List<WorkInfo>> apply =
                wm.getWorkInfosForUniqueWorkLiveData(PortcastStateWorker.WORK_ID);

        MediatorLiveData<PortcastImportProgress> result = new MediatorLiveData<>();
        Combiner combiner = new Combiner(result);
        result.addSource(subscribe, combiner::onSubscribe);
        result.addSource(apply, combiner::onApply);
        return result;
    }

    /** Holds the latest snapshot of each work list and recomputes the merged value. */
    private static final class Combiner {
        private final MediatorLiveData<PortcastImportProgress> out;
        @Nullable private List<WorkInfo> subscribe;
        @Nullable private List<WorkInfo> apply;

        Combiner(MediatorLiveData<PortcastImportProgress> out) {
            this.out = out;
        }

        void onSubscribe(List<WorkInfo> infos) {
            subscribe = infos;
            recompute();
        }

        void onApply(List<WorkInfo> infos) {
            apply = infos;
            recompute();
        }

        private void recompute() {
            // Subscribe phase takes priority: it's enqueued first and carries the
            // useful X-of-Y count. The apply worker is enqueued right as subscribe
            // finishes, so the banner stays up continuously across the handoff.
            WorkInfo subscribing = firstActive(subscribe);
            if (subscribing != null) {
                Data progress = subscribing.getProgress();
                int current = progress.getInt(PortcastSubscribeWorker.PROGRESS_CURRENT, 0);
                int total = progress.getInt(PortcastSubscribeWorker.PROGRESS_TOTAL, 0);
                out.setValue(new PortcastImportProgress(Phase.SUBSCRIBING, current, total));
            } else if (firstActive(apply) != null) {
                out.setValue(new PortcastImportProgress(Phase.APPLYING, 0, 0));
            } else {
                out.setValue(null);
            }
        }

        @Nullable
        private static WorkInfo firstActive(@Nullable List<WorkInfo> infos) {
            if (infos == null) {
                return null;
            }
            for (WorkInfo info : infos) {
                // ENQUEUED (incl. between retry backoffs), RUNNING and BLOCKED all
                // count as in-flight; only terminal states are "finished".
                if (!info.getState().isFinished()) {
                    return info;
                }
            }
            return null;
        }
    }
}
