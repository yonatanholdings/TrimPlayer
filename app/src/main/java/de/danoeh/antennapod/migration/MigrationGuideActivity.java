package de.danoeh.antennapod.migration;

import android.content.res.TypedArray;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ArrayRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.ui.common.ThemeSwitcher;

/**
 * Pre-picker "how to export your backup" guide for the file-based migration
 * sources. It sits between tapping a source (e.g. Podcast Addict) and the raw
 * any-file system picker, teaching the one step the picker can't: the
 * user has to produce a backup <em>inside the other app first</em>. Spotify is
 * deliberately not routed here — {@link SpotifyMigrationActivity} captures the
 * library in-app, so there's no file to export and no guide to show.
 *
 * <p>Purely presentational: it owns no import logic. On "choose file" / "skip"
 * it finishes with {@link #RESULT_OK}; the caller (onboarding or Settings →
 * Import) then launches its existing file picker, so the shared
 * {@code ImportFlowController} auto-detection and success flow are untouched.
 * Back/cancel finishes {@link #RESULT_CANCELED} and the caller does nothing.
 *
 * <p>Adding another source is one {@link GuideApp} entry + its strings + a
 * screenshot drawable — no new code path.
 */
public class MigrationGuideActivity extends AppCompatActivity {

    /** Import-source key (matches the onboarding/analytics labels), e.g. "podcast_addict". */
    public static final String EXTRA_SOURCE = "de.danoeh.antennapod.migration.SOURCE";

    /** One guided source: app name + intro + numbered steps (each with an optional
     *  per-step screenshot, parallel array) + optional tip. */
    enum GuideApp {
        PODCAST_ADDICT("podcast_addict",
                R.string.onboarding_source_podcast_addict,
                R.string.migration_guide_podcast_addict_intro,
                R.array.migration_guide_podcast_addict_steps,
                R.array.migration_guide_podcast_addict_step_images,
                R.string.migration_guide_podcast_addict_note),
        ANTENNAPOD("antennapod",
                R.string.onboarding_source_antennapod,
                R.string.migration_guide_antennapod_intro,
                R.array.migration_guide_antennapod_steps,
                R.array.migration_guide_antennapod_step_images,
                R.string.migration_guide_antennapod_note);

        final String source;
        @StringRes final int appName;
        @StringRes final int intro;
        @ArrayRes final int steps;
        /**
         * Drawables parallel to {@link #steps}; an entry may be 0 for a text-only step.
         */
        @ArrayRes final int stepImages;
        /** 0 ⇒ no tip line. */
        @StringRes final int note;

        GuideApp(String source, @StringRes int appName, @StringRes int intro,
                 @ArrayRes int steps, @ArrayRes int stepImages, @StringRes int note) {
            this.source = source;
            this.appName = appName;
            this.intro = intro;
            this.steps = steps;
            this.stepImages = stepImages;
            this.note = note;
        }

        @Nullable
        static GuideApp forSource(@Nullable String source) {
            if (source == null) {
                return null;
            }
            for (GuideApp app : values()) {
                if (app.source.equals(source)) {
                    return app;
                }
            }
            return null;
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setTheme(ThemeSwitcher.getNoTitleTheme(this));
        super.onCreate(savedInstanceState);

        GuideApp app = GuideApp.forSource(getIntent().getStringExtra(EXTRA_SOURCE));
        if (app == null) {
            // No guide for this source (or none supplied): don't strand the user —
            // proceed straight to the picker the caller would have opened anyway.
            setResult(RESULT_OK);
            finish();
            return;
        }

        setContentView(R.layout.migration_guide_activity);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(getString(R.string.migration_guide_title, getString(app.appName)));
        setSupportActionBar(toolbar);
        // Navigation (back) leaves RESULT_CANCELED, so the caller stays put.
        toolbar.setNavigationOnClickListener(v -> finish());

        ((TextView) findViewById(R.id.guide_intro)).setText(app.intro);

        bindSteps(app);

        TextView note = findViewById(R.id.guide_note);
        if (app.note != 0) {
            note.setText(app.note);
        } else {
            note.setVisibility(View.GONE);
        }

        findViewById(R.id.guide_choose_file).setOnClickListener(v -> proceed());
        findViewById(R.id.guide_skip).setOnClickListener(v -> proceed());
    }

    /** Inflate one numbered row per step from the app's string-array, attaching the
     *  parallel per-step screenshot where present. The step list is data — edit the
     *  arrays, not this code, to change copy or images. */
    private void bindSteps(GuideApp app) {
        LinearLayout container = findViewById(R.id.guide_steps);
        String[] steps = getResources().getStringArray(app.steps);
        TypedArray images = getResources().obtainTypedArray(app.stepImages);
        try {
            LayoutInflater inflater = LayoutInflater.from(this);
            for (int i = 0; i < steps.length; i++) {
                View row = inflater.inflate(R.layout.migration_guide_step, container, false);
                ((TextView) row.findViewById(R.id.step_number))
                        .setText(getString(R.string.migration_guide_step_number, i + 1));
                ((TextView) row.findViewById(R.id.step_text)).setText(steps[i]);

                int imageRes = i < images.length() ? images.getResourceId(i, 0) : 0;
                if (imageRes != 0) {
                    ImageView image = row.findViewById(R.id.step_image);
                    image.setImageResource(imageRes);
                    image.setContentDescription(getString(
                            R.string.migration_guide_screenshot_desc, i + 1, getString(app.appName)));
                    row.findViewById(R.id.step_image_card).setVisibility(View.VISIBLE);
                }
                container.addView(row);
            }
        } finally {
            images.recycle();
        }
    }

    private void proceed() {
        setResult(RESULT_OK);
        finish();
    }
}
