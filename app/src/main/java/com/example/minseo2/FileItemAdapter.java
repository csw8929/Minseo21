package com.example.minseo2;

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

public class FileItemAdapter extends RecyclerView.Adapter<FileItemAdapter.ViewHolder> {

    interface OnClickListener {
        void onClick(VideoItem item);
    }

    private final List<VideoItem> items = new ArrayList<>();
    private final OnClickListener listener;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.getDefault());

    public FileItemAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public void setItems(List<VideoItem> newItems) {
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
        VideoItem item = items.get(position);
        h.tvName.setText(item.name);
        if (item.type == VideoItem.TYPE_FOLDER) {
            h.ivIcon.setImageResource(R.drawable.ic_folder);
            h.tvMeta.setText("");
        } else {
            h.ivIcon.setImageResource(R.drawable.ic_video);
            String meta = formatSize(item.size) + "  |  " + dateFormat.format(new Date(item.dateModified * 1000));
            h.tvMeta.setText(meta);
        }
        h.itemView.setOnClickListener(v -> listener.onClick(item));
    }

    @Override
    public int getItemCount() { return items.size(); }

    private String formatSize(long bytes) {
        if (bytes < 1024 * 1024) return String.format("%.0f KB", bytes / 1024f);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024f * 1024));
        return String.format("%.2f GB", bytes / (1024f * 1024 * 1024));
    }
    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivIcon;
        TextView  tvName, tvMeta;
        ViewHolder(View v) {
            super(v);
            ivIcon = v.findViewById(R.id.ivIcon);
            tvName = v.findViewById(R.id.tvName);
            tvMeta = v.findViewById(R.id.tvMeta);
        }
    }
}
