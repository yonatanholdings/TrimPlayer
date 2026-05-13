package de.danoeh.antennapod.ui.echo;

import java.util.Calendar;

public class EchoConfig {
    public static int RELEASE_YEAR = Calendar.getInstance().get(Calendar.YEAR) - 1;

    public static long jan1() {
        return startOfYear(RELEASE_YEAR);
    }

    public static long endTime() {
        return startOfYear(RELEASE_YEAR + 1);
    }

    private static long startOfYear(int year) {
        Calendar date = Calendar.getInstance();
        date.set(Calendar.HOUR_OF_DAY, 0);
        date.set(Calendar.MINUTE, 0);
        date.set(Calendar.SECOND, 0);
        date.set(Calendar.MILLISECOND, 0);
        date.set(Calendar.DAY_OF_MONTH, 1);
        date.set(Calendar.MONTH, 0);
        date.set(Calendar.YEAR, year);
        return date.getTimeInMillis();
    }

    public static boolean isCurrentlyVisible() {
        return true;
    }
}
