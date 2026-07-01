package de.danoeh.antennapod.storage.importexport.spotify;

/**
 * Jaro-Winkler string similarity, normalized to 0.0–1.0. Used by
 * {@link PodcastIndexResolver} to score title/author matches against a
 * Spotify show's metadata.
 *
 * <p>Standard reference implementation; verified against the
 * Wikipedia worked example ("MARTHA"/"MARHTA" → 0.961).
 */
public final class JaroWinkler {

    /** Winkler's prefix scaling factor. Bounded to 0.25 by construction
     *  (0.1 × 4 chars) so jaro + bonus ≤ 1.0 even at jaro = 0.6+. */
    private static final double PREFIX_SCALE = 0.1;
    private static final int MAX_PREFIX = 4;

    private JaroWinkler() {
    }

    public static double similarity(String s1, String s2) {
        if (s1 == null) s1 = "";
        if (s2 == null) s2 = "";
        if (s1.isEmpty() && s2.isEmpty()) {
            return 1.0;
        }
        if (s1.isEmpty() || s2.isEmpty()) {
            return 0.0;
        }

        int len1 = s1.length();
        int len2 = s2.length();
        int matchDistance = Math.max(len1, len2) / 2 - 1;
        if (matchDistance < 0) {
            matchDistance = 0;
        }

        boolean[] m1 = new boolean[len1];
        boolean[] m2 = new boolean[len2];
        int matches = 0;
        for (int i = 0; i < len1; i++) {
            int start = Math.max(0, i - matchDistance);
            int end = Math.min(i + matchDistance + 1, len2);
            for (int j = start; j < end; j++) {
                if (m2[j]) {
                    continue;
                }
                if (s1.charAt(i) != s2.charAt(j)) {
                    continue;
                }
                m1[i] = true;
                m2[j] = true;
                matches++;
                break;
            }
        }
        if (matches == 0) {
            return 0.0;
        }

        int transpositions = 0;
        int k = 0;
        for (int i = 0; i < len1; i++) {
            if (!m1[i]) {
                continue;
            }
            while (!m2[k]) {
                k++;
            }
            if (s1.charAt(i) != s2.charAt(k)) {
                transpositions++;
            }
            k++;
        }
        double jaro = (
                (double) matches / len1
                + (double) matches / len2
                + (matches - transpositions / 2.0) / matches
        ) / 3.0;

        int prefix = 0;
        int prefixCap = Math.min(MAX_PREFIX, Math.min(len1, len2));
        for (int i = 0; i < prefixCap; i++) {
            if (s1.charAt(i) == s2.charAt(i)) {
                prefix++;
            } else {
                break;
            }
        }
        return jaro + prefix * PREFIX_SCALE * (1.0 - jaro);
    }
}
