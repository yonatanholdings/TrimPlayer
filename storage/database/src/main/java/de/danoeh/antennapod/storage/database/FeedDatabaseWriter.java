package de.danoeh.antennapod.storage.database;

import android.content.Context;
import android.util.Log;
import de.danoeh.antennapod.event.FeedListUpdateEvent;
import de.danoeh.antennapod.event.NewEpisodesPrefetchEvent;
import de.danoeh.antennapod.model.download.DownloadError;
import de.danoeh.antennapod.model.download.DownloadResult;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.feed.SortOrder;
import de.danoeh.antennapod.net.sync.serviceinterface.EpisodeAction;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueue;
import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Creates and updates feeds in the database.
 */
public abstract class FeedDatabaseWriter {
    private static final String TAG = "FeedDbWriter";

    private static Feed searchFeedByIdentifyingValueOrID(Feed feed) {
        if (feed.getId() != 0) {
            return DBReader.getFeed(feed.getId(), false, 0, Integer.MAX_VALUE);
        } else {
            List<Feed> feeds = DBReader.getFeedList();
            for (Feed f : feeds) {
                if (f.getIdentifyingValue().equals(feed.getIdentifyingValue())) {
                    f.setItems(DBReader.getFeedItemList(f, FeedItemFilter.unfiltered(),
                            SortOrder.DATE_NEW_OLD, 0, Integer.MAX_VALUE));
                    return f;
                }
            }
        }
        return null;
    }

