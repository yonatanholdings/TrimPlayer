package de.danoeh.antennapod.billing;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.ProductDetailsResponseListener;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import de.danoeh.antennapod.playback.service.trim.EntitlementStore;
import de.danoeh.antennapod.playback.service.trim.TrimClient;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.screen.preferences.pro.TrimProFragment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Wraps Google Play Billing for the TrimPlayer Pro purchase flow.
 *
 * Lifecycle:
 *   - {@link #get(Context)} returns a process-scoped singleton with an
 *     application Context so the BillingClient survives Pro-screen rotation.
 *   - {@link #connect()} starts the BillingClient connection; safe to call
 *     repeatedly (idempotent while connected, re-connects after a drop).
 *   - {@link #queryProductsAsync()} loads SKU details so the Pro screen can
 *     render Play-supplied localized prices instead of hardcoded strings.
 *   - {@link #launchPurchase(Activity, String)} starts the purchase flow.
 *   - {@link #queryPurchasesAsync()} restores entitlement on app start.
 *
 * Verification flow:
 *   purchase → onPurchasesUpdated → /api/v1/billing/verify → server mints
 *   Pro JWT + writes device_entitlements row → EntitlementStore.applyProToken
 *   on success, then acknowledgePurchase (mandatory within 3 days).
 *
 * Listeners: UI subscribes via {@link #addListener(Listener)} to learn about
 * SKU-details arrival, purchase success/failure, and connection state.
 * Dispatched on the main thread because BillingClient already calls back there.
 */
public final class TrimBillingManager {
    private static final String TAG = "TrimBillingManager";

    public interface Listener {
        /** Called whenever the SKU details cache is refreshed (success or
         *  empty). Inspect via {@link #getProductDetails(String)}. */
        default void onProductDetailsUpdated() { }

        /** Purchase + server verification both succeeded. Pro is now active. */
        default void onPurchaseAcknowledged(String productId) { }

        /** Anything went wrong — Play, network, server. {@code message} is
         *  user-presentable (already localized where possible). */
        default void onPurchaseFailed(String productId, String message) { }

        /** Billing isn't available on this device (no Play Store, old Play
         *  Services, etc.). Hide purchase UI accordingly. */
        default void onBillingUnavailable(String reason) { }
    }

    private static volatile TrimBillingManager instance;

    public static TrimBillingManager get(Context ctx) {
        TrimBillingManager local = instance;
        if (local == null) {
            synchronized (TrimBillingManager.class) {
                local = instance;
                if (local == null) {
                    instance = local = new TrimBillingManager(ctx.getApplicationContext());
                }
            }
        }
        return local;
    }

    private final Context appContext;
    private final BillingClient client;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    /** SKU → details cache. Populated by queryProductsAsync. */
    private final Map<String, ProductDetails> products = new HashMap<>();
    private volatile boolean connected = false;
    private volatile boolean unavailable = false;

    private TrimBillingManager(Context appContext) {
        this.appContext = appContext;
        this.client = BillingClient.newBuilder(appContext)
                .setListener(this::onPurchasesUpdated)
                .enablePendingPurchases(
                        com.android.billingclient.api.PendingPurchasesParams.newBuilder()
                                .enableOneTimeProducts()
                                .build())
                .build();
    }

    public void addListener(Listener l) {
        if (l != null) listeners.addIfAbsent(l);
    }

    public void removeListener(Listener l) {
        if (l != null) listeners.remove(l);
    }

    @Nullable
    public ProductDetails getProductDetails(String sku) {
        return products.get(sku);
    }

    public boolean isUnavailable() {
        return unavailable;
    }

    /** Start (or restart) the BillingClient connection. Safe to call from any
     *  state — if already connected, this is a no-op. After a successful
     *  connection we automatically refresh products and restore purchases. */
    @MainThread
    public void connect() {
        if (connected) {
            queryProductsAsync();
            queryPurchasesAsync();
            return;
        }
        if (unavailable) {
            // Don't keep retrying on a device that's already told us no.
            return;
        }
        client.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult result) {
                if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    connected = true;
                    queryProductsAsync();
                    queryPurchasesAsync();
                } else if (result.getResponseCode()
                        == BillingClient.BillingResponseCode.BILLING_UNAVAILABLE) {
                    unavailable = true;
                    notifyUnavailable("billing_unavailable");
                } else {
                    Log.w(TAG, "Billing setup failed: code=" + result.getResponseCode()
                            + " msg=" + result.getDebugMessage());
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                connected = false;
                // BillingClient will reconnect lazily on next API call; no
                // need to schedule retries here.
            }
        });
    }

    /** Fetch ProductDetails for our subscription SKUs and cache them.
     *  Pro screen reads from the cache to render localized prices. */
    public void queryProductsAsync() {
        if (!connected) return;
        List<QueryProductDetailsParams.Product> wanted = new ArrayList<>();
        wanted.add(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(TrimProFragment.SKU_MONTHLY)
                .setProductType(BillingClient.ProductType.SUBS)
                .build());
        wanted.add(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(TrimProFragment.SKU_MONTHLY_PLUS)
                .setProductType(BillingClient.ProductType.SUBS)
                .build());
        wanted.add(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(TrimProFragment.SKU_YEARLY)
                .setProductType(BillingClient.ProductType.SUBS)
                .build());
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(wanted)
                .build();
        client.queryProductDetailsAsync(params, new ProductDetailsResponseListener() {
            @Override
            public void onProductDetailsResponse(BillingResult result,
                                                 com.android.billingclient.api.QueryProductDetailsResult queryResult) {
                List<ProductDetails> details = queryResult.getProductDetailsList();
                if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                    Log.w(TAG, "queryProductDetailsAsync failed: code="
                            + result.getResponseCode() + " msg=" + result.getDebugMessage());
                    return;
                }
                products.clear();
                if (details != null) {
                    for (ProductDetails d : details) {
                        products.put(d.getProductId(), d);
                    }
                }
                for (Listener l : listeners) l.onProductDetailsUpdated();
            }
        });
    }

    /** Launch the purchase flow. Caller must have already verified that
     *  {@link #getProductDetails(String)} returns non-null for this SKU. */
    @MainThread
    public void launchPurchase(Activity activity, String sku) {
        ProductDetails details = products.get(sku);
        if (details == null) {
            notifyPurchaseFailed(sku, "Product not available yet, try again in a moment.");
            return;
        }
        List<ProductDetails.SubscriptionOfferDetails> offers = details.getSubscriptionOfferDetails();
        if (offers == null || offers.isEmpty()) {
            notifyPurchaseFailed(sku, "Subscription offer missing — contact support.");
            return;
        }
        // Use the first offer for now. When/if you ship multiple base plans
        // (e.g. intro $0.99 first month), pick the right one based on
        // eligibility — Play returns ineligible offers too.
        String offerToken = offers.get(0).getOfferToken();
        BillingFlowParams.ProductDetailsParams pdParams =
                BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(offerToken)
                        .build();
        BillingFlowParams flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(pdParams))
                .build();
        BillingResult res = client.launchBillingFlow(activity, flowParams);
        if (res.getResponseCode() != BillingClient.BillingResponseCode.OK) {
            notifyPurchaseFailed(sku, "Couldn't open purchase: " + res.getDebugMessage());
        }
    }

    /** Called by BillingClient when a purchase completes (or fails). */
    private void onPurchasesUpdated(BillingResult result, @Nullable List<Purchase> purchases) {
        int code = result.getResponseCode();
        if (code == BillingClient.BillingResponseCode.USER_CANCELED) {
            // No-op — user dismissed the sheet. Don't show an error.
            return;
        }
        if (code != BillingClient.BillingResponseCode.OK) {
            String sku = purchases != null && !purchases.isEmpty()
                    ? firstSku(purchases.get(0)) : "";
            notifyPurchaseFailed(sku, "Purchase failed: " + result.getDebugMessage());
            return;
        }
        if (purchases == null || purchases.isEmpty()) return;
        for (Purchase p : purchases) {
            handlePurchase(p);
        }
    }

    /** Common path for both fresh purchases and queryPurchasesAsync results.
     *  Server-verifies, then acknowledges. Idempotent — calling on an already-
     *  acknowledged purchase is a no-op on the server side (entitlement upsert)
     *  and we skip the acknowledge call. */
    private void handlePurchase(Purchase p) {
        if (p.getPurchaseState() != Purchase.PurchaseState.PURCHASED) {
            // Pending purchase (parental approval, slow card). Will fire again
            // through onPurchasesUpdated when it settles. No-op.
            return;
        }
        String sku = firstSku(p);
        String purchaseToken = p.getPurchaseToken();
        if (sku.isEmpty() || purchaseToken.isEmpty()) return;

        String clientId = UserPreferences.getOrCreateTrimClientId();
        TrimClient.getInstance().billingVerify(clientId, sku, purchaseToken).enqueue(
                new Callback<TrimClient.BillingVerifyResponse>() {
                    @Override
                    public void onResponse(Call<TrimClient.BillingVerifyResponse> call,
                                           Response<TrimClient.BillingVerifyResponse> resp) {
                        if (!resp.isSuccessful() || resp.body() == null) {
                            notifyPurchaseFailed(sku,
                                    "Couldn't verify your purchase (HTTP " + resp.code()
                                            + "). It will retry automatically.");
                            return;
                        }
                        TrimClient.BillingVerifyResponse body = resp.body();
                        long expiresMs = parseIsoToMs(body.expires_at);
                        EntitlementStore.get().applyProToken(
                                body.pro_token, expiresMs, body.source);
                        if (!p.isAcknowledged()) {
                            acknowledge(p, sku);
                        } else {
                            notifyPurchaseAcknowledged(sku);
                        }
                    }

                    @Override
                    public void onFailure(Call<TrimClient.BillingVerifyResponse> call, Throwable t) {
                        notifyPurchaseFailed(sku,
                                "Couldn't reach TrimBrain to verify your purchase. "
                                        + "It will retry automatically next time you open Pro.");
                    }
                });
    }

    private void acknowledge(Purchase p, String sku) {
        AcknowledgePurchaseParams params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(p.getPurchaseToken())
                .build();
        client.acknowledgePurchase(params, result -> {
            if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                notifyPurchaseAcknowledged(sku);
            } else {
                // Server already gave us Pro entitlement; we just couldn't
                // ack with Play. Google will refund the purchase if not
                // acknowledged within 3 days — surface the failure so the
                // user can re-open the Pro screen, which will retry.
                notifyPurchaseFailed(sku,
                        "Couldn't acknowledge purchase: " + result.getDebugMessage());
            }
        });
    }

    /** Fetch purchases the user already owns and re-run the verify path for
     *  each. Run on app start so a reinstall or "Restore purchases" tap
     *  recovers Pro status without re-paying. */
    public void queryPurchasesAsync() {
        if (!connected) return;
        QueryPurchasesParams params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build();
        client.queryPurchasesAsync(params, (result, purchases) -> {
            if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) return;
            for (Purchase p : purchases) handlePurchase(p);
        });
    }

    private static String firstSku(Purchase p) {
        List<String> ids = p.getProducts();
        return ids == null || ids.isEmpty() ? "" : ids.get(0);
    }

    /** Convert an ISO-8601 timestamp (with offset) to unix-ms. Returns 0 on
     *  parse failure so EntitlementStore treats expiry as unknown. */
    private static long parseIsoToMs(String iso) {
        if (iso == null || iso.isEmpty()) return 0;
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (Exception e) {
            try {
                // Some server libraries emit +00:00 instead of Z — both are RFC3339.
                return java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli();
            } catch (Exception e2) {
                Log.w(TAG, "Could not parse expires_at='" + iso + "'", e2);
                return 0;
            }
        }
    }

    // --- dispatch helpers ---

    private void notifyUnavailable(String reason) {
        for (Listener l : listeners) l.onBillingUnavailable(reason);
    }

    private void notifyPurchaseAcknowledged(String sku) {
        for (Listener l : listeners) l.onPurchaseAcknowledged(sku);
    }

    private void notifyPurchaseFailed(String sku, String message) {
        for (Listener l : listeners) l.onPurchaseFailed(sku, message);
    }
}
