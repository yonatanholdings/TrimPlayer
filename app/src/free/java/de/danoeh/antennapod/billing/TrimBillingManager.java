package de.danoeh.antennapod.billing;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.Nullable;

/**
 * Free-flavour billing: deliberately does nothing.
 *
 * The free flavour ships no proprietary Google libraries — the entry requirement
 * for F-Droid, IzzyOnDroid and the FOSS app lists — and Play Billing is the
 * largest of them. Nothing is lost functionally: every feature of TrimPlayer is
 * free, and the Pro surface is a goodwill-contribution screen that is already
 * gated off at runtime by the backend flag (TrimProDialogs.isProUiVisible()).
 *
 * {@link #isUnavailable()} returns true, which is the signal TrimProFragment
 * already uses to hide the purchase buttons and show the "billing unavailable"
 * message — the same path a Play build takes on a device with no Play Store.
 *
 * Keep the public API identical to the `play` twin in
 * app/src/play/java/de/danoeh/antennapod/billing/TrimBillingManager.java.
 */
public final class TrimBillingManager {

    /** Same shape as the play twin's listener; nothing ever fires here. */
    public interface Listener {
        default void onProductDetailsUpdated() {
        }

        default void onPurchaseAcknowledged(String productId) {
        }

        default void onPurchaseFailed(String productId, String message) {
        }

        default void onBillingUnavailable(String reason) {
        }
    }

    private static volatile TrimBillingManager instance;

    public static TrimBillingManager get(Context ctx) {
        TrimBillingManager local = instance;
        if (local == null) {
            synchronized (TrimBillingManager.class) {
                local = instance;
                if (local == null) {
                    instance = local = new TrimBillingManager();
                }
            }
        }
        return local;
    }

    private TrimBillingManager() {
    }

    public void addListener(Listener l) {
    }

    public void removeListener(Listener l) {
    }

    /** Always null: no store, so no localized price. Callers keep their fallback label. */
    @Nullable
    public String getFormattedPrice(String sku) {
        return null;
    }

    /** Always true: there is no billing library in this build. */
    public boolean isUnavailable() {
        return true;
    }

    public void connect() {
    }

    public void queryProductsAsync() {
    }

    public void launchPurchase(Activity activity, String sku) {
    }

    public void queryPurchasesAsync() {
    }
}