    /**
     * Adds new Feeds to the database or updates the old versions if they already exists. If another Feed with the same
     * identifying value already exists, this method will add new FeedItems from the new Feed to the existing Feed.
     * These FeedItems will be marked as unread with the exception of the most recent FeedItem.
     *
     * @param context Used for accessing the DB.
     * @param newFeed The new Feed object.
     * @param removeUnlistedItems The item list in the new Feed object is considered to be exhaustive.
     *                            I.e. items are removed from the database if they are not in this item list.
     * @return The updated Feed from the database if it already existed, or the new Feed from the parameters otherwise.
     */
    public static synchronized Feed updateFeed(Context context, Feed newFeed, boolean removeUnlistedItems) {
        Feed resultFeed;
        List<FeedItem> unlistedItems = new ArrayList<>();
        List<FeedItem> genuinelyNewItems = new ArrayList<>();
        List<NewEpisodesPrefetchEvent.Item> trimPrefetchItems = new ArrayList<>();

        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();

        // Look up feed in the feedslist
        final Feed savedFeed = searchFeedByIdentifyingValueOrID(newFeed);
        if (savedFeed == null) {
            Log.d(TAG, "Found no existing Feed with title "
                            + newFeed.getTitle() + ". Adding as new one.");

            resultFeed = newFeed;
        } else {
            Log.d(TAG, "Feed with title " + newFeed.getTitle()
                        + " already exists. Syncing new with existing one.");

            Collections.sort(newFeed.getItems(), new FeedItemPubdateComparator());
            FeedItemDuplicateGuesserPool newFeedDuplicateGuesser = new FeedItemDuplicateGuesserPool(newFeed.getItems());
            FeedItemDuplicateGuesserPool savedFeedDuplicateGuesser
                    = new FeedItemDuplicateGuesserPool(savedFeed.getItems());

            if (newFeed.getPageNr() == savedFeed.getPageNr()) {
                savedFeed.updateFromOther(newFeed);
                savedFeed.getPreferences().updateFromOther(newFeed.getPreferences());
            } else {
                Log.d(TAG, "New feed has a higher page number.");
                savedFeed.setNextPageLink(newFeed.getNextPageLink());
            }

            // get the most recent date now, before we start changing the list
            FeedItem priorMostRecent = savedFeed.getMostRecentItem();
            Date priorMostRecentDate = new Date();
            if (priorMostRecent != null) {
                priorMostRecentDate = priorMostRecent.getPubDate();
            }

            // Look for new or updated Items
            for (int idx = 0; idx < newFeed.getItems().size(); idx++) {
                final FeedItem item = newFeed.getItems().get(idx);

                FeedItem possibleDuplicate = newFeedDuplicateGuesser.guessDuplicate(item);
                if (!newFeed.isLocalFeed() && possibleDuplicate != null && item != possibleDuplicate) {
                    // Canonical episode is the first one returned (usually oldest)
                    DBWriter.addDownloadStatus(new DownloadResult(item.getTitle(),
                            savedFeed.getId(), Feed.FEEDFILETYPE_FEED, false,
                            DownloadError.ERROR_PARSER_EXCEPTION_DUPLICATE,
                            "The podcast host appears to have added the same episode twice. "
                                    + "AntennaPod still refreshed the feed and attempted to repair it."
                                    + "\n\nOriginal episode:\n" + duplicateEpisodeDetails(item)
                                    + "\n\nSecond episode that is also in the feed:\n"
                                    + duplicateEpisodeDetails(possibleDuplicate)));
                    continue;
                }

                FeedItem oldItem = savedFeedDuplicateGuesser.findById(item);
                if (!newFeed.isLocalFeed() && oldItem == null) {
                    oldItem = savedFeedDuplicateGuesser.guessDuplicate(item);
                    if (oldItem != null) {
                        Log.d(TAG, "Repaired duplicate: " + oldItem + ", " + item);
                        DBWriter.addDownloadStatus(new DownloadResult(item.getTitle(),
                                savedFeed.getId(), Feed.FEEDFILETYPE_FEED, false,
                                DownloadError.ERROR_PARSER_EXCEPTION_DUPLICATE,
                                "The podcast host changed the ID of an existing episode instead of just "
                                        + "updating the episode itself. AntennaPod still refreshed the feed and "
                                        + "attempted to repair it."
                                        + "\n\nOriginal episode:\n" + duplicateEpisodeDetails(oldItem)
                                        + "\n\nNow the feed contains:\n" + duplicateEpisodeDetails(item)));
                        oldItem.setItemIdentifier(item.getItemIdentifier());

                        if (oldItem.isPlayed() && oldItem.getMedia() != null
                                && savedFeed.getState() != Feed.STATE_NOT_SUBSCRIBED) {
                            EpisodeAction action = new EpisodeAction.Builder(oldItem, EpisodeAction.PLAY)
                                    .currentTimestamp()
                                    .started(oldItem.getMedia().getDuration() / 1000)
                                    .position(oldItem.getMedia().getDuration() / 1000)
                                    .total(oldItem.getMedia().getDuration() / 1000)
                                    .build();
                            SynchronizationQueue.getInstance().enqueueEpisodeAction(action);
                        }
                    }
                }

                if (oldItem != null) {
                    oldItem.updateFromOther(item);
                } else {
                    Log.d(TAG, "Found new item: " + item.getTitle());
                    item.setFeed(savedFeed);

                    if (idx >= savedFeed.getItems().size()) {
                        savedFeed.getItems().add(item);
                    } else {
                        savedFeed.getItems().add(idx, item);
                    }
                    savedFeedDuplicateGuesser.add(item);

                    boolean shouldPerformNewEpisodesAction = item.getPubDate() == null
                            || priorMostRecentDate == null
                            || priorMostRecentDate.before(item.getPubDate())
                            || priorMostRecentDate.equals(item.getPubDate());
                    if (savedFeed.getState() == Feed.STATE_SUBSCRIBED && shouldPerformNewEpisodesAction) {
                        genuinelyNewItems.add(item);
                        if (!savedFeed.isLocalFeed() && item.getMedia() != null) {
                            String mediaUrl = item.getMedia().getDownloadUrl();
                            String rssUrl = savedFeed.getDownloadUrl();
                            if (mediaUrl != null && !mediaUrl.isEmpty()
                                    && rssUrl != null && !rssUrl.isEmpty()) {
                                trimPrefetchItems.add(new NewEpisodesPrefetchEvent.Item(
                                        rssUrl, mediaUrl, item.getItemIdentifier()));
                            }
                        }
                        // Inbox deprecation: new episodes are no longer flagged NEW or
                        // routed by NewEpisodesAction — playlist auto-add rules (below,
                        // applyPlaylistAutoRules) are the only router. Feeds whose legacy
                        // setting was ADD_TO_QUEUE were converted to rules on the default
                        // playlist by InboxDeprecationMigration.
                    }
                }
            }

            // identify items to be removed
            if (removeUnlistedItems) {
                Iterator<FeedItem> it = savedFeed.getItems().iterator();
                while (it.hasNext()) {
                    FeedItem feedItem = it.next();
                    if (newFeedDuplicateGuesser.findById(feedItem) == null) {
                        unlistedItems.add(feedItem);
                        it.remove();
                    }
                }
            }

            // update attributes
            savedFeed.setLastModified(newFeed.getLastModified());
            savedFeed.setType(newFeed.getType());
            savedFeed.setLastUpdateFailed(false);

            resultFeed = savedFeed;
        }

        try {
            if (savedFeed == null) {
                DBWriter.addNewFeed(context, newFeed).get();
                // Update with default values that are set in database
                resultFeed = searchFeedByIdentifyingValueOrID(newFeed);
            } else {
                DBWriter.setCompleteFeed(savedFeed).get();
            }
            if (removeUnlistedItems) {
                DBWriter.deleteFeedItems(context, unlistedItems).get();
            }
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }

        // Playlist auto-add rules: append this refresh's genuinely-new episodes to
        // every playlist watching this show. Rule creation time is the cutoff, so
        // a rule never pulls in episodes published before it existed. Runs after
        // the items are saved (they need DB ids to enter a playlist).
        if (savedFeed != null && !genuinelyNewItems.isEmpty()) {
            applyPlaylistAutoRules(savedFeed.getId(), genuinelyNewItems);
        }

        adapter.close();

        if (savedFeed != null) {
            EventBus.getDefault().post(new FeedListUpdateEvent(savedFeed));
        } else {
            EventBus.getDefault().post(new FeedListUpdateEvent(Collections.emptyList()));
        }

        if (!trimPrefetchItems.isEmpty()) {
            EventBus.getDefault().post(new NewEpisodesPrefetchEvent(trimPrefetchItems));
        }

        return resultFeed;
    }

