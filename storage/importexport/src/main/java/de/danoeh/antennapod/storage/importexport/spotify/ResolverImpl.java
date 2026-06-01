package de.danoeh.antennapod.storage.importexport.spotify;

/**
 * One step in the {@link SpotifyShowResolver}'s resolver chain. Implementations
 * are tried in priority order; the first to return {@link Resolution.Resolved}
 * wins, otherwise the chain continues until exhausted.
 *
 * <p>Implementations MUST be blocking and thread-safe — the orchestrator runs
 * up to {@link SpotifyShowResolver#MAX_CONCURRENCY} of these in parallel from a
 * worker pool. Per-call timeouts are enforced by each implementation's own
 * HTTP client config; the orchestrator only enforces the total per-import
 * budget.
 */
public interface ResolverImpl {
    /** Blocking. Never throws — failures must be returned as
     *  {@link Resolution.Unresolvable} so the chain can advance. */
    Resolution resolve(ResolverInput input);

    /** Short stable identifier, used in {@link Resolution.Resolved#source}
     *  and in {@link Resolution.Unresolvable#reason} prefixes. */
    String name();
}
