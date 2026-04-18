package com.example.minseo21;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class FavoriteAdapter extends RecyclerView.Adapter<FavoriteAdapter.ViewHolder> {

    interface Listener {
        void onClick(Favorite fav);
        void onLongClick(Favorite fav);
    }

    private final List<Favorite> items = new ArrayList<>();
    private final Listener listener;
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.getDefault());

    public FavoriteAdapter(Listener listener) { this.listener = listener; }

    public void setItems(List<Favorite> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_file, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int position) {
        Favorite f = items.get(position);
        String prefix = f.isNas ? "[NAS] " : "[LOCAL] ";
        h.tvName.setText(prefix + (f.name != null ? f.name : "(제목 없음)"));
        h.ivIcon.setImageResource(f.isRecent ? R.drawable.ic_star_red : R.drawable.ic_star_yellow);
        String meta = formatTime(f.positionMs) + "  |  " + dateFormat.format(new Date(f.addedAt));
        h.tvMeta.setText(meta);
        h.itemView.setOnClickListener(v -> listener.onClick(f));
        h.itemView.setOnLongClickListener(v -> { listener.onLongClick(f); return true; });
    }

    @Override
    public int getItemCount() { return items.size(); }

    private String formatTime(long ms) {
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s);
        return String.format(Locale.getDefault(), "%d:%02d", m, s);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivIcon;
        TextView tvName, tvMeta;
        ViewHolder(View v) {
            super(v);
            ivIcon = v.findViewById(R.id.ivIcon);
            tvName = v.findViewById(R.id.tvName);
            tvMeta = v.findViewById(R.id.tvMeta);
        }
    }
}
