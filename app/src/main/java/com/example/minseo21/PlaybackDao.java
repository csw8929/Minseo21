package com.example.minseo21;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface PlaybackDao {
    @Query("SELECT * FROM playback_position WHERE uri = :uri LIMIT 1")
    PlaybackPosition getPosition(String uri);

    @Query("SELECT * FROM playback_position ORDER BY updatedAt DESC LIMIT 1")
    PlaybackPosition getLastPosition();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void savePosition(PlaybackPosition position);

    @Query("DELETE FROM playback_position WHERE uri = :uri")
    void clearPosition(String uri);

    @Query("DELETE FROM playback_position")
    void clearAll();
}
