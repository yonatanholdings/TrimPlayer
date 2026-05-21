package de.danoeh.antennapod.ui.statistics;

import de.danoeh.antennapod.storage.database.DBReader;

import java.util.Calendar;

/**
 * Synthetic stats for marketing screenshots. When {@link #ENABLED} is true,
 * {@link StatisticsViewModel#refresh} returns these values instead of running
 * the real DB queries. Flip the flag back to false before shipping.
 *
 * Data is shaped to look "realistic but flattering": a regular listener with
 * a multi-year history, weekend-heavy listening, an evening peak, and a healthy
 * mix of trim categories.
 */
public final class DemoStats {

    /** Master switch — set to true to populate the Statistics screens with
     *  synthetic data, false to use real DB-backed stats. */
    public static final boolean ENABLED = true;

    private DemoStats() {}

    public static DBReader.EditorialStats fakeEditorial() {
        DBReader.EditorialStats s = new DBReader.EditorialStats();

        // ── Totals ───────────────────────────────────────────────────────────
        s.totalPlayedMs = hoursToMs(412.5);
        s.totalSavedMs  = hoursToMs(58.2);
        s.savedSpeedMs   = hoursToMs(31.4);
        s.savedSilenceMs = hoursToMs(13.7);
        s.savedIntrosMs  = hoursToMs(8.1);

        // ── Episode counters ────────────────────────────────────────────────
        s.episodesStarted    = 438;
        s.episodesCompleted  = 312;
        s.episodesInProgress = 11;
        s.episodesAbandoned  = 27;
        s.streakDays         = 14;

        // ── Hour-of-day (minutes per hour, evening peak) ────────────────────
        // Hand-shaped curve: very low overnight, morning commute bump, dip
        // through midday, evening climb peaking at 9pm.
        long[] hour = {
                  3,   1,   0,   0,   0,   2,   8,  35,
                 52,  28,  18,  14,  22,  19,  17,  21,
                 32,  41,  68,  82,  94, 118,  76,  44
        };
        s.byHour = hour;
        s.topHourLocal = 21;

        // ── Day-of-week (Sun=0 … Sat=6, minutes per day) ────────────────────
        s.byDay = new long[] { 142, 88, 96, 124, 101, 78, 167 };

        // ── Weekly hours (12 weeks, oldest first) ───────────────────────────
        s.weekly = new float[] {
                4.2f, 5.8f, 6.1f, 7.4f, 5.9f, 8.3f,
                7.1f, 9.0f, 8.4f, 9.8f, 10.6f, 11.9f
        };

        // ── Heatmap (26 weeks × 7 days) ─────────────────────────────────────
        // Start at the Sunday 26 weeks ago (matches real query semantics).
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.DAY_OF_YEAR, -26 * 7);
        int dow = cal.get(Calendar.DAY_OF_WEEK) - 1;
        cal.add(Calendar.DAY_OF_YEAR, -dow);
        s.heatmapStartMs = cal.getTimeInMillis();

        // Seeded pseudorandom so the grid looks organic but reproducible.
        java.util.Random rnd = new java.util.Random(20260520L);
        for (int w = 0; w < 26; w++) {
            for (int d = 0; d < 7; d++) {
                // Weekend cells lean heavier; recent weeks slightly more active.
                double weekend = (d == 0 || d == 6) ? 1.4 : 1.0;
                double recency = 0.7 + 0.6 * (w / 25.0);
                double rest = rnd.nextDouble() < 0.18 ? 0 : 1.0; // ~18% rest days
                double v = rnd.nextDouble() * weekend * recency * rest;
                int bucket = v < 0.05 ? 0 : (int) Math.min(4, 1 + v * 4);
                s.heatmap[w][d] = bucket;
                s.heatmapMs[w][d] = bucket == 0 ? 0 : (long) (v * 90 * 60_000L);
            }
        }

        // ── Yearly totals ──────────────────────────────────────────────────
        s.yearly.add(new DBReader.EditorialStats.YearItem(2022,  64.5f));
        s.yearly.add(new DBReader.EditorialStats.YearItem(2023, 118.2f));
        s.yearly.add(new DBReader.EditorialStats.YearItem(2024, 102.7f));
        s.yearly.add(new DBReader.EditorialStats.YearItem(2025, 142.0f));
        s.yearly.add(new DBReader.EditorialStats.YearItem(2026,  85.1f));

        // ── Shows (top 8 + Other) ──────────────────────────────────────────
        // Colors are pre-set fallbacks; FeedColorCache will overwrite once
        // covers exist (won't, because feedIds don't map to real feeds).
        int[] palette = {0xFFf4a261, 0xFF2a9d8f, 0xFFe76f51, 0xFF264653,
                         0xFFa06cd5, 0xFF83c5be, 0xFFbc4749, 0xFF588157};
        String[] titles = {
                "The Daily", "Hard Fork", "Lex Fridman Podcast",
                "Acquired", "99% Invisible", "Conan O'Brien Needs a Friend",
                "Search Engine", "Reply All"
        };
        float[] hrs = { 72.3f, 58.1f, 49.7f, 41.4f, 33.0f, 28.6f, 21.9f, 17.4f };
        float totalShowHrs = 0;
        for (float h : hrs) totalShowHrs += h;
        for (int i = 0; i < titles.length; i++) {
            int pct = Math.round(hrs[i] * 100f / totalShowHrs);
            s.shows.add(new DBReader.EditorialStats.ShowItem(
                    1000L + i, titles[i], null, hrs[i], pct, palette[i]));
        }
        // "Other" bucket
        s.shows.add(new DBReader.EditorialStats.ShowItem(
                -1, "Other", null, 11.6f, 0, 0xFF9aa0a6));

        return s;
    }

    public static DBReader.SkipStatistics fakeSkip() {
        DBReader.SkipStatistics r = new DBReader.SkipStatistics();
        r.speedMs   = hoursToMs(31.4);
        r.silenceMs = hoursToMs(13.7);
        r.adMs      = hoursToMs(7.2);
        r.introMs   = hoursToMs(4.4);
        r.outroMs   = hoursToMs(1.5);
        r.totalMs = r.speedMs + r.silenceMs + r.adMs + r.introMs + r.outroMs;

        r.todayMs = minutesToMs(12);
        r.weekMs  = hoursToMs(3.4);
        r.monthMs = hoursToMs(11.8);

        // ── Monthly skip totals across 3 years ──────────────────────────────
        // Aggregated by the Time Saved tab into per-year buckets. Recent
        // months heavier; older months thinner. Cur year through May.
        int[] year2024 = { 11, 9, 14, 16, 18, 22, 19, 24, 21, 26, 23, 28 };
        int[] year2025 = { 25, 27, 31, 28, 34, 38, 36, 42, 39, 44, 47, 52 };
        int[] year2026 = { 49, 53, 58, 61, 47 };
        addMonths(r, 2024, year2024);
        addMonths(r, 2025, year2025);
        addMonths(r, 2026, year2026);

        return r;
    }

    private static void addMonths(DBReader.SkipStatistics r, int year, int[] monthHrs) {
        for (int m = 0; m < monthHrs.length; m++) {
            DBReader.MonthlySkipItem item = new DBReader.MonthlySkipItem();
            item.year = year;
            item.month = m + 1;
            item.totalMs = hoursToMs(monthHrs[m]);
            r.monthly.add(item);
        }
    }

    private static long hoursToMs(double hours) {
        return (long) (hours * 3_600_000.0);
    }

    private static long minutesToMs(double minutes) {
        return (long) (minutes * 60_000.0);
    }
}
