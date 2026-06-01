package de.danoeh.antennapod.storage.importexport.spotify;

/**
 * Primary resolver — once the backend's {@code POST /resolve/spotify}
 * endpoint exists. See {@code trimplayer-android-migration-plan.md} §2.4
 * for the contract.
 *
 * <p>Until then this is a stub that always returns
 * {@link Resolution.Unresolvable} so {@link SpotifyShowResolver}'s chain
 * falls through to {@link PodcastIndexResolver} without spending budget on
 * a 404.
 *
 * <p>TODO: wire up the real HTTP call. Use the same OkHttp + Authorization
 * pattern as {@code TrimEventsUploadWorker}. Cache 404 responses for the
 * lifetime of the import so repeat lookups for the same id don't re-spend
 * the budget.
 */
public final class TrimBrainResolver implements ResolverImpl {

    private static final String NAME = "trimbrain";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Resolution resolve(ResolverInput input) {
        return new Resolution.Unresolvable(NAME + "-not-implemented");
    }
}
