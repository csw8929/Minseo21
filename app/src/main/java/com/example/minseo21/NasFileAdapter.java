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

public class NasFileAdapter extends RecyclerView.Adapter<NasFileAdapter.ViewHolder> {

    interface OnClickListener {
        void onClick(VideoItem item);
    }

    private final List<VideoItem> items = new ArrayList<>();
    private final OnClickListener listener;
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.getDefault());
    private String highlightNasPath;

    public NasFileAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public void setItems(List<VideoItem> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    /** @return 하이라이트된 항목의 position. 없으면 -1. */
    public int setHighlightNasPath(String nasPath) {
        this.highlightNasPath = nasPath;
        notifyDataSetChanged();
        if (nasPath == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            VideoItem it = items.get(i);
            if (it.type == VideoItem.TYPE_VIDEO && nasPath.equals(it.nasPath)) return i;
        }
        return -1;
    }

    /** 재생 목록 생성용: 폴더 제외 파일만 반환 */
    public List<VideoItem> getAllFiles() {
        List<VideoItem> files = new ArrayList<>();
        for (VideoItem v : items) {
            if (v.type == VideoItem.TYPE_VIDEO) files.add(v);
        }
        return files;
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
        boolean highlighted = item.type == VideoItem.TYPE_VIDEO
                && highlightNasPath != null && item.nasPath != null
                && highlightNasPath.equals(item.nasPath);
        h.tvName.setTextColor(highlighted ? 0xFF4A90D9 : 0xFFEEEEEE);
        if (item.type == VideoItem.TYPE_FOLDER) {
            h.ivIcon.setImageResource(R.drawable.ic_folder);
            h.tvMeta.setText("");
        } else {
            h.ivIcon.setImageResource(R.drawable.ic_video);
            String meta = formatSize(item.size);
            if (item.dateModified > 0) {
                meta += "  |  " + dateFormat.format(new Date(item.dateModified * 1000));
            }
            h.tvMeta.setText(meta);
        }
        h.itemView.setOnClickListener(v -> listener.onClick(item));
    }

    @Override
    public int getItemCount() { return items.size(); }

    private String formatSize(long bytes) {
        if (bytes <= 0) return "";
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
