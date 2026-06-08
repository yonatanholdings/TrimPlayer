package de.danoeh.antennapod.onboarding;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.GetContent;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.greenrobot.eventbus.EventBus;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.event.AnalyticsEvent;
import de.danoeh.antennapod.importflow.ImportFlowController;
import de.danoeh.antennapod.migration.SpotifyMigrationActivity;
import de.danoeh.antennapod.ui.common.ThemeSwitcher;

/**
 * First-run "Bring your podcasts with you" screen. Surfaces the import paths that
 * otherwise live deep in Settings, at the one moment they matter most — before a
 * new user hits an empty library and bounces. Launched once from
 * {@code MainActivity.checkFirstLaunch()} (and re-openable from Settings); skippable.
 *
 * <p>The file-based buttons open the system picker and hand the result to the shared
 * {@link ImportFlowController}, which auto-detects the type — so the label is per-app
 * recognition, not a separate code path. When an import is enqueued the controller
 * calls {@link #onImportEnqueued}: instead of silently finishing, we show a success
 * screen that lands the auto-trim promise at the emotional peak, then the CTA finishes
 * to Home where MainActivity's background-import banner shows live progress.
 */
public class OnboardingActivity extends AppCompatActivity implements ImportFlowController.ImportHost {

    private ImportFlowController importController;
    /** Detected source of the import currently celebrated on the success screen, for
     *  the CTA analytics. */
    private String successSource = "unknown";
    @Nullable private AnimatorSet trimAnimator;
    private boolean animationStopped;

    private final ActivityResultLauncher<String> pickFileLauncher =
            registerForActivityResult(new GetContent(), this::onFilePicked);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setTheme(ThemeSwitcher.getNoTitleTheme(this));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.onboarding_activity);

        // preferMergeForFreshLibrary = true: a .db onto the new user's empty library
        // merges (online re-subscribe + refresh, no restart) instead of full-restore.
        importController = new ImportFlowController(this, this, true);
        EventBus.getDefault().post(AnalyticsEvent.onboardingImportShown());

        findViewById(R.id.onboarding_spotify_button).setOnClickListener(v -> {
            EventBus.getDefault().post(AnalyticsEvent.onboardingImportChoice("spotify"));
            armFirstPlayNudge("spotify");
            startActivity(new Intent(this, SpotifyMigrationActivity.class));
            finish();
        });
        findViewById(R.id.onboarding_btn_podcast_addict).setOnClickListener(
                v -> pickFileForChoice("podcast_addict"));
        findViewById(R.id.onboarding_btn_antennapod).setOnClickListener(
                v -> pickFileForChoice("antennapod"));
        findViewById(R.id.onboarding_btn_portcast).setOnClickListener(
                v -> pickFileForChoice("portcast"));
        findViewById(R.id.onboarding_btn_opml).setOnClickListener(
                v -> pickFileForChoice("opml"));
        findViewById(R.id.onboarding_start_fresh_button).setOnClickListener(v -> {
            EventBus.getDefault().post(AnalyticsEvent.onboardingImportChoice("skip"));
            finish();
        });
        findViewById(R.id.onboarding_success_cta_button).setOnClickListener(v -> {
            EventBus.getDefault().post(AnalyticsEvent.onboardingSuccessCtaTapped(successSource));
            finish();
        });
    }

    private void pickFileForChoice(String choice) {
        EventBus.getDefault().post(AnalyticsEvent.onboardingImportChoice(choice));
        pickFileLauncher.launch("*/*");
    }

    private void onFilePicked(@Nullable Uri uri) {
        // Null = user backed out of the picker; stay on onboarding so they can retry.
        if (uri != null) {
            importController.route(uri);
        }
    }

    // ── ImportFlowController.ImportHost ──────────────────────────────────────

    /** Remember that onboarding started an import, so Home can offer a one-time
     *  "press play" nudge once the imported episodes materialize. */
    private void armFirstPlayNudge(String source) {
        getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE).edit()
                .putBoolean(MainActivity.PREF_PENDING_FIRST_PLAY, true)
                .putString(MainActivity.PREF_PENDING_FIRST_PLAY_SOURCE, source)
                .apply();
    }

    @Override
    public void onImportEnqueued(String source, int subscriptionsCount, boolean broughtHistory) {
        successSource = source != null ? source : "unknown";
        armFirstPlayNudge(successSource);
        EventBus.getDefault().post(
                AnalyticsEvent.onboardingImportSucceeded(successSource, subscriptionsCount));

        ((TextView) findViewById(R.id.onboarding_success_body)).setText(broughtHistory
                ? R.string.onboarding_success_body_history
                : R.string.onboarding_success_body_subscriptions);
        findViewById(R.id.onboarding_picker_container).setVisibility(View.GONE);
        findViewById(R.id.onboarding_success_container).setVisibility(View.VISIBLE);
        startTrimAnimation();
    }

    /**
     * The "snip": loops the episode bar's intro / ad / outro segments collapsing to
     * nothing so the bar visibly contracts — showing, not just telling, that we cut
     * the boring parts. Started only once the success container is visible (widths
     * are 0 while it's GONE).
     */
    private void startTrimAnimation() {
        View bar = findViewById(R.id.onboarding_trim_bar);
        View intro = findViewById(R.id.seg_intro);
        View ad = findViewById(R.id.seg_ad);
        View outro = findViewById(R.id.seg_outro);
        bar.post(() -> {
            if (animationStopped) {
                return;
            }
            loopTrim(intro, ad, outro, intro.getWidth(), ad.getWidth(), outro.getWidth());
        });
    }

    private void loopTrim(View intro, View ad, View outro, int wIntro, int wAd, int wOutro) {
        if (animationStopped || isFinishing()) {
            return;
        }
        setViewWidth(intro, wIntro);
        setViewWidth(ad, wAd);
        setViewWidth(outro, wOutro);

        ValueAnimator cutIntro = collapse(intro, wIntro);
        cutIntro.setStartDelay(700);
        ValueAnimator cutAd = collapse(ad, wAd);
        cutAd.setStartDelay(1200);
        ValueAnimator cutOutro = collapse(outro, wOutro);
        cutOutro.setStartDelay(1700);

        AnimatorSet set = new AnimatorSet();
        set.playTogether(cutIntro, cutAd, cutOutro);
        set.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (animationStopped || isFinishing()) {
                    return;
                }
                // Hold the contracted bar a beat, then reset and loop.
                outro.postDelayed(() -> loopTrim(intro, ad, outro, wIntro, wAd, wOutro), 1300);
            }
        });
        trimAnimator = set;
        set.start();
    }

    private ValueAnimator collapse(View v, int fromPx) {
        ValueAnimator va = ValueAnimator.ofInt(fromPx, 0);
        va.setDuration(450);
        va.addUpdateListener(anim -> setViewWidth(v, (int) anim.getAnimatedValue()));
        return va;
    }

    private static void setViewWidth(View v, int px) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        lp.width = px;
        v.setLayoutParams(lp);
    }

    @Override
    public void onImportHandedOff() {
        // OPML opens its own selection screen; arm the nudge so Home still offers a
        // first play once those subscriptions' episodes land, then dismiss.
        armFirstPlayNudge("opml");
        finish();
    }

    @Override
    protected void onStop() {
        super.onStop();
        animationStopped = true;
        if (trimAnimator != null) {
            trimAnimator.cancel();
        }
        if (importController != null) {
            importController.dispose();
        }
    }
}
