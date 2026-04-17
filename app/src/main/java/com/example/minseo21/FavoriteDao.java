package com.example.minseo21;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface FavoriteDao {
    @Query("SELECT * FROM favorite ORDER BY addedAt DESC")
    List<Favorite> getAll();

    @Insert
    long insert(Favorite fav);

    @Query("DELETE FROM favorite WHERE id = :id")
    void deleteById(long id);
}