    /** Append new episodes (published after each rule's cutoff) to the playlists
     *  auto-fed by this show. Oldest first, so a multi-episode drop lands in
     *  listening order. */
    private static void applyPlaylistAutoRules(long feedId, List<FeedItem> newItems) {
        java.util.Map<Long, Long> rules = DBReader.getAutoRulesForFeed(feedId);
        if (rules.isEmpty()) {
            return;
        }
        List<FeedItem> ordered = new ArrayList<>(newItems);
        Collections.sort(ordered, (a, b) -> {
            long ta = a.getPubDate() == null ? 0 : a.getPubDate().getTime();
            long tb = b.getPubDate() == null ? 0 : b.getPubDate().getTime();
            return Long.compare(ta, tb);
        });
        for (java.util.Map.Entry<Long, Long> rule : rules.entrySet()) {
            List<FeedItem> eligible = new ArrayList<>();
            for (FeedItem item : ordered) {
                // No pubDate = can't prove it predates the rule; treat as new.
                if (item.getPubDate() == null || item.getPubDate().getTime() >= rule.getValue()) {
                    eligible.add(item);
                }
            }
            if (!eligible.isEmpty()) {
                Log.d(TAG, "auto-adding " + eligible.size() + " episode(s) to playlist " + rule.getKey());
                DBWriter.addPlaylistItems(rule.getKey(), eligible.toArray(new FeedItem[0]));
            }
        }
    }

    private static String duplicateEpisodeDetails(FeedItem item) {
        return "Title: " + item.getTitle()
                + "\nID: " + item.getItemIdentifier()
                + ((item.getMedia() == null) ? "" : "\nURL: " + item.getMedia().getDownloadUrl());
    }
}
