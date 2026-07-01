package de.danoeh.antennapod.playback.service.trim;

import android.util.Log;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Last-seen Pro entitlement snapshot.
 *
 * Source of truth lives on the backend (a JWT + a row in device_entitlements);
 * this store caches what /segments returned so UI elements (Pro badge, upsell
 * modal, settings copy) can render without a network round-trip.
 *
 * Persistence is handled by {@link UserPreferences} so the snapshot survives
 * process death between sessions. We re-hydrate lazily on first read.
 *
 * Threading: snapshots are immutable value objects; the field reference is
 * volatile. Listeners are dispatched on the caller's thread — usually the
 * Retrofit response thread — so listeners must post to the main thread
 * themselves if they touch UI.
 */
public final class EntitlementStore {
    private static final String TAG = "EntitlementStore";
    private static volatile EntitlementStore instance;

    public static EntitlementStore get() {
        EntitlementStore local = instance;
        if (local == null) {
            synchronized (EntitlementStore.class) {
                local = instance;
                if (local == null) {
                    instance = local = new EntitlementStore();
                }
            }
        }
        return local;
    }

    public interface Listener {
        void onEntitlementChanged(Snapshot snapshot);
    }

    /**
     * Immutable snapshot. Build via {@link #fromServer} or {@link #unknown}.
     */
    public static final class Snapshot {
        public final String status;          // "ok" | "quota_exceeded" | "pro" | null (unknown)
        public final String source;          // play_subscription | play_lifetime | beta_grandfather | null
        public final Integer quotaUsed;      // free-tier auto-trims used this month, null if unknown
        public final Integer quotaLimit;     // free-tier monthly quota, null if unknown
        public final String resetsAtIso;     // ISO-8601 next-month boundary, null if unknown
        /** Server-driven kill-switch for in-app Pro UI. False (default) on
         *  pre-2026-05-21 server responses and at first launch — UI stays
         *  hidden until the server explicitly says otherwise. */
        public final boolean proUiVisible;

        private Snapshot(String status, String source,
                         Integer quotaUsed, Integer quotaLimit, String resetsAtIso,
                         boolean proUiVisible) {
            this.status = status;
            this.source = source;
            this.quotaUsed = quotaUsed;
            this.quotaLimit = quotaLimit;
            this.resetsAtIso = resetsAtIso;
            this.proUiVisible = proUiVisible;
        }

        public static Snapshot unknown() {
            return new Snapshot(null, null, null, null, null, false);
        }

        public static Snapshot fromServer(TrimClient.EntitlementStatus ent) {
            if (ent == null) {
                return unknown();
            }
            boolean visible = ent.pro_ui_visible != null && ent.pro_ui_visible;
            return new Snapshot(ent.status, ent.source, ent.used, ent.quota, ent.resets_at, visible);
        }

        public boolean isPro() {
            return "pro".equals(status);
        }

        public boolean isQuotaExceeded() {
            return "quota_exceeded".equals(status);
        }

        public boolean isBetaGrandfather() {
            return "beta_grandfather".equals(source);
        }
    }

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile Snapshot current;

    private EntitlementStore() {
        // Re-hydrate from disk so the first UI read after process restart sees
        // the most recent server-confirmed state instead of "unknown".
        Integer used  = nullIfZero(UserPreferences.getTrimQuotaUsed());
        Integer limit = UserPreferences.getTrimQuotaLimit();
        this.current = new Snapshot(
                UserPreferences.getTrimProStatus(),
                UserPreferences.getTrimProSource(),
                used,
                limit,
                UserPreferences.getTrimQuotaResetsAt(),
                UserPreferences.getTrimProUiVisible());
    }

    /** Get the current snapshot. Never null. */
    public Snapshot snapshot() {
        Snapshot s = current;
        return s != null ? s : Snapshot.unknown();
    }

    /** Update from a /segments response. No-op if {@code ent} is null. Notifies
     *  listeners only when something actually changed. */
    public void updateFromServer(TrimClient.EntitlementStatus ent) {
        if (ent == null) {
            return;
        }
        Snapshot next = Snapshot.fromServer(ent);
        Snapshot prev = current;
        if (snapshotsEqual(prev, next)) {
            return;
        }
        current = next;
        UserPreferences.writeTrimEntitlementSnapshot(
                next.status, next.source, next.quotaUsed, next.quotaLimit, next.resetsAtIso,
                next.proUiVisible);
        Log.d(TAG, "Entitlement updated: status=" + next.status
                + " source=" + next.source
                + " used=" + next.quotaUsed + "/" + next.quotaLimit
                + " proUiVisible=" + next.proUiVisible);
        for (Listener l : listeners) {
            try {
                l.onEntitlementChanged(next);
            } catch (RuntimeException ex) {
                Log.w(TAG, "Listener threw", ex);
            }
        }
    }

    /** Apply a freshly-minted Pro JWT (from /billing/verify). Flips status to
     *  pro immediately so the UI updates before the next /segments call. */
    public void applyProToken(String proToken, long expiresMs, String source) {
        UserPreferences.writeTrimProToken(proToken, expiresMs);
        // Preserve the existing proUiVisible flag — purchasing Pro shouldn't
        // independently flip the visibility kill-switch one way or the other.
        boolean prevVisible = current != null && current.proUiVisible;
        Snapshot next = new Snapshot("pro", source, null, null, null, prevVisible);
        if (!snapshotsEqual(current, next)) {
            current = next;
            UserPreferences.writeTrimEntitlementSnapshot(
                    next.status, next.source, null, null, null, next.proUiVisible);
            for (Listener l : listeners) {
                try {
                    l.onEntitlementChanged(next);
                } catch (RuntimeException ex) {
                    Log.w(TAG, "Listener threw", ex);
                }
            }
        }
    }

    public void addListener(Listener l) {
        if (l != null) {
            listeners.addIfAbsent(l);
        }
    }

    public void removeListener(Listener l) {
        if (l != null) {
            listeners.remove(l);
        }
    }

    private static boolean snapshotsEqual(Snapshot a, Snapshot b) {
        if (a == null || b == null) {
            return a == b;
        }
        return eq(a.status, b.status)
                && eq(a.source, b.source)
                && eq(a.quotaUsed, b.quotaUsed)
                && eq(a.quotaLimit, b.quotaLimit)
                && eq(a.resetsAtIso, b.resetsAtIso)
                && a.proUiVisible == b.proUiVisible;
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private static Integer nullIfZero(int v) {
        return v <= 0 ? null : v;
    }
}
