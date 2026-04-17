package com.example.minseo21;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {PlaybackPosition.class, Favorite.class}, version = 3, exportSchema = false)
public abstract class PlaybackDatabase extends RoomDatabase {
    private static volatile PlaybackDatabase instance;

    public abstract PlaybackDao playbackDao();
    public abstract FavoriteDao favoriteDao();

    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE playback_position ADD COLUMN subtitleTrackId INTEGER NOT NULL DEFAULT " + Integer.MIN_VALUE);
            db.execSQL("ALTER TABLE playback_position ADD COLUMN audioTrackId    INTEGER NOT NULL DEFAULT " + Integer.MIN_VALUE);
            db.execSQL("ALTER TABLE playback_position ADD COLUMN screenMode      INTEGER NOT NULL DEFAULT -1");
        }
    };

    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS favorite (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "uri TEXT NOT NULL, " +
                "name TEXT, " +
                "isNas INTEGER NOT NULL, " +
                "nasPath TEXT, " +
                "bucketId TEXT, " +
                "bucketDisplayName TEXT, " +
                "positionMs INTEGER NOT NULL, " +
                "addedAt INTEGER NOT NULL)"
            );
        }
    };

    public static PlaybackDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (PlaybackDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                            context.getApplicationContext(),
                            PlaybackDatabase.class,
                            "playback.db"
                    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build();
                }
            }
        }
        return instance;
    }
}
