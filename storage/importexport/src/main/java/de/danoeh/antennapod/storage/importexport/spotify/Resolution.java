package de.danoeh.antennapod.storage.importexport.spotify;

/**
 * Outcome of a single {@link ResolverInput} → feed URL attempt. Two cases:
 *
 * <ul>
 *   <li>{@link Resolved} — a feed URL we'll subscribe to, with a confidence
 *       score and the name of the resolver that produced it.</li>
 *   <li>{@link Unresolvable} — we couldn't (or chose not to) map this show
 *       to a feed; the {@code reason} string is for telemetry/debugging,
 *       not for showing to users.</li>
 * </ul>
 *
 * <p>Modelled as a POJO discriminated union (not a Java 17 sealed class) to
 * match the convention used elsewhere in this module.
 */
public abstract class Resolution {

    public boolean isResolved() {
        return this instanceof Resolved;
    }

    public static final class Resolved extends Resolution {
        public final String feedUrl;
        /** 0.0 – 1.0. For TrimBrain hits this is the backend's reported confidence;
         *  for the PodcastIndex fallback it's the title+author similarity score. */
        public final double confidence;
        /** Resolver name: "trimbrain", "podcastindex", etc. */
        public final String source;

        public Resolved(String feedUrl, double confidence, String source) {
            this.feedUrl = feedUrl;
            this.confidence = confidence;
            this.source = source;
        }
    }

    public static final class Unresolvable extends Resolution {
        /** Machine-readable tag: "trimbrain-not-implemented",
         *  "podcastindex-below-threshold", "budget-exceeded", etc. */
        public final String reason;

        public Unresolvable(String reason) {
            this.reason = reason;
        }
    }
}
