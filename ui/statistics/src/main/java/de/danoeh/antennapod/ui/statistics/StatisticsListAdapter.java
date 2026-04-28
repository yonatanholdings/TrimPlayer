package de.danoeh.antennapod.ui.statistics;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;

import de.danoeh.antennapod.storage.database.StatisticsItem;
import java.util.List;

/**
 * Parent Adapter for the playback and download statistics list.
 */
public abstract class StatisticsListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_FEED = 1;
    protected final Context context;
    private List<StatisticsItem> statisticsData = new java.util.ArrayList<>();
    protected PieChartView.PieChartData pieChartData = new PieChartView.PieChartData(new float[0]);

    protected StatisticsListAdapter(Context context) {
        this.context = context;
    }

    @Override
    public int getItemCount() {
        return statisticsData.size() + 1;
    }

    @Override
    public int getItemViewType(int position) {
        return position == 0 ? TYPE_HEADER : TYPE_FEED;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(context);
        if (viewType == TYPE_HEADER) {
            return new HeaderHolder(inflater.inflate(R.layout.statistics_listitem_total, parent, false));
        }
        return new StatisticsHolder(inflater.inflate(R.layout.statistics_listitem, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
        if (getItemViewType(position) == TYPE_HEADER) {
            HeaderHolder holder = (HeaderHolder) h;
            holder.pieChart.setData(pieChartData);
            holder.totalTime.setText(getHeaderValue());
            holder.totalText.setText(getHeaderCaption());
            String savedValue = getHeaderSavedValue();
            if (savedValue != null) {
                holder.totalSavedTime.setText(savedValue);
                holder.totalSavedTime.setVisibility(View.VISIBLE);
                holder.totalSavedLabel.setText(getHeaderSavedCaption());
                holder.totalSavedLabel.setVisibility(View.VISIBLE);
            } else {
                holder.totalSavedTime.setVisibility(View.GONE);
                holder.totalSavedLabel.setVisibility(View.GONE);
            }
        } else {
            StatisticsHolder holder = (StatisticsHolder) h;
            StatisticsItem statsItem = statisticsData.get(position - 1);
            Glide.with(context)
                    .load(statsItem.feed.getImageUrl())
                    .apply(new RequestOptions()
                            .placeholder(R.color.light_gray)
                            .error(R.color.light_gray)
                            .fitCenter()
                            .dontAnimate())
                    .into(holder.image);

            holder.title.setText(statsItem.feed.getTitle());
            holder.chip.setTextColor(pieChartData.getColorOfItem(position - 1));
            onBindFeedViewHolder(holder, statsItem);
        }
    }

    public void update(List<StatisticsItem> statistics) {
        statisticsData = statistics;
        pieChartData = generateChartData(statistics);
        notifyDataSetChanged();
    }

    static class HeaderHolder extends RecyclerView.ViewHolder {
        TextView totalTime;
        PieChartView pieChart;
        TextView totalText;
        TextView totalSavedTime;
        TextView totalSavedLabel;

        HeaderHolder(View itemView) {
            super(itemView);
            totalTime = itemView.findViewById(R.id.total_time);
            pieChart = itemView.findViewById(R.id.pie_chart);
            totalText = itemView.findViewById(R.id.total_description);
            totalSavedTime = itemView.findViewById(R.id.total_saved_time);
            totalSavedLabel = itemView.findViewById(R.id.total_saved_label);
        }
    }

    public static class StatisticsHolder extends RecyclerView.ViewHolder {
        public ImageView image;
        public TextView title;
        public TextView value;
        public TextView chip;
        public TextView savedValue;

        StatisticsHolder(View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.imgvCover);
            title = itemView.findViewById(R.id.txtvTitle);
            value = itemView.findViewById(R.id.txtvValue);
            chip = itemView.findViewById(R.id.chip);
            savedValue = itemView.findViewById(R.id.txtvSavedValue);
        }
    }

    protected abstract String getHeaderCaption();

    protected abstract String getHeaderValue();

    /** Return a formatted saved-time string to show below the chart, or null to hide it. */
    protected String getHeaderSavedValue() {
        return null;
    }

    protected String getHeaderSavedCaption() {
        return context.getString(R.string.statistics_total_time_saved);
    }

    protected abstract PieChartView.PieChartData generateChartData(List<StatisticsItem> statisticsData);

    protected abstract void onBindFeedViewHolder(StatisticsHolder holder, StatisticsItem item);
}
