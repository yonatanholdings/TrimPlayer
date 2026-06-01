package de.danoeh.antennapod.storage.importexport.spotify;

import androidx.annotation.Nullable;

/**
 * One Spotify-sourced subscription waiting to be resolved to an RSS feed URL.
 *
 * <p>Populated from the PortCast document's {@code subscriptions[]} array
 * (see {@code PortcastImporter.PortFeed}). {@link #spotifyShowId} is the
 * stable Spotify identifier extracted from
 * {@code platformRefs: ["spotify:show:<id>"]}; {@link #title} and
 * {@link #author} are what the exporter captured and what the fuzzy-match
 * fallback compares against.
 */
public final class ResolverInput {
    @Nullable public final String spotifyShowId;
    @Nullable public final String title;
    @Nullable public final String author;

    public ResolverInput(@Nullable String spotifyShowId, @Nullable String title, @Nullable String author) {
        this.spotifyShowId = spotifyShowId;
        this.title = title;
        this.author = author;
    }
}
