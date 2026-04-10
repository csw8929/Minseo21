package com.example.minseo21;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "playback_position")
public class PlaybackPosition {
    @PrimaryKey
    @NonNull
    public String uri = "";
    public String name;
    public String bucketId; // 폴더 ID 추가
    public long positionMs;
    public long updatedAt;
    public int  subtitleTrackId = Integer.MIN_VALUE;
    public int  audioTrackId    = Integer.MIN_VALUE;
    public int  screenMode      = -1;
}
