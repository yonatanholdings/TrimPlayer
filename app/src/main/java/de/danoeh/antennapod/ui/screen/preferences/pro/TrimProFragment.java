package de.danoeh.antennapod.ui.screen.preferences.pro;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.snackbar.Snackbar;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.playback.service.trim.EntitlementStore;
import de.danoeh.antennapod.playback.service.trim.TrimClient;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.screen.preferences.PreferenceActivity;
import java.text.NumberFormat;
import java.util.Locale;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Settings → TrimPlayer Pro. Shows the current entitlement state, lists Pro
 * benefits, and offers the SKU purchase buttons.
 *
 * <p>Phase 1 (2026-05-19) ships the UI surface only. Play Billing wiring is
 * blocked on:
 *   - Android package name registered in Play Console (TBD)
 *   - Product IDs being created in Play Console (TBD)
 *   - Server-side {@code _verify_with_google} being un-stubbed
 * Each upgrade button currently shows a "coming soon" snackbar.
 *
 * <p>Tier surface (2026-05-19): v1 ships **monthly + yearly only**. The
 * Supporter tier ($50/yr) is implemented end-to-end (SKU constant, layout
 * elements, badge + thanks, Supporter Digest fetch/render, backend endpoint
 * contract) but hidden via {@link #SUPPORTER_TIER_ENABLED}. Flip that
 * constant to {@code true} to expose the tier without re-adding code.
 */
public class TrimProFragment extends Fragment {

    // TODO(pro/billing): replace with real Play Console product IDs once minted.
    // Listed here as constants so the billing wiring (next phase) has one place
    // to read them from. Lifetime tier was dropped 2026-05-19; replaced with
    // the Supporter tier ($50/yr — same Pro entitlement, donation-shaped price
    // with badge + thanks). See PRICING_PLAN.md §3.
    public static final String SKU_MONTHLY      = "trimplayer_pro_monthly";
    public static final String SKU_MONTHLY_PLUS = "trimplayer_pro_monthly_plus";
    public static final String SKU_YEARLY       = "trimplayer_pro_yearly";
    public static final String SKU_SUPPORTER    = "trimplayer_pro_supporter";

    /** Entitlement source string the backend will tag Supporter purchases with.
     *  Mirrored in EntitlementStore.Snapshot for badge rendering. The exact
     *  string is part of the wire contract — keep in sync with
     *  TrimBrain billing.py when the Play SKU → source mapping is implemented. */
    public static final String SOURCE_SUPPORTER = "play_supporter";

    /** Feature flag: Supporter tier is implemented end-to-end but hidden in v1
     *  per pricing-plan decision 2026-05-19. Flip to {@code true} to expose the
     *  $50 Supporter purchase button, the Supporter badge + thanks copy, and
     *  the monthly Supporter Digest card. Everything downstream of this flag
     *  (DTOs, endpoint, store source string, layout views) is preserved. */
    public static final boolean SUPPORTER_TIER_ENABLED = false;

    private EntitlementStore.Listener storeListener;
    private de.danoeh.antennapod.billing.TrimBillingManager.Listener billingListener;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.trim_pro_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        if (getActivity() instanceof PreferenceActivity) {
            androidx.appcompat.app.ActionBar ab =
                    ((PreferenceActivity) getActivity()).getSupportActionBar();
            if (ab != null) {
                ab.setTitle(R.string.trim_pro_title);
            }
        }

        MaterialButton monthly     = v.findViewById(R.id.trimProPriceMonthly);
        MaterialButton monthlyPlus = v.findViewById(R.id.trimProPriceMonthlyPlus);
        MaterialButton yearly      = v.findViewById(R.id.trimProPriceYearly);
        MaterialButton supporter   = v.findViewById(R.id.trimProPriceSupporter);
        monthly.setOnClickListener(b -> launchPurchase(SKU_MONTHLY));
        monthlyPlus.setOnClickListener(b -> launchPurchase(SKU_MONTHLY_PLUS));
        yearly.setOnClickListener(b -> launchPurchase(SKU_YEARLY));
        supporter.setOnClickListener(b -> launchPurchase(SKU_SUPPORTER));

        render(EntitlementStore.get().snapshot(), v);
        loadImpact(v);

        storeListener = snapshot -> {
            View view = getView();
            if (view == null) {
                return;
            }
            view.post(() -> render(snapshot, view));
        };
        EntitlementStore.get().addListener(storeListener);

        de.danoeh.antennapod.billing.TrimBillingManager billing =
                de.danoeh.antennapod.billing.TrimBillingManager.get(requireContext());
        billingListener = new de.danoeh.antennapod.billing.TrimBillingManager.Listener() {
            @Override public void onProductDetailsUpdated() {
                View view = getView();
                if (view != null) {
                    view.post(() -> applyPriceLabels(view));
                }
            }

            @Override public void onPurchaseAcknowledged(String productId) {
                View view = getView();
                if (view != null) {
                    Snackbar.make(view, R.string.trim_pro_purchase_success,
                            Snackbar.LENGTH_LONG).show();
                }
                // EntitlementStore already updated by TrimBillingManager
                // before this listener fires — render() will re-pull via the
                // EntitlementStore listener.
            }

            @Override public void onPurchaseFailed(String productId, String message) {
                View view = getView();
                if (view != null) {
                    Snackbar.make(view, message, Snackbar.LENGTH_LONG).show();
                }
            }

            @Override public void onBillingUnavailable(String reason) {
                View view = getView();
                if (view != null) {
                    view.post(() -> hidePurchaseButtons(view));
                }
            }
        };
        billing.addListener(billingListener);
        billing.connect();
    }

    @Override
    public void onDestroyView() {
        if (storeListener != null) {
            EntitlementStore.get().removeListener(storeListener);
            storeListener = null;
        }
        if (billingListener != null) {
            de.danoeh.antennapod.billing.TrimBillingManager.get(requireContext())
                    .removeListener(billingListener);
            billingListener = null;
        }
        super.onDestroyView();
    }

    private void render(EntitlementStore.Snapshot s, View root) {
        MaterialCardView activeCard       = root.findViewById(R.id.trimProActiveCard);
        TextView activeSubtitle           = root.findViewById(R.id.trimProActiveSubtitle);
        TextView supporterBadge           = root.findViewById(R.id.trimProSupporterBadge);
        TextView supporterThanks          = root.findViewById(R.id.trimProSupporterThanks);
        TextView pricingTitle             = root.findViewById(R.id.trimProPricingTitle);
        MaterialButton monthly            = root.findViewById(R.id.trimProPriceMonthly);
        MaterialButton monthlyPlus        = root.findViewById(R.id.trimProPriceMonthlyPlus);
        MaterialButton yearly             = root.findViewById(R.id.trimProPriceYearly);
        MaterialButton supporter          = root.findViewById(R.id.trimProPriceSupporter);
        TextView supporterExplainer       = root.findViewById(R.id.trimProSupporterExplainer);
        TextView supportNote              = root.findViewById(R.id.trimProSupportNote);

        boolean isPro = s != null && s.isPro();
        boolean isSupporter = isPro && SOURCE_SUPPORTER.equals(s.source);
        activeCard.setVisibility(isPro ? View.VISIBLE : View.GONE);
        // Hide pricing if user is already Pro — keeps the screen honest about
        // what's still available to purchase.
        int pricingVis = isPro ? View.GONE : View.VISIBLE;
        pricingTitle.setVisibility(pricingVis);
        monthly.setVisibility(pricingVis);
        monthlyPlus.setVisibility(pricingVis);
        yearly.setVisibility(pricingVis);
        supportNote.setVisibility(pricingVis);
        // Supporter purchase surface gated on the feature flag — defensive
        // belt-and-suspenders with the layout's hardcoded gone. When the flag
        // flips on, this line restores normal show-when-not-Pro behavior.
        int supporterPurchaseVis = SUPPORTER_TIER_ENABLED ? pricingVis : View.GONE;
        supporter.setVisibility(supporterPurchaseVis);
        supporterExplainer.setVisibility(supporterPurchaseVis);

        // Badge + thanks render whenever source == play_supporter — useful for
        // manually-granted entitlements during testing, and for the day the
        // flag flips on. Pre-launch, no real users have this source.
        supporterBadge.setVisibility(isSupporter ? View.VISIBLE : View.GONE);
        supporterThanks.setVisibility(isSupporter ? View.VISIBLE : View.GONE);

        if (isPro) {
            String src = s.source != null ? s.source : "?";
            activeSubtitle.setText(getString(R.string.trim_pro_status_active_subtitle, src));
        }

        // Supporter Digest card — fetch only when source confirms Supporter
        // tier. Other Pro tiers (monthly/yearly subscription) don't see this.
        MaterialCardView digestCard = root.findViewById(R.id.trimProDigestCard);
        if (isSupporter) {
            digestCard.setVisibility(View.VISIBLE);
            fetchSupporterDigest(root);
        } else {
            digestCard.setVisibility(View.GONE);
        }
    }

    /** Show the listener's real, all-time trim impact — the time TrimPlayer has
     *  saved them by skipping intros, ads, outros, and silence. Everyone sees
     *  this; it's the "here's the value you're getting" case for chipping in.
     *  Read off the main thread — {@link DBReader#getSkipStatistics()} hits SQLite. */
    private void loadImpact(View root) {
        new Thread(() -> {
            DBReader.SkipStatistics stats = DBReader.getSkipStatistics();
            long savedMs = stats.introMs + stats.adMs + stats.outroMs + stats.silenceMs;
            root.post(() -> {
                if (!isAdded()) {
                    return;
                }
                TextView impact = root.findViewById(R.id.trimProImpact);
                if (impact == null) {
                    return;
                }
                impact.setText(savedMs >= 60_000L
                        ? getString(R.string.trim_pro_impact, formatDuration(savedMs))
                        : getString(R.string.trim_pro_impact_empty));
            });
        }, "trim-pro-impact").start();
    }

    /** Humanize a millisecond duration as e.g. "3 hours 12 minutes" or "12 minutes".
     *  Minutes-granularity is enough for an impact figure; callers gate sub-minute
     *  totals to the empty-state copy. */
    private String formatDuration(long ms) {
        long totalMinutes = ms / 60_000L;
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        StringBuilder sb = new StringBuilder();
        if (hours > 0) {
            sb.append(hours).append(hours == 1 ? " hour" : " hours");
            if (minutes > 0) {
                sb.append(' ').append(minutes).append(minutes == 1 ? " minute" : " minutes");
            }
        } else {
            sb.append(minutes).append(minutes == 1 ? " minute" : " minutes");
        }
        return sb.toString();
    }

    /** Fetch the Supporter Digest and render. Three terminal states:
     *   - success         → content block populated
     *   - empty (404/204) → "your first digest arrives at end of month"
     *   - other failure   → error message
     *
     *  <p>We don't cache the response on disk yet — the digest changes monthly,
     *  the call is small, and a stale-cache layer adds complexity for almost
     *  no benefit at this scale. Add SharedPreferences caching if the call
     *  becomes a noticeable cost. */
    private void fetchSupporterDigest(View root) {
        TextView message  = root.findViewById(R.id.trimProDigestMessage);
        LinearLayout body = root.findViewById(R.id.trimProDigestContent);
        message.setVisibility(View.VISIBLE);
        message.setText(R.string.trim_digest_loading);
        body.setVisibility(View.GONE);

        String clientId = UserPreferences.getOrCreateTrimClientId();
        String proToken = UserPreferences.getTrimProToken();
        TrimClient.getInstance().getSupporterDigest(clientId, proToken)
                .enqueue(new Callback<TrimClient.SupporterDigest>() {
                    @Override
                    public void onResponse(Call<TrimClient.SupporterDigest> call,
                                           Response<TrimClient.SupporterDigest> resp) {
                        View v = getView();
                        if (v == null) {
                            return;
                        }
                        // 404 / 204 → endpoint not yet built or no digest this
                        // period. Surface as "empty" so the card stays calm.
                        if (resp.code() == 404 || resp.code() == 204
                                || resp.body() == null) {
                            showDigestMessage(v, R.string.trim_digest_empty);
                            return;
                        }
                        if (!resp.isSuccessful()) {
                            showDigestMessage(v, R.string.trim_digest_error);
                            return;
                        }
                        renderDigest(v, resp.body());
                    }

                    @Override
                    public void onFailure(Call<TrimClient.SupporterDigest> call, Throwable t) {
                        View v = getView();
                        if (v == null) {
                            return;
                        }
                        showDigestMessage(v, R.string.trim_digest_error);
                    }
                });
    }

    private void showDigestMessage(View root, int stringRes) {
        TextView message  = root.findViewById(R.id.trimProDigestMessage);
        LinearLayout body = root.findViewById(R.id.trimProDigestContent);
        message.setVisibility(View.VISIBLE);
        message.setText(stringRes);
        body.setVisibility(View.GONE);
    }

    private void renderDigest(View root, TrimClient.SupporterDigest d) {
        TextView message     = root.findViewById(R.id.trimProDigestMessage);
        LinearLayout body    = root.findViewById(R.id.trimProDigestContent);
        TextView period      = root.findViewById(R.id.trimProDigestPeriod);
        TextView totalMin    = root.findViewById(R.id.trimProDigestTotalMinutes);
        TextView change      = root.findViewById(R.id.trimProDigestChange);
        TextView avgListener = root.findViewById(R.id.trimProDigestAvgListener);
        TextView yourStats   = root.findViewById(R.id.trimProDigestYourStats);
        LinearLayout topPods = root.findViewById(R.id.trimProDigestTopPodcasts);
        TextView funding     = root.findViewById(R.id.trimProDigestFunding);
        LinearLayout shipped = root.findViewById(R.id.trimProDigestShipped);
        LinearLayout nextUp  = root.findViewById(R.id.trimProDigestNext);
        TextView note        = root.findViewById(R.id.trimProDigestNote);

        message.setVisibility(View.GONE);
        body.setVisibility(View.VISIBLE);

        period.setText(d.period != null ? getString(R.string.trim_digest_period, d.period) : "");

        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.getDefault());
        NumberFormat usd = NumberFormat.getCurrencyInstance(Locale.US);

        TextView cumulative       = root.findViewById(R.id.trimProDigestCumulative);
        TextView skipBreakdown    = root.findViewById(R.id.trimProDigestSkipBreakdown);
        TextView accuracy         = root.findViewById(R.id.trimProDigestAccuracy);
        TextView catalogPodcasts  = root.findViewById(R.id.trimProDigestCatalogPodcasts);
        TextView catalogSegments  = root.findViewById(R.id.trimProDigestCatalogSegments);

        TrimClient.CommunityPulse c = d.community;
        if (c != null) {
            if (c.cumulative != null && c.cumulative.minutes_saved_all_time != null) {
                cumulative.setVisibility(View.VISIBLE);
                cumulative.setText(getString(R.string.trim_digest_cumulative,
                        nf.format(c.cumulative.minutes_saved_all_time)));
            } else {
                cumulative.setVisibility(View.GONE);
            }
            if (c.total_minutes_saved != null) {
                totalMin.setText(getString(R.string.trim_digest_total_minutes,
                        nf.format(c.total_minutes_saved)));
            }
            if (c.pct_change_vs_prev != null) {
                int pct = (int) Math.round(c.pct_change_vs_prev * 100);
                String sign = pct >= 0 ? "+" : "";
                change.setVisibility(View.VISIBLE);
                change.setText(getString(R.string.trim_digest_change_vs_prev, sign + pct + "%"));
            } else {
                change.setVisibility(View.GONE);
            }
            if (c.avg_listener_minutes_saved != null) {
                avgListener.setText(getString(R.string.trim_digest_avg_listener,
                        nf.format(c.avg_listener_minutes_saved)));
            }
            if (c.your_minutes_saved != null && c.your_percentile != null) {
                yourStats.setVisibility(View.VISIBLE);
                yourStats.setText(getString(R.string.trim_digest_your_stats,
                        nf.format(c.your_minutes_saved), c.your_percentile));
            } else {
                yourStats.setVisibility(View.GONE);
            }
            renderSkipBreakdown(skipBreakdown, c.skip_breakdown);
            renderAccuracy(accuracy, nf, c.accuracy);
            renderCatalog(catalogPodcasts, catalogSegments, nf, c.catalog);
            renderTopPodcasts(topPods, c.top_podcasts);
        }

        TrimClient.DevUpdate dev = d.dev;
        if (dev != null) {
            String fund = String.format(Locale.getDefault(),
                    getString(R.string.trim_digest_funding_line),
                    formatUsd(usd, dev.supporter_income_usd),
                    formatUsd(usd, dev.server_cost_usd),
                    formatUsd(usd, dev.net_to_dev_usd));
            funding.setText(fund);
            renderBullets(shipped, dev.shipped);
            renderBullets(nextUp, dev.next);
            if (dev.note != null && !dev.note.isEmpty()) {
                note.setVisibility(View.VISIBLE);
                note.setText(dev.note);
            } else {
                note.setVisibility(View.GONE);
            }
        }
    }

    private void renderSkipBreakdown(TextView v, TrimClient.SkipBreakdown b) {
        if (b == null) { v.setVisibility(View.GONE); return; }
        long intros = b.intros_count != null ? b.intros_count : 0;
        long ads    = b.ads_count    != null ? b.ads_count    : 0;
        long outros = b.outros_count != null ? b.outros_count : 0;
        long total  = intros + ads + outros;
        if (total <= 0) { v.setVisibility(View.GONE); return; }
        // Rounded percentages; sum may be 99 or 101 due to rounding. Acceptable
        // — surfacing precise floats here would feel false-precise.
        int adsPct    = (int) Math.round(100.0 * ads    / total);
        int introsPct = (int) Math.round(100.0 * intros / total);
        int outrosPct = (int) Math.round(100.0 * outros / total);
        v.setVisibility(View.VISIBLE);
        v.setText(getString(R.string.trim_digest_skip_breakdown, adsPct, introsPct, outrosPct));
    }

    private void renderAccuracy(TextView v, NumberFormat nf, TrimClient.AlgorithmAccuracy a) {
        if (a == null || a.accuracy_pct == null) { v.setVisibility(View.GONE); return; }
        int pct = (int) Math.round(a.accuracy_pct * 100);
        String hits   = a.hits   != null ? nf.format(a.hits)   : "?";
        String misses = a.misses != null ? nf.format(a.misses) : "?";
        v.setVisibility(View.VISIBLE);
        v.setText(getString(R.string.trim_digest_accuracy, pct, hits, misses));
    }

    private void renderCatalog(TextView podcastsView, TextView segmentsView,
                               NumberFormat nf, TrimClient.CatalogGrowth g) {
        if (g == null) {
            podcastsView.setVisibility(View.GONE);
            segmentsView.setVisibility(View.GONE);
            return;
        }
        if (g.podcasts_known != null && g.podcasts_added_this_period != null) {
            String delta = (g.podcasts_added_this_period >= 0 ? "+" : "")
                    + nf.format(g.podcasts_added_this_period);
            podcastsView.setVisibility(View.VISIBLE);
            podcastsView.setText(getString(R.string.trim_digest_catalog_podcasts,
                    delta, nf.format(g.podcasts_known)));
        } else {
            podcastsView.setVisibility(View.GONE);
        }
        if (g.canonical_segments_known != null
                && g.canonical_segments_added_this_period != null) {
            String delta = (g.canonical_segments_added_this_period >= 0 ? "+" : "")
                    + nf.format(g.canonical_segments_added_this_period);
            segmentsView.setVisibility(View.VISIBLE);
            segmentsView.setText(getString(R.string.trim_digest_catalog_segments,
                    delta, nf.format(g.canonical_segments_known)));
        } else {
            segmentsView.setVisibility(View.GONE);
        }
    }

    private void renderTopPodcasts(LinearLayout container,
                                   java.util.List<TrimClient.TopPodcast> items) {
        container.removeAllViews();
        if (items == null) {
            return;
        }
        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.getDefault());
        for (TrimClient.TopPodcast p : items) {
            TextView t = new TextView(container.getContext());
            String mins = p.minutes_saved != null ? nf.format(p.minutes_saved) + " min" : "";
            t.setText("· " + (p.title != null ? p.title : "?") + "  " + mins);
            container.addView(t);
        }
    }

    private void renderBullets(LinearLayout container, java.util.List<String> items) {
        container.removeAllViews();
        if (items == null) {
            return;
        }
        for (String item : items) {
            TextView t = new TextView(container.getContext());
            t.setText("· " + item);
            container.addView(t);
        }
    }

    private static String formatUsd(NumberFormat fmt, Double v) {
        if (v == null) return "—";
        return fmt.format(v);
    }

    /** Kick off Play Billing for {@code sku}. Errors are surfaced via the
     *  TrimBillingManager listener (snackbar in this fragment). */
    private void launchPurchase(String sku) {
        if (getActivity() == null) {
            return;
        }
        de.danoeh.antennapod.billing.TrimBillingManager billing =
                de.danoeh.antennapod.billing.TrimBillingManager.get(requireContext());
        if (billing.isUnavailable()) {
            Snackbar.make(requireView(), R.string.trim_pro_billing_unavailable,
                    Snackbar.LENGTH_LONG).show();
            return;
        }
        billing.launchPurchase(requireActivity(), sku);
    }

    /** When Play returns ProductDetails, swap the hardcoded fallback strings
     *  ("Monthly · $2.99") for Play-supplied localized prices. */
    private void applyPriceLabels(View root) {
        de.danoeh.antennapod.billing.TrimBillingManager billing =
                de.danoeh.antennapod.billing.TrimBillingManager.get(requireContext());
        MaterialButton monthly     = root.findViewById(R.id.trimProPriceMonthly);
        MaterialButton monthlyPlus = root.findViewById(R.id.trimProPriceMonthlyPlus);
        MaterialButton yearly      = root.findViewById(R.id.trimProPriceYearly);
        applyOnePrice(monthly, billing.getProductDetails(SKU_MONTHLY),
                R.string.trim_pro_price_monthly_label);
        applyOnePrice(monthlyPlus, billing.getProductDetails(SKU_MONTHLY_PLUS),
                R.string.trim_pro_price_monthly_plus_label);
        applyOnePrice(yearly,  billing.getProductDetails(SKU_YEARLY),
                R.string.trim_pro_price_yearly_label);
    }

    private void applyOnePrice(MaterialButton btn,
                               com.android.billingclient.api.ProductDetails details,
                               int labelRes) {
        if (details == null) return;  // keep the fallback hardcoded label
        java.util.List<com.android.billingclient.api.ProductDetails.SubscriptionOfferDetails> offers =
                details.getSubscriptionOfferDetails();
        if (offers == null || offers.isEmpty()) {
            return;
        }
        java.util.List<com.android.billingclient.api.ProductDetails.PricingPhase> phases =
                offers.get(0).getPricingPhases().getPricingPhaseList();
        if (phases == null || phases.isEmpty()) {
            return;
        }
        String formatted = phases.get(0).getFormattedPrice();  // already localized
        btn.setText(getString(labelRes, formatted));
    }

    private void hidePurchaseButtons(View root) {
        root.findViewById(R.id.trimProPriceMonthly).setVisibility(View.GONE);
        root.findViewById(R.id.trimProPriceMonthlyPlus).setVisibility(View.GONE);
        root.findViewById(R.id.trimProPriceYearly).setVisibility(View.GONE);
        root.findViewById(R.id.trimProPriceSupporter).setVisibility(View.GONE);
        root.findViewById(R.id.trimProSupporterExplainer).setVisibility(View.GONE);
        // Still show the pricing-title so users know what's missing; the
        // empty-state message will read from R.string.trim_pro_billing_unavailable.
        TextView title = root.findViewById(R.id.trimProPricingTitle);
        title.setText(R.string.trim_pro_billing_unavailable);
    }
}
