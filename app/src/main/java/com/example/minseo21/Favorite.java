package com.example.minseo21;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

/** 즐겨찾기 항목 — 재생 위치까지 포함하므로 같은 파일도 여러 번 저장될 수 있음. */
@Entity(tableName = "favorite")
public class Favorite {
    @PrimaryKey(autoGenerate = true)
    public long id;

    /** 로컬: content:// URI, NAS: canonical URL (SID 제외) */
    @NonNull
    public String uri = "";

    public String name;
    public boolean isNas;

    /** NAS 전용: /video/폴더/파일.mkv — SID 재발급 시 스트림 URL 재생성용 */
    public String nasPath;

    /** 로컬 전용: 같은 폴더 플레이리스트 복원용 */
    public String bucketId;
    public String bucketDisplayName;

    public long positionMs;
    public long addedAt;

    /** 목록 상단의 "마지막 재생" 합성 항목인지 여부 (DB 저장 안 함, 빨간 별표 표시용) */
    @Ignore
    public boolean isRecent = false;
}
