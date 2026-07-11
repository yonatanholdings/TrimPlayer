package de.danoeh.antennapod.ui.statistics;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.format.DateUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ShareCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.chip.ChipGroup;

import de.danoeh.antennapod.net.common.CommunityImpactClient;
import de.danoeh.antennapod.net.common.CommunityImpactClient.CommunityImpact;
import de.danoeh.antennapod.net.common.CommunityImpactClient.Window;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.statistics.editorial.EditorialTheme;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Community Impact: the anonymous, pooled trim-impact of all TrimPlayer
 * listeners (collective hero + breakdown, all-time) plus a tenure-fair "you vs
 * the community" comparison over a user-selectable trailing window
 * (7d/30d/90d/1y/All), and a shareable impact card.
 *
 * <p>Styled with the editorial design system ({@link EditorialTheme}) to match
 * the statistics suite. Community numbers come from {@link CommunityImpactClient}
 * (cached, then refreshed); the user's own numbers come from local
 * {@link DBReader} skip breakdowns. See {@code docs/community-impact-spec.md}.
 */
public class CommunityImpactFragment extends Fragment {
    private static final String TAG = "CommunityImpact";
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final String DEFAULT_WINDOW = "30d";
    private static final int SHARE_SIZE = 1080;
    private static final String SHARE_URL =
            "https://trimplayer.com/impact?utm_source=community_share&utm_medium=social";

    private View contentContainer;
    private View emptyState;
    private TextView heroCaption;
    private TextView heroSentence;
    private TextView compareWindowCaption;
    private View shareButton;
    private LinearLayout compareContainer;
    private View breakdownSection;
    private TextView breakdownCaption;
    private LinearLayout breakdownContainer;
    private TextView contributionValue;
    private TextView footerUpdated;
    private ChipGroup windowChips;

