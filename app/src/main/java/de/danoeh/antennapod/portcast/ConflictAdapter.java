package de.danoeh.antennapod.portcast;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.danoeh.antennapod.R;

/**
 * Grouped, expandable conflict-resolution adapter shared by the
 * PodcastAddict and PortCast importers. Rows can be grouped by feed
 * (default) or by last-played date; each section header has a
 * "use {source}" switch that batch-toggles all its rows.
 *
 * <p>Constructor takes a {@link Context} so the adapter can be hosted by
 * both a Fragment and an Activity without a circular Fragment dependency.
 */
public final class ConflictAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_EPISODE = 1;

    private final Context context;
    private final List<ConflictRow> allConflicts;
    private final TextView summaryView;
    private final String sourceLabel;
    private boolean groupByDate = false;

    private final List<Section> sections = new ArrayList<>();
    /** Mixed list of Section + ConflictRow entries flattened in render order
     *  (a Section followed by its children when expanded). */
    private final List<Object> flatList = new ArrayList<>();

    public ConflictAdapter(Context context, List<ConflictRow> conflicts,
                           String sourceLabel, TextView summary) {
        this.context = context;
        this.allConflicts = conflicts;
        this.sourceLabel = sourceLabel;
        this.summaryView = summary;
        rebuild();
    }

    public void setGroupMode(boolean byDate) {
        this.groupByDate = byDate;
        rebuild();
    }

    public void setAll(boolean useIncoming) {
        for (ConflictRow c : allConflicts) c.useIncoming = useIncoming;
        refreshFlatList();
        updateSummary();
    }

    // ── Section ─────────────────────────────────────────────────────────

    private class Section {
        final String title;
        final List<ConflictRow> episodes = new ArrayList<>();
        boolean expanded = false;

        Section(String title) { this.title = title; }

        /** null = mixed; true = all from import; false = all existing. */
        Boolean groupState() {
            boolean anyIncoming = false;
            boolean anyExisting = false;
            for (ConflictRow e : episodes) {
                if (e.useIncoming) anyIncoming = true; else anyExisting = true;
            }
            if (anyIncoming && anyExisting) return null;
            return anyIncoming;
        }

        void setAll(boolean useIncoming) {
            for (ConflictRow e : episodes) e.useIncoming = useIncoming;
        }
    }

    // ── Build ────────────────────────────────────────────────────────────

    private void rebuild() {
        sections.clear();
        Map<String, Section> map = new LinkedHashMap<>();

        long now = System.currentTimeMillis();
        long weekAgo = now - 7L * 86400000L;
        long monthAgo = now - 30L * 86400000L;
        Calendar cal = Calendar.getInstance();
        int thisYear = cal.get(Calendar.YEAR);

        for (ConflictRow c : allConflicts) {
            String key;
            if (groupByDate) {
                long date = c.lastPlayedMs;
                if (date <= 0) {
                    key = context.getString(R.string.podcast_addict_date_unknown);
                } else if (date >= weekAgo) {
                    key = context.getString(R.string.podcast_addict_date_this_week);
                } else if (date >= monthAgo) {
                    key = context.getString(R.string.podcast_addict_date_this_month);
                } else {
                    cal.setTimeInMillis(date);
                    key = cal.get(Calendar.YEAR) == thisYear
                            ? context.getString(R.string.podcast_addict_date_this_year)
                            : context.getString(R.string.podcast_addict_date_older);
                }
            } else {
                key = c.feedTitle;
            }
            if (!map.containsKey(key)) {
                map.put(key, new Section(key));
            }
            map.get(key).episodes.add(c);
        }

        if (!groupByDate) {
            List<Section> sorted = new ArrayList<>(map.values());
            sorted.sort((a, b) -> a.title.compareToIgnoreCase(b.title));
            sections.addAll(sorted);
        } else {
            sections.addAll(map.values());
        }

        refreshFlatList();
        updateSummary();
    }

    private void refreshFlatList() {
        flatList.clear();
        for (Section s : sections) {
            flatList.add(s);
            if (s.expanded) flatList.addAll(s.episodes);
        }
        notifyDataSetChanged();
    }

    private void updateSummary() {
        long incomingCount = 0;
        for (ConflictRow c : allConflicts) if (c.useIncoming) incomingCount++;
        summaryView.setText(context.getString(R.string.import_summary_source,
                (int) incomingCount, allConflicts.size(), sourceLabel));
    }

    // ── Adapter ──────────────────────────────────────────────────────────

    @Override
    public int getItemViewType(int pos) {
        return flatList.get(pos) instanceof Section ? TYPE_HEADER : TYPE_EPISODE;
    }

    @Override
    public int getItemCount() { return flatList.size(); }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderVH(inf.inflate(R.layout.item_conflict_section_header, parent, false));
        }
        return new EpisodeVH(inf.inflate(R.layout.item_conflict_episode, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof HeaderVH) {
            bindHeader((HeaderVH) holder, (Section) flatList.get(position));
        } else {
            bindEpisode((EpisodeVH) holder, (ConflictRow) flatList.get(position));
        }
    }

    private void bindHeader(HeaderVH h, Section section) {
        h.title.setText(section.title);

        Boolean gs = section.groupState();
        String stateLabel = gs == null
                ? context.getString(R.string.podcast_addict_section_mixed)
                : (gs ? context.getString(R.string.import_section_all_source, sourceLabel)
                       : context.getString(R.string.podcast_addict_section_all_ap));
        h.subtitle.setText(context.getString(R.string.podcast_addict_section_episodes,
                section.episodes.size(), stateLabel));

        h.expandIcon.setRotation(section.expanded ? 0f : -90f);

        h.groupSwitch.setOnCheckedChangeListener(null);
        h.groupSwitch.setChecked(gs == null || Boolean.TRUE.equals(gs));
        h.groupSwitch.setAlpha(gs == null ? 0.5f : 1f);
        h.groupSwitch.setOnCheckedChangeListener((btn, checked) -> {
            section.setAll(checked);
            int idx = flatList.indexOf(section);
            notifyItemChanged(idx);
            if (section.expanded) {
                notifyItemRangeChanged(idx + 1, section.episodes.size());
            }
            updateSummary();
        });

        h.itemView.setOnClickListener(v -> {
            int idx = flatList.indexOf(section);
            section.expanded = !section.expanded;
            if (section.expanded) {
                flatList.addAll(idx + 1, section.episodes);
                notifyItemChanged(idx);
                notifyItemRangeInserted(idx + 1, section.episodes.size());
            } else {
                flatList.subList(idx + 1, idx + 1 + section.episodes.size()).clear();
                notifyItemChanged(idx);
                notifyItemRangeRemoved(idx + 1, section.episodes.size());
            }
        });
    }

    private void bindEpisode(EpisodeVH h, ConflictRow conflict) {
        h.title.setText(conflict.episodeTitle);
        h.apState.setText(conflict.apStateDescription);
        h.paState.setText(conflict.incomingStateDescription);
        h.toggle.setOnCheckedChangeListener(null);
        h.toggle.setChecked(conflict.useIncoming);
        h.toggle.setOnCheckedChangeListener((btn, checked) -> {
            conflict.useIncoming = checked;
            for (Section s : sections) {
                int idx = flatList.indexOf(s);
                if (s.episodes.contains(conflict) && idx >= 0) {
                    notifyItemChanged(idx);
                    break;
                }
            }
            updateSummary();
        });
    }

    // ── ViewHolders ───────────────────────────────────────────────────────

    static class HeaderVH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView subtitle;
        final ImageView expandIcon;
        final MaterialSwitch groupSwitch;

        HeaderVH(View v) {
            super(v);
            title = v.findViewById(R.id.sectionTitle);
            subtitle = v.findViewById(R.id.sectionSubtitle);
            expandIcon = v.findViewById(R.id.expandIcon);
            groupSwitch = v.findViewById(R.id.groupSwitch);
        }
    }

    static class EpisodeVH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView apState;
        final TextView paState;
        final MaterialSwitch toggle;

        EpisodeVH(View v) {
            super(v);
            title = v.findViewById(R.id.episodeTitle);
            apState = v.findViewById(R.id.apState);
            paState = v.findViewById(R.id.paState);
            toggle = v.findViewById(R.id.usePaSwitch);
        }
    }
}
