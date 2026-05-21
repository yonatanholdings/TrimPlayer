package de.danoeh.antennapod.ui.screen.preferences;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;

import com.bytehamster.lib.preferencesearch.SearchConfiguration;
import com.bytehamster.lib.preferencesearch.SearchPreference;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.ui.preferences.screen.AnimatedPreferenceFragment;
import de.danoeh.antennapod.ui.preferences.screen.about.AboutFragment;
import de.danoeh.antennapod.ui.preferences.screen.bugreport.BugReportFragment;
import de.danoeh.antennapod.ui.statistics.StatisticsFragment;

public class MainPreferencesFragment extends AnimatedPreferenceFragment {

    private static final String PREF_SCREEN_USER_INTERFACE = "prefScreenInterface";
    private static final String PREF_SCREEN_PLAYBACK = "prefScreenPlayback";
    private static final String PREF_SCREEN_DOWNLOADS = "prefScreenDownloads";
    private static final String PREF_SCREEN_IMPORT_EXPORT = "prefScreenImportExport";
    private static final String PREF_SEND_BUG_REPORT = "prefSendBugReport";
    private static final String PREF_ABOUT = "prefAbout";
    private static final String PREF_NOTIFICATION = "notifications";
    private static final String PREF_STATISTICS = "prefStatistics";
    private static final String PREF_TRIM_PRO = "prefTrimPro";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.preferences);
        setupMainScreen();
        setupSearch();
    }

    @Override
    public void onStart() {
        super.onStart();
        ((PreferenceActivity) getActivity()).getSupportActionBar().setTitle(R.string.settings_label);
    }

    private void setupMainScreen() {
        findPreference(PREF_SCREEN_USER_INTERFACE).setOnPreferenceClickListener(preference -> {
            ((PreferenceActivity) getActivity()).openScreen(R.xml.preferences_user_interface);
            return true;
        });
        findPreference(PREF_SCREEN_PLAYBACK).setOnPreferenceClickListener(preference -> {
            ((PreferenceActivity) getActivity()).openScreen(R.xml.preferences_playback);
            return true;
        });
        findPreference(PREF_SCREEN_DOWNLOADS).setOnPreferenceClickListener(preference -> {
            ((PreferenceActivity) getActivity()).openScreen(R.xml.preferences_downloads);
            return true;
        });
        findPreference(PREF_SCREEN_IMPORT_EXPORT).setOnPreferenceClickListener(preference -> {
            ((PreferenceActivity) getActivity()).openScreen(R.xml.preferences_import_export);
            return true;
        });
        findPreference(PREF_NOTIFICATION).setOnPreferenceClickListener(preference -> {
            ((PreferenceActivity) getActivity()).openScreen(R.xml.preferences_notifications);
            return true;
        });
        findPreference(PREF_ABOUT).setOnPreferenceClickListener(
                preference -> {
                    getParentFragmentManager().beginTransaction()
                            .replace(R.id.settingsContainer, new AboutFragment())
                            .addToBackStack(getString(R.string.about_pref)).commit();
                    return true;
                }
        );
        findPreference(PREF_SEND_BUG_REPORT).setOnPreferenceClickListener(preference -> {
            getParentFragmentManager().beginTransaction()
                    .replace(R.id.settingsContainer, new BugReportFragment())
                    .addToBackStack(getString(R.string.report_bug_title)).commit();
            return true;
        });
        findPreference(PREF_STATISTICS).setOnPreferenceClickListener(preference -> {
            getParentFragmentManager().beginTransaction()
                    .replace(R.id.settingsContainer, new StatisticsFragment())
                    .addToBackStack(getString(R.string.statistics_label)).commit();
            return true;
        });
        // TrimPlayer Pro entry — summary text reflects current entitlement.
        // Visibility is server-driven; the preference appears/disappears the
        // next time the user returns to Settings after a /segments response
        // changes the pro_ui_visible flag.
        Preference proPref = findPreference(PREF_TRIM_PRO);
        if (proPref != null) {
            if (!de.danoeh.antennapod.ui.screen.preferences.pro.TrimProDialogs.isProUiVisible()) {
                proPref.setVisible(false);
            } else {
                de.danoeh.antennapod.playback.service.trim.EntitlementStore.Snapshot snap =
                        de.danoeh.antennapod.playback.service.trim.EntitlementStore.get().snapshot();
                if (snap != null && snap.isPro()) {
                    proPref.setSummary(R.string.trim_pro_pref_summary_active);
                }
                proPref.setOnPreferenceClickListener(preference -> {
                    getParentFragmentManager().beginTransaction()
                            .replace(R.id.settingsContainer,
                                    new de.danoeh.antennapod.ui.screen.preferences.pro.TrimProFragment())
                            .addToBackStack(getString(R.string.trim_pro_title)).commit();
                    return true;
                });
            }
        }
    }

    private void setupSearch() {
        SearchPreference searchPreference = findPreference("searchPreference");
        SearchConfiguration config = searchPreference.getSearchConfiguration();
        config.setActivity((AppCompatActivity) getActivity());
        config.setFragmentContainerViewId(R.id.settingsContainer);
        config.setBreadcrumbsEnabled(true);

        config.index(R.xml.preferences_user_interface)
                .addBreadcrumb(PreferenceActivity.getTitleOfPage(R.xml.preferences_user_interface));
        config.index(R.xml.preferences_playback)
                .addBreadcrumb(PreferenceActivity.getTitleOfPage(R.xml.preferences_playback));
        config.index(R.xml.preferences_downloads)
                .addBreadcrumb(PreferenceActivity.getTitleOfPage(R.xml.preferences_downloads));
        config.index(R.xml.preferences_import_export)
                .addBreadcrumb(PreferenceActivity.getTitleOfPage(R.xml.preferences_import_export));
        config.index(R.xml.preferences_autodownload)
                .addBreadcrumb(PreferenceActivity.getTitleOfPage(R.xml.preferences_downloads))
                .addBreadcrumb(R.string.automation)
                .addBreadcrumb(PreferenceActivity.getTitleOfPage(R.xml.preferences_autodownload));
        config.index(R.xml.preferences_notifications)
                .addBreadcrumb(PreferenceActivity.getTitleOfPage(R.xml.preferences_notifications));
        config.index(R.xml.feed_settings)
                .addBreadcrumb(PreferenceActivity.getTitleOfPage(R.xml.feed_settings));
        config.index(R.xml.preferences_swipe)
                .addBreadcrumb(PreferenceActivity.getTitleOfPage(R.xml.preferences_user_interface))
                .addBreadcrumb(PreferenceActivity.getTitleOfPage(R.xml.preferences_swipe));
    }
}