    @Nullable private CommunityImpact community;
    /** Local per-window skip breakdowns, keyed by chip label (incl. "All"). */
    @Nullable private Map<String, DBReader.SkipBreakdown> localByWindow;
    /** The user's real average listening speed; 0 until the DB read returns (or
     *  when there isn't enough history yet). */
    private float localAvgSpeed;
    private String selectedWindow = DEFAULT_WINDOW;
    @Nullable private Disposable loadDisposable;
    @Nullable private Disposable shareDisposable;
    @Nullable private ValueAnimator heroAnimator;
    @Nullable private String lastHeroKey;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_community_impact, container, false);
        contentContainer  = root.findViewById(R.id.content_container);
        emptyState        = root.findViewById(R.id.empty_state);
        heroCaption       = root.findViewById(R.id.hero_caption);
        heroSentence      = root.findViewById(R.id.hero_sentence);
        compareWindowCaption = root.findViewById(R.id.compare_window_caption);
        shareButton       = root.findViewById(R.id.share_button);
        compareContainer  = root.findViewById(R.id.compare_container);
        breakdownSection  = root.findViewById(R.id.breakdown_section);
        breakdownCaption  = root.findViewById(R.id.breakdown_caption);
        breakdownContainer = root.findViewById(R.id.breakdown_container);
        contributionValue = root.findViewById(R.id.contribution_value);
        footerUpdated     = root.findViewById(R.id.footer_updated);
        windowChips       = root.findViewById(R.id.window_chips);

        Context ctx = requireContext();
        Typeface serif = EditorialTheme.getSerif(ctx);
        heroSentence.setTypeface(serif);
        contributionValue.setTypeface(serif);
        shareButton.setEnabled(false);
        shareButton.setOnClickListener(v -> shareImpact());

        windowChips.setOnCheckedStateChangeListener((group, checkedIds) -> {
            String label = labelForChip(group.getCheckedChipId());
            if (label != null) {
                selectedWindow = label;
                // The window now scopes the hero too, so re-render the whole screen.
                render();
            }
        });

        // Paint last-known community data immediately (local "you" values fill in
        // once the DB read returns), then refresh both.
        community = CommunityImpactClient.cached();
        if (community != null) {
            render();
        }
        loadData();
        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        // The host (PreferenceActivity) otherwise leaves the toolbar on "Settings".
        if (getActivity() instanceof AppCompatActivity) {
            ActionBar ab = ((AppCompatActivity) getActivity()).getSupportActionBar();
            if (ab != null) {
                ab.setTitle(R.string.community_impact_title);
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (loadDisposable != null) {
            loadDisposable.dispose();
            loadDisposable = null;
        }
        if (shareDisposable != null) {
            shareDisposable.dispose();
            shareDisposable = null;
        }
        if (heroAnimator != null) {
            heroAnimator.cancel();
            heroAnimator = null;
        }
    }

    private void loadData() {
        loadDisposable = Single.fromCallable(() -> {
            Map<String, DBReader.SkipBreakdown> local = computeLocalWindows();
            // The user's real average listening speed — read off the main thread
            // alongside the skip breakdowns (both hit SQLite).
            float speed = DBReader.getAveragePlaybackSpeed();
            CommunityImpact fresh = CommunityImpactClient.fetchSync();  // null on failure
            return new Loaded(local, speed, fresh);
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(loaded -> {
                    localByWindow = loaded.local;
                    localAvgSpeed = loaded.avgSpeed;
                    if (loaded.community != null) {
                        community = loaded.community;
                    }
                    render();
                }, error -> render());
    }

    private Map<String, DBReader.SkipBreakdown> computeLocalWindows() {
        long now = System.currentTimeMillis();
        Map<String, DBReader.SkipBreakdown> map = new HashMap<>();
        map.put("7d",  DBReader.getSkipBreakdown(now - 7 * DAY_MS, now));
        map.put("30d", DBReader.getSkipBreakdown(now - 30 * DAY_MS, now));
        map.put("90d", DBReader.getSkipBreakdown(now - 90 * DAY_MS, now));
        map.put("1y",  DBReader.getSkipBreakdown(now - 365 * DAY_MS, now));
        map.put("All", DBReader.getSkipBreakdown(0, now));
        return map;
    }

    private void render() {
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        DBReader.SkipBreakdown localAll = localByWindow != null ? localByWindow.get("All") : null;
        // The hero is the sum of the five buckets we actually display — our real
        // reclaimed total — not the backend's separate total_ms field.
        long communityTotal = community != null ? communityReclaimed(community) : 0;
        boolean communityEmpty = communityTotal <= 0;
        boolean localEmpty = localAll == null || localAll.totalMs <= 0;
        if (communityEmpty && localEmpty) {
            emptyState.setVisibility(View.VISIBLE);
            contentContainer.setVisibility(View.GONE);
            return;
        }
        emptyState.setVisibility(View.GONE);
        contentContainer.setVisibility(View.VISIBLE);

        int accent = EditorialTheme.vermilion(ctx);
        long yourTotal = localAll != null ? localAll.totalMs : 0;
        if (community != null && communityTotal > 0) {
            // Hero: one sentence, the two figures emphasized, scoped to the chip
            // window — "1,254 listeners saved 2 months of ads, silence and filler."
            heroCaption.setText(heroCaptionText(ctx, false));
            String count = String.format(Locale.getDefault(), "%,d",
                    selectedWindowContributors());
            String dur = relatablePhrase(selectedWindowReclaimed());
            setHero(buildHeroSentence(R.string.community_impact_hero_sentence,
                    accent, count, dur));
            breakdownSection.setVisibility(View.VISIBLE);
            breakdownCaption.setText(windowSuffix());
            renderBreakdown(ctx, selectedWindowBuckets());
        } else {
            // No community data yet: personal sentence (your windowed total only).
            heroCaption.setText(heroCaptionText(ctx, true));
            DBReader.SkipBreakdown you = localByWindow != null
                    ? localByWindow.get(selectedWindow) : null;
            String dur = relatablePhrase(you != null ? you.totalMs : 0);
            setHero(buildHeroSentence(R.string.community_impact_hero_sentence_personal,
                    accent, dur));
            breakdownSection.setVisibility(View.GONE);
            breakdownContainer.removeAllViews();
        }

        // Shareable whenever there's anything to boast — all-time community OR your
        // own all-time cut (the share card is all-time, so use yourTotal here).
        shareButton.setEnabled(communityTotal > 0 || yourTotal > 0);
        // "Your contribution" follows the chip window too.
        DBReader.SkipBreakdown yourWin = localByWindow != null
                ? localByWindow.get(selectedWindow) : null;
        long yourWindowTotal = yourWin != null ? yourWin.totalMs : 0;
        String yourWindowStr = formatHumanDuration(yourWindowTotal);
        contributionValue.setText(spanColor(
                getString(R.string.community_impact_your_total, yourWindowStr),
                yourWindowStr, accent));

        String updated = formatUpdated(ctx, community != null ? community.asOf : null);
        if (updated != null) {
            footerUpdated.setText(updated);
            footerUpdated.setVisibility(View.VISIBLE);
        } else {
            footerUpdated.setVisibility(View.GONE);
        }

        renderComparison();
    }

    /** Build the hero sentence from a template, emphasizing each supplied figure
     *  (large serif + accent) so it reads as a sentence but the numbers carry. */
    private CharSequence buildHeroSentence(int templateRes, int accent, String... figures) {
        String sentence = getString(templateRes, (Object[]) figures);
        SpannableString sp = new SpannableString(sentence);
        for (String fig : figures) {
            int i = sentence.indexOf(fig);
            if (i < 0) {
                continue;
            }
            int end = i + fig.length();
            sp.setSpan(new RelativeSizeSpan(1.7f), i, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sp.setSpan(new ForegroundColorSpan(accent), i, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sp;
    }

    /** "Reclaimed together · last 30 days" — base label + the active window. */
    private CharSequence heroCaptionText(Context ctx, boolean personal) {
        String base = getString(personal ? R.string.community_impact_hero_caption_personal
                : R.string.community_impact_hero_caption);
        return base + " · " + windowSuffix();
    }

    /** The active window as a caption fragment: "all time" / "last 30 days". */
    private String windowSuffix() {
        return "All".equals(selectedWindow)
                ? getString(R.string.community_impact_window_alltime)
                : getString(R.string.community_impact_window_last, windowPhrase());
    }

    private String windowPhrase() {
        switch (selectedWindow) {
            case "7d":  return getString(R.string.community_impact_window_phrase_7d);
            case "90d": return getString(R.string.community_impact_window_phrase_90d);
            case "1y":  return getString(R.string.community_impact_window_phrase_1y);
            default:    return getString(R.string.community_impact_window_phrase_30d);
        }
    }

    /** Community listener count for the selected window (all-time contributors for
     *  "All", else the window's active contributors). */
    private long selectedWindowContributors() {
        if (community == null) {
            return 0;
        }
        if ("All".equals(selectedWindow)) {
            return community.contributors;
        }
        Window w = community.windows.get(selectedWindow);
        return w != null ? w.activeContributors : 0;
    }

    /** Community reclaimed total (sum of the five buckets) for the selected window. */
    private long selectedWindowReclaimed() {
        if (community == null) {
            return 0;
        }
        if ("All".equals(selectedWindow)) {
            return communityReclaimed(community);
        }
        Window w = community.windows.get(selectedWindow);
        return w != null ? w.adsMs + w.silenceMs + w.speedMs + w.introMs + w.outroMs : 0;
    }

    /** The five community buckets {ads, silence, speed, intro, outro} for the
     *  selected window (all-time for "All"), feeding the breakdown. */
    private long[] selectedWindowBuckets() {
        if (community == null) {
            return new long[]{0, 0, 0, 0, 0};
        }
        if ("All".equals(selectedWindow)) {
            return new long[]{community.adsMs, community.silenceMs, community.speedMs,
                    community.introMs, community.outroMs};
        }
        Window w = community.windows.get(selectedWindow);
        if (w == null) {
            return new long[]{0, 0, 0, 0, 0};
        }
        return new long[]{w.adsMs, w.silenceMs, w.speedMs, w.introMs, w.outroMs};
    }

    /** Set the hero sentence with a soft fade when its content changes (e.g. a
     *  window switch). A fade is calmer and font-safe vs. a digit count-up. */
    private void setHero(CharSequence text) {
        heroSentence.setText(text);
        String key = text.toString();
        if (key.equals(lastHeroKey)) {
            return;
        }
        lastHeroKey = key;
        if (heroAnimator != null) {
            heroAnimator.cancel();
        }
        heroSentence.setAlpha(0f);
        heroAnimator = ValueAnimator.ofFloat(0f, 1f);
        heroAnimator.setDuration(500);
        heroAnimator.addUpdateListener(a -> heroSentence.setAlpha((float) a.getAnimatedValue()));
        heroAnimator.start();
    }

    /** The you-vs-community rows for the selected window. Re-run on chip change. */
    private void renderComparison() {
        Context ctx = getContext();
        if (ctx == null || compareContainer == null) {
            return;
        }
        compareWindowCaption.setText(windowCaption(ctx));
        compareContainer.removeAllViews();
        DBReader.SkipBreakdown you = localByWindow != null
                ? localByWindow.get(selectedWindow) : null;

        // One shared scale across all five time-saved buckets (both your values
        // and the community averages), so bar lengths are comparable across rows
        // — a big bucket reads as a long bar, a small one stays short.
        double scale = sharedDurationScale(you);

        addAspect(ctx, getString(R.string.community_impact_row_ads),
                you != null ? you.adMs : 0, communityAvg(BucketType.AD), scale, true);
        addAspect(ctx, getString(R.string.community_impact_row_silence),
                you != null ? you.silenceMs : 0, communityAvg(BucketType.SILENCE), scale, true);
        addAspect(ctx, getString(R.string.community_impact_row_speed_saved),
                you != null ? you.speedMs : 0, communityAvg(BucketType.SPEED), scale, true);
        addAspect(ctx, getString(R.string.community_impact_row_intro),
                you != null ? you.introMs : 0, communityAvg(BucketType.INTRO), scale, true);
        addAspect(ctx, getString(R.string.community_impact_row_outro),
                you != null ? you.outroMs : 0, communityAvg(BucketType.OUTRO), scale, false);

        // Typical playback speed sits OUTSIDE the bar grammar: it's an intensive
        // ×-ratio (not a duration), lifetime, and ignores the window — so a bar
        // would be a category error. Render it as its own small two-number block.
        // The "you" figure is the user's REAL average listening speed (0 = not
        // enough history yet -> shown as "—"), not the configured default speed.
        double youSpeed = localAvgSpeed;
        double communitySpeed = (community != null && community.avgPlaybackSpeed != null)
                ? community.avgPlaybackSpeed : -1;
        addSpeedRow(ctx, youSpeed, communitySpeed);
    }

    /** Largest value across both your figures and the community averages for the
     *  five time-saved buckets, so every comparison bar can share one scale.
     *  Returns at least 1 to avoid divide-by-zero. */
    private double sharedDurationScale(@Nullable DBReader.SkipBreakdown you) {
        double max = 0;
        for (BucketType type : BucketType.values()) {
            max = Math.max(max, localBucket(you, type));
            max = Math.max(max, communityAvg(type));
        }
        return max > 0 ? max : 1;
    }

    private static long localBucket(@Nullable DBReader.SkipBreakdown you, BucketType type) {
        if (you == null) {
            return 0;
        }
        switch (type) {
            case AD:      return you.adMs;
            case SILENCE: return you.silenceMs;
            case SPEED:   return you.speedMs;
            case INTRO:   return you.introMs;
            case OUTRO:   return you.outroMs;
            default:      return 0;
        }
    }

    private String windowCaption(Context ctx) {
        if ("All".equals(selectedWindow)) {
            return getString(R.string.community_impact_compare_window_all);
        }
        int phrase;
        switch (selectedWindow) {
            case "7d":  phrase = R.string.community_impact_window_phrase_7d; break;
            case "90d": phrase = R.string.community_impact_window_phrase_90d; break;
            case "1y":  phrase = R.string.community_impact_window_phrase_1y; break;
            default:    phrase = R.string.community_impact_window_phrase_30d; break;
        }
        return getString(R.string.community_impact_compare_window, getString(phrase));
    }

    private enum BucketType { AD, SILENCE, SPEED, INTRO, OUTRO }

    /** Per-active-listener community average for the selected window (or all-time
     *  for "All"), in ms. Returns -1 when there is no community data to compare. */
    private long communityAvg(BucketType type) {
        if (community == null) {
            return -1;
        }
        long bucket;
        long denom;
        if ("All".equals(selectedWindow)) {
            denom = community.contributors;
            bucket = bucketAllTime(type);
        } else {
            Window w = community.windows.get(selectedWindow);
            if (w == null) {
                return -1;
            }
            denom = w.activeContributors;
            bucket = bucketWindow(w, type);
        }
        return denom > 0 ? bucket / denom : 0;
    }

    /** The community's reclaimed total = the sum of the five buckets we display.
     *  This is the figure we headline, rather than the backend's {@code total_ms}. */
    private static long communityReclaimed(CommunityImpact c) {
        return c.adsMs + c.silenceMs + c.speedMs + c.introMs + c.outroMs;
    }

    private long bucketAllTime(BucketType type) {
        switch (type) {
            case AD:      return community.adsMs;
            case SILENCE: return community.silenceMs;
            case SPEED:   return community.speedMs;
            case INTRO:   return community.introMs;
            case OUTRO:   return community.outroMs;
            default:      return 0;
        }
    }

    private long bucketWindow(Window w, BucketType type) {
        switch (type) {
            case AD:      return w.adsMs;
            case SILENCE: return w.silenceMs;
            case SPEED:   return w.speedMs;
            case INTRO:   return w.introMs;
            case OUTRO:   return w.outroMs;
            default:      return 0;
        }
    }

    /** One comparison row, three stacked lines so the bar owns its own line:
     *    1. label (left) + verdict badge (right)
     *    2. full-width bar (vermilion fill = you, ink tick = the nation's avg) +
     *       ONE trailing mono number = your value
     *    3. a quiet "you X · avg Y" caption
     *  A faint rule separates rows so five don't blur into one gray mass. */
    private void addAspect(Context ctx, String label, double youVal,
                           double communityVal, double scale, boolean separator) {
        int accent = EditorialTheme.vermilion(ctx);
        int ink = EditorialTheme.ink(ctx);
        int muted = EditorialTheme.inkMuted(ctx);
        int track = EditorialTheme.ruleFaint(ctx);
        if (scale <= 0) {
            scale = 1;
        }

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(ctx, 10), 0, dp(ctx, 10));

        // Line 1 — label + verdict badge.
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView labelView = new TextView(ctx);
        labelView.setText(label);
        labelView.setTextSize(14);
        labelView.setTextColor(ink);
        labelView.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(labelView);
        String verdict = verdictText(youVal, communityVal);
        if (verdict != null) {
            TextView verdictView = new TextView(ctx);
            verdictView.setText(verdict);
            verdictView.setTextSize(12);
            verdictView.setTextColor(youVal >= communityVal ? accent : muted);
            header.addView(verdictView);
        }
        row.addView(header);

        // Line 2 — the bar owns the full width; one mono number trails it (you).
        LinearLayout barLine = new LinearLayout(ctx);
        barLine.setOrientation(LinearLayout.HORIZONTAL);
        barLine.setGravity(Gravity.CENTER_VERTICAL);
        barLine.setPadding(0, dp(ctx, 8), 0, 0);
        CompareBar bar = new CompareBar(ctx);
        bar.set((float) (youVal / scale),
                communityVal >= 0 ? (float) (communityVal / scale) : 0f,
                communityVal >= 0, accent, track, ink);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(0, dp(ctx, 14), 1f);
        barLp.setMarginEnd(dp(ctx, 12));
        bar.setLayoutParams(barLp);
        barLine.addView(bar);
        TextView valueView = new TextView(ctx);
        valueView.setTypeface(EditorialTheme.getMono(ctx));
        valueView.setText(formatHumanDuration((long) youVal));
        valueView.setTextSize(13);
        valueView.setTextColor(ink);
        valueView.setGravity(Gravity.END);
        valueView.setMinWidth(dp(ctx, 64));
        barLine.addView(valueView);
        row.addView(barLine);

        // Line 3 — quiet caption with both relevant numbers (you vs the avg).
        TextView caption = new TextView(ctx);
        caption.setTextSize(12);
        caption.setTextColor(muted);
        caption.setPadding(0, dp(ctx, 5), 0, 0);
        String avgStr = communityVal >= 0 ? formatHumanDuration((long) communityVal) : "—";
        caption.setText(getString(R.string.community_impact_compare_you_avg,
                formatHumanDuration((long) youVal), avgStr));
        row.addView(caption);

        compareContainer.addView(row);
        if (separator) {
            View rule = new View(ctx);
            rule.setBackgroundColor(EditorialTheme.ruleVeryFaint(ctx));
            compareContainer.addView(rule, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 0.5f))));
        }
    }

    /** The "Typical speed" block — deliberately NOT a bar (it's a ×-ratio, not a
     *  duration). A ruled separator sets it apart from the five bar rows, then a
     *  label + two plain numerals (you vs the nation) + a faster/slower verdict. */
    private void addSpeedRow(Context ctx, double youSpeed, double communitySpeed) {
        int ink = EditorialTheme.ink(ctx);
        int muted = EditorialTheme.inkMuted(ctx);
        int accent = EditorialTheme.vermilion(ctx);

        View rule = new View(ctx);
        rule.setBackgroundColor(EditorialTheme.ruleFaint(ctx));
        LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 0.5f)));
        ruleLp.topMargin = dp(ctx, 14);
        ruleLp.bottomMargin = dp(ctx, 4);
        compareContainer.addView(rule, ruleLp);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(ctx, 8), 0, dp(ctx, 8));

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView labelView = new TextView(ctx);
        String cap = "   " + getString(R.string.community_impact_row_speed_caption);
        labelView.setText(spanColor(getString(R.string.community_impact_row_speed) + cap,
                cap.trim(), muted));
        labelView.setTextSize(14);
        labelView.setTextColor(ink);
        labelView.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(labelView);
        if (communitySpeed >= 0 && youSpeed > 0) {
            TextView verdict = new TextView(ctx);
            boolean faster = youSpeed >= communitySpeed;
            verdict.setText(faster ? R.string.community_impact_speed_faster
                    : R.string.community_impact_speed_slower);
            verdict.setTextSize(12);
            verdict.setTextColor(faster ? accent : muted);
            header.addView(verdict);
        }
        row.addView(header);

        TextView values = new TextView(ctx);
        values.setTypeface(EditorialTheme.getMono(ctx));
        values.setTextSize(13);
        values.setTextColor(ink);
        values.setPadding(0, dp(ctx, 6), 0, 0);
        String youStr = youSpeed > 0
                ? String.format(Locale.getDefault(), "%.2f×", youSpeed) : "—";
        String natStr = communitySpeed >= 0
                ? String.format(Locale.getDefault(), "%.2f×", communitySpeed) : "—";
        String line = getString(R.string.community_impact_speed_values, youStr, natStr);
        SpannableString sp = new SpannableString(line);
        int natAt = line.indexOf(natStr, youStr.length());
        if (natAt >= 0) {
            sp.setSpan(new ForegroundColorSpan(muted), natAt - 1, line.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        values.setText(sp);
        row.addView(values);
        compareContainer.addView(row);
    }

    @Nullable
    private String verdictText(double youVal, double communityVal) {
        if (communityVal <= 0 || youVal <= 0) {
            return null;
        }
        double ratio = youVal / communityVal;
        if (ratio >= 1.15) {
            return getString(R.string.community_impact_verdict_multiple,
                    String.format(Locale.getDefault(), "%.1f", ratio));
        } else if (ratio <= 0.85) {
            return getString(R.string.community_impact_verdict_below);
        }
        return getString(R.string.community_impact_verdict_on_par);
    }

    private static final int BUCKET_COUNT = 5;

    /** "Where the time goes" — ONE stacked proportion bar (the same idea as the
     *  share card) plus a compact legend listing each bucket's absolute community
     *  total and share, for the selected window. Replaces the old five bar rows. */
    private void renderBreakdown(Context ctx, long[] parts) {
        breakdownContainer.removeAllViews();
        int[] colors = bucketColors(ctx);
        String[] labels = {
                getString(R.string.community_impact_row_ads),
                getString(R.string.community_impact_row_silence),
                getString(R.string.community_impact_row_speed_saved),
                getString(R.string.community_impact_row_intro),
                getString(R.string.community_impact_row_outro)};
        long total = 0;
        for (long p : parts) {
            total += p;
        }
        total = Math.max(1, total);

        StackedBar bar = new StackedBar(ctx);
        bar.set(parts, colors);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 18));
        bar.setLayoutParams(barLp);
        breakdownContainer.addView(bar);

        for (int i = 0; i < BUCKET_COUNT; i++) {
            breakdownContainer.addView(
                    breakdownLegendRow(ctx, labels[i], parts[i], total, colors[i]));
        }
    }

    /** Bucket palette, order: ads, silence, speed, intros, outros. Five distinct
     *  hues (the muddy ink-caption/ink-very-muted browns are gone) — this is the
     *  ONE place the multi-hue palette lives, so it doesn't fight the comparison's
     *  "vermilion = you". */
    private int[] bucketColors(Context ctx) {
        return new int[]{
                EditorialTheme.vermilion(ctx),
                EditorialTheme.gold(ctx),
                EditorialTheme.ink(ctx),
                EditorialTheme.vermilionSoft(ctx),
                EditorialTheme.goldSoft(ctx)
        };
    }

    /** One legend line: colour swatch + label + absolute total + percentage. */
    private View breakdownLegendRow(Context ctx, String label, long ms, long total, int tint) {
        LinearLayout rowView = new LinearLayout(ctx);
        rowView.setOrientation(LinearLayout.HORIZONTAL);
        rowView.setGravity(Gravity.CENTER_VERTICAL);
        rowView.setPadding(0, dp(ctx, 6), 0, dp(ctx, 6));

        View swatch = new View(ctx);
        swatch.setBackgroundColor(tint);
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(dp(ctx, 10), dp(ctx, 10));
        swLp.setMarginEnd(dp(ctx, 10));
        rowView.addView(swatch, swLp);

        TextView labelView = new TextView(ctx);
        labelView.setText(label);
        labelView.setTextSize(14);
        labelView.setTextColor(EditorialTheme.ink(ctx));
        labelView.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        rowView.addView(labelView);

        TextView valueView = new TextView(ctx);
        valueView.setTypeface(EditorialTheme.getMono(ctx));
        valueView.setTextSize(13);
        valueView.setGravity(Gravity.END);
        String dur = ms > 0 ? formatHumanDuration(ms) : "—";
        String pct = "  " + Math.round(100.0 * ms / total) + "%";
        SpannableString sp = new SpannableString(dur + pct);
        sp.setSpan(new ForegroundColorSpan(EditorialTheme.ink(ctx)), 0, dur.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        sp.setSpan(new ForegroundColorSpan(EditorialTheme.inkMuted(ctx)),
                dur.length(), dur.length() + pct.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        valueView.setText(sp);
        rowView.addView(valueView);
        return rowView;
    }

    // ── Share card ────────────────────────────────────────────────────────────

    private void shareImpact() {
        final Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        DBReader.SkipBreakdown localAll = localByWindow != null ? localByWindow.get("All") : null;
        final long yourTotal = localAll != null ? localAll.totalMs : 0;
        final CommunityImpact c = community;
        final boolean hasCommunity = c != null && communityReclaimed(c) > 0;
        if (!hasCommunity && yourTotal <= 0) {
            return;  // nothing to share yet
        }

        // Community card when we have pooled data; otherwise a personal "my
        // impact" card built from the local breakdown alone.
        final String eyebrow = hasCommunity ? "RECLAIMED BY TRIM NATION" : "MY TIME, RECLAIMED";
        final long heroTotal = hasCommunity ? communityReclaimed(c) : yourTotal;
        final long[] buckets = hasCommunity
                ? new long[]{c.adsMs, c.silenceMs, c.speedMs, c.introMs, c.outroMs}
                : localBuckets(localAll);
        // Community card footers with the user's own cut; the personal card's hero
        // already is that figure, so it needs no footer line.
        final String footer = hasCommunity && yourTotal > 0
                ? "I've personally cut " + formatHumanDuration(yourTotal) : null;
        final String shareText = hasCommunity
                ? getString(R.string.community_impact_share_text,
                        relatablePhrase(heroTotal), formatHumanDuration(yourTotal))
                : getString(R.string.community_impact_share_text_personal,
                        formatHumanDuration(yourTotal));

        if (shareDisposable != null) {
            shareDisposable.dispose();
        }
        shareDisposable = Single.fromCallable(() -> {
            Bitmap bmp = drawShareCard(ctx, eyebrow, heroTotal, buckets, footer);
            File file = new File(UserPreferences.getDataFolder(null), "TrimPlayerImpact.png");
            try (FileOutputStream out = new FileOutputStream(file)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 90, out);
            }
            return androidx.core.content.FileProvider.getUriForFile(
                    ctx, ctx.getString(R.string.provider_authority), file);
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(uri -> launchShareChooser(uri, shareText),
                        error -> {
                            // Don't fail silently — a dead-looking button is worse
                            // than a message. Surface the real cause for diagnosis.
                            Log.e(TAG, "Building/sharing the impact card failed", error);
                            Context c2 = getContext();
                            if (c2 != null) {
                                Toast.makeText(c2, R.string.community_impact_share_failed,
                                        Toast.LENGTH_LONG).show();
                            }
                        });
    }

    private static long[] localBuckets(@Nullable DBReader.SkipBreakdown b) {
        if (b == null) {
            return new long[]{0, 0, 0, 0, 0};
        }
        return new long[]{b.adMs, b.silenceMs, b.speedMs, b.introMs, b.outroMs};
    }

    private void launchShareChooser(Uri uri, String shareText) {
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        try {
            new ShareCompat.IntentBuilder(ctx)
                    .setType("image/png")
                    .addStream(uri)
                    .setText(shareText + "\n" + SHARE_URL)
                    .setChooserTitle(R.string.community_impact_share_button)
                    .startChooser();
        } catch (android.content.ActivityNotFoundException e) {
            // No app can handle the share intent (e.g. a bare emulator).
            Log.e(TAG, "No app available to handle the share", e);
            Toast.makeText(ctx, R.string.community_impact_share_failed,
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Renders the 1080² share card. {@code buckets} = {ads, silence, speed, intro,
     * outro} for the stacked proportion bar; {@code footerLine} is an optional
     * second line below the hairline (null to omit). Used for both the community
     * card and the local-only personal card.
     */
    private Bitmap drawShareCard(Context ctx, String eyebrowText, long heroTotal,
                                 long[] buckets, @Nullable String footerLine) {
        final int w = SHARE_SIZE;
        final int h = SHARE_SIZE;
        final int bg = 0xFF15110D;        // editorial ink (dark)
        final int cream = 0xFFFBF8F1;     // editorial paper
        final int accent = 0xFFB8442E;    // editorial vermilion
        final int muted = 0xFF9A8C7A;
        final int hairline = 0x33FBF8F1;  // cream @ ~20%

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(bg);
        Typeface serif = EditorialTheme.getSerif(ctx);

        Paint wordmark = textPaint(cream, 34, Typeface.DEFAULT);
        wordmark.setLetterSpacing(0.25f);
        canvas.drawText("TRIMPLAYER", w / 2f, 134, wordmark);

        Paint rulePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        rulePaint.setColor(accent);
        canvas.drawRect(w / 2f - 60, 196, w / 2f + 60, 200, rulePaint);

        Paint eyebrow = textPaint(muted, 30, Typeface.DEFAULT);
        eyebrow.setLetterSpacing(0.15f);
        canvas.drawText(eyebrowText, w / 2f, 300, eyebrow);

        Paint hero = textPaint(accent, 190, serif);
        canvas.drawText(relatablePhrase(heroTotal), w / 2f, 480, hero);

        Paint sub = textPaint(cream, 40, Typeface.DEFAULT);
        canvas.drawText("of ads, silence, intros and filler", w / 2f, 556, sub);

        // Mini stacked proportion bar.
        long total = Math.max(1, buckets[0] + buckets[1] + buckets[2] + buckets[3] + buckets[4]);
        int[] segColors = {accent, 0xFFA47436, cream, 0xFF8C7A66, 0xFF5E5347};
        float barLeft = 160, barRight = 920, barTop = 660, barBottom = 684;
        float x = barLeft;
        Paint seg = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < buckets.length; i++) {
            float segW = (barRight - barLeft) * buckets[i] / total;
            seg.setColor(segColors[i]);
            canvas.drawRect(x, barTop, x + segW, barBottom, seg);
            x += segW;
        }

        Paint legend = textPaint(muted, 28, Typeface.DEFAULT);
        canvas.drawText(String.format(Locale.US, "Ads %d%%  ·  Silence %d%%  ·  Speed %d%%",
                pct(buckets[0], total), pct(buckets[1], total), pct(buckets[2], total)),
                w / 2f, 744, legend);

        if (footerLine != null) {
            Paint hairPaint = new Paint();
            hairPaint.setColor(hairline);
            canvas.drawRect(160, 860, 920, 861, hairPaint);

            Paint you = textPaint(cream, 46, Typeface.DEFAULT);
            canvas.drawText(footerLine, w / 2f, 944, you);
        }

        Paint url = textPaint(accent, 34, Typeface.DEFAULT);
        url.setLetterSpacing(0.05f);
        canvas.drawText("trimplayer.com", w / 2f, 1012, url);
        return bmp;
    }

    private static Paint textPaint(int color, float size, Typeface tf) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setTextSize(size);
        p.setTypeface(tf);
        p.setTextAlign(Paint.Align.CENTER);
        return p;
    }

    private static int pct(long part, long total) {
        return (int) Math.round(100.0 * part / total);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Colour the first occurrence of {@code sub} within {@code full}.
     */
    private static CharSequence spanColor(String full, String sub, int color) {
        int i = full.indexOf(sub);
        if (i < 0) {
            return full;
        }
        SpannableString s = new SpannableString(full);
        s.setSpan(new ForegroundColorSpan(color), i, i + sub.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    /** "Updated 3 hours ago" / "Updated just now" / "Updated on 6 Jun" from an
     *  ISO-8601 UTC {@code as_of}; null (hide) if it can't be parsed. */
    @Nullable
    private String formatUpdated(Context ctx, @Nullable String asOf) {
        if (asOf == null || asOf.isEmpty()) {
            return null;
        }
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date date = fmt.parse(asOf);
            if (date == null) {
                return null;
            }
            long epoch = date.getTime();
            long now = System.currentTimeMillis();
            if (now - epoch < 60_000L) {
                return getString(R.string.community_impact_updated,
                        getString(R.string.community_impact_updated_justnow));
            }
            CharSequence rel = DateUtils.getRelativeTimeSpanString(
                    epoch, now, DateUtils.MINUTE_IN_MILLIS);
            return getString(R.string.community_impact_updated, rel);
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static String labelForChip(int chipId) {
        if (chipId == R.id.chip_7d) {
            return "7d";
        } else if (chipId == R.id.chip_30d) {
            return "30d";
        } else if (chipId == R.id.chip_90d) {
            return "90d";
        } else if (chipId == R.id.chip_1y) {
            return "1y";
        } else if (chipId == R.id.chip_all) {
            return "All";
        }
        return null;
    }

    private static int dp(Context ctx, float dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }

    /** A friendly, relatable phrase for a big duration: "9 years", "3 months",
     *  "12 days", "5 hours". Used for the hero subtitle and the share card. */
    private static String relatablePhrase(long ms) {
        double days = ms / (double) DAY_MS;
        if (days >= 365) {
            double years = days / 365.0;
            String n = years >= 10 ? String.format(Locale.getDefault(), "%.0f", years)
                    : String.format(Locale.getDefault(), "%.1f", years);
            return n + (years >= 2 ? " years" : " year");
        }
        if (days >= 60) {
            long months = Math.round(days / 30.0);
            return months + " months";
        }
        if (days >= 1) {
            long d = Math.round(days);
            return d + (d == 1 ? " day" : " days");
        }
        long hours = Math.max(1, Math.round(ms / 3_600_000.0));
        return hours + (hours == 1 ? " hour" : " hours");
    }

    /** Human-readable duration: Nd Mh / Nh Mm / Nm / 0m. */
    private static String formatHumanDuration(long ms) {
        long totalMin = ms / 60_000L;
        if (totalMin <= 0) {
            return "0m";
        }
        long minutes = totalMin % 60;
        long totalHr = totalMin / 60;
        long hours = totalHr % 24;
        long days = totalHr / 24;
        if (days > 0) {
            return hours > 0 ? days + "d " + hours + "h" : days + "d";
        }
        if (hours > 0) {
            return minutes > 0 ? hours + "h " + minutes + "m" : hours + "h";
        }
        return minutes + "m";
    }

    /** A flat horizontal bar: a faint track, a coloured fill (your value), and an
     *  optional ink "par" tick (the community average) for one-glance comparison. */
    private static final class CompareBar extends View {
        private float youFrac;
        private float commFrac;
        private boolean showTick;
        private int fillColor;
        private int trackColor;
        private int tickColor;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float density;

        CompareBar(Context ctx) {
            this(ctx, null);
        }

        CompareBar(Context ctx, @Nullable android.util.AttributeSet attrs) {
            super(ctx, attrs);
            density = ctx.getResources().getDisplayMetrics().density;
        }

        void set(float youFrac, float commFrac, boolean showTick,
                 int fillColor, int trackColor, int tickColor) {
            this.youFrac = clamp(youFrac);
            this.commFrac = clamp(commFrac);
            this.showTick = showTick;
            this.fillColor = fillColor;
            this.trackColor = trackColor;
            this.tickColor = tickColor;
            invalidate();
        }

        private static float clamp(float f) {
            return Math.max(0f, Math.min(1f, f));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float trackH = 8 * density;
            float corner = trackH / 2f;
            float top = (h - trackH) / 2f;

            paint.setColor(trackColor);
            rect.set(0, top, w, top + trackH);
            canvas.drawRoundRect(rect, corner, corner, paint);

            float fillW = youFrac * w;
            if (fillW > 0) {
                paint.setColor(fillColor);
                rect.set(0, top, fillW, top + trackH);
                canvas.drawRoundRect(rect, corner, corner, paint);
            }

            if (showTick) {
                float tickH = 12 * density;
                float tickW = 2 * density;
                float x = commFrac * w;
                paint.setColor(tickColor);
                canvas.drawRect(x - tickW / 2f, (h - tickH) / 2f,
                        x + tickW / 2f, (h + tickH) / 2f, paint);
            }
        }
    }

    /** A single horizontal bar split into proportional coloured segments — the
     *  "where the time goes" breakdown as one glanceable stacked bar (mirrors the
     *  share card's mini bar). Segments narrower than a hairline are skipped. */
    private static final class StackedBar extends View {
        @Nullable private long[] parts;
        @Nullable private int[] colors;
        private long total;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        StackedBar(Context ctx) {
            this(ctx, null);
        }

        StackedBar(Context ctx, @Nullable android.util.AttributeSet attrs) {
            super(ctx, attrs);
        }

        void set(long[] parts, int[] colors) {
            this.parts = parts;
            this.colors = colors;
            long sum = 0;
            for (long p : parts) {
                sum += p;
            }
            this.total = Math.max(1, sum);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (parts == null || colors == null) {
                return;
            }
            float w = getWidth();
            float h = getHeight();
            float x = 0;
            for (int i = 0; i < parts.length && i < colors.length; i++) {
                float segW = w * parts[i] / total;
                if (segW <= 0) {
                    continue;
                }
                paint.setColor(colors[i]);
                canvas.drawRect(x, 0, x + segW, h, paint);
                x += segW;
            }
        }
    }

    private static final class Loaded {
        final Map<String, DBReader.SkipBreakdown> local;
        final float avgSpeed;
        @Nullable final CommunityImpact community;

        Loaded(Map<String, DBReader.SkipBreakdown> local, float avgSpeed,
               @Nullable CommunityImpact community) {
            this.local = local;
            this.avgSpeed = avgSpeed;
            this.community = community;
        }
    }
}
