package com.example.minseo21.xr

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.InputStream

/**
 * MP4 컨테이너의 sv3d / st3d 박스 존재 여부로 [SpatialMode] 추정.
 *
 * Google Spatial Media V2 spec 의 박스 정밀 파싱(트리 descent + payload field 해석) 대신,
 * **byte-grep + mdat 경계 + sanity check** 의 conservative 구현:
 *
 * - moov 영역 안에서 4-byte 마커 `sv3d` / `st3d` 검색
 * - mdat 박스 시작 인덱스를 상한으로 잡아 압축 비디오 데이터 false-positive 차단
 * - 마커 직전 4 byte 가 그럴듯한 box size 인지(8 ~ 16MB) 검증 → false positive 추가 차단
 *
 * 결과:
 * - sv3d 발견 → [SpatialMode.VR180_HEMISPHERE] (구형/반구형 spherical 콘텐츠)
 * - st3d 만 발견 → [SpatialMode.SBS_PANEL] (평면 stereoscopic, sv3d 없음 → flat SBS)
 * - 둘 다 없음 / non-fast-start (mdat 가 moov 앞) / 비-로컬 scheme → [SpatialMode.NONE]
 *   (호출자가 파일명 heuristic 으로 fallback)
 *
 * **read budget 1MB** — moov 가 이보다 크면 NONE. 실제 VR180 MP4 의 moov 는 수십~수백 KB 수준.
 *
 * **content:// / file:// 만 처리.** http(s) 는 NONE — NAS streaming 진입에서 range request
 * 비용을 피하고 파일명 heuristic 에 맡긴다.
 *
 * 정밀 파싱 (st3d.stereo_mode 값 / equi.proj_bounds 해석) 은 v2 후속 — 현 단계는 분류 3-state
 * 만 필요해 byte-grep 으로 충분.
 */
object SpatialMediaParser {

    private const val TAG = "SACH_XR"

    /** 박스 검색 read budget. moov 가 이보다 크면 NONE 반환. */
    private const val MAX_READ_BYTES = 1 * 1024 * 1024  // 1MB

    /** 마커 직전 4-byte 를 BE u32 box size 로 해석했을 때 최소값 (`size + type` 자체가 8). */
    private const val MIN_PLAUSIBLE_BOX_SIZE = 8

    /** 마커 직전 4-byte box size 의 상한 — 16MB 이상이면 false positive 로 간주. */
    private const val MAX_PLAUSIBLE_BOX_SIZE = 16 * 1024 * 1024

    private val MARKER_SV3D: ByteArray = "sv3d".toByteArray(Charsets.US_ASCII)
    private val MARKER_ST3D: ByteArray = "st3d".toByteArray(Charsets.US_ASCII)
    private val MARKER_MDAT: ByteArray = "mdat".toByteArray(Charsets.US_ASCII)

    /**
     * Uri 가 가리키는 MP4 의 moov 박스에서 sv3d / st3d 마커를 찾아 SpatialMode 추정.
     * 실패 / 매칭 없음 / 비-로컬 scheme → [SpatialMode.NONE] 반환 (호출자가 파일명 fallback).
     */
    fun parse(context: Context, uri: Uri?): SpatialMode {
        if (uri == null) return SpatialMode.NONE
        val scheme = uri.scheme?.lowercase()
        if (scheme != "content" && scheme != "file") {
            Log.d(TAG, "SpatialMediaParser: skip non-local scheme=$scheme")
            return SpatialMode.NONE
        }
        return try {
            context.contentResolver.openInputStream(uri)?.use { inp ->
                parseStream(inp)
            } ?: SpatialMode.NONE
        } catch (e: Exception) {
            Log.d(TAG, "SpatialMediaParser open 실패: $e")
            SpatialMode.NONE
        }
    }

    private fun parseStream(input: InputStream): SpatialMode {
        val buf = ByteArray(MAX_READ_BYTES)
        var total = 0
        while (total < MAX_READ_BYTES) {
            val n = input.read(buf, total, MAX_READ_BYTES - total)
            if (n <= 0) break
            total += n
        }
        if (total < 16) return SpatialMode.NONE

        val mdatIdx = indexOf(buf, total, MARKER_MDAT)
        val searchEnd = if (mdatIdx >= 0) mdatIdx else total

        val sv3dIdx = findVerifiedMarker(buf, searchEnd, MARKER_SV3D)
        if (sv3dIdx >= 0) {
            Log.i(TAG, "SpatialMediaParser: sv3d @ $sv3dIdx → VR180_HEMISPHERE (read=$total, mdat@$mdatIdx)")
            return SpatialMode.VR180_HEMISPHERE
        }
        val st3dIdx = findVerifiedMarker(buf, searchEnd, MARKER_ST3D)
        if (st3dIdx >= 0) {
            Log.i(TAG, "SpatialMediaParser: st3d @ $st3dIdx → SBS_PANEL (read=$total, mdat@$mdatIdx)")
            return SpatialMode.SBS_PANEL
        }
        Log.d(TAG, "SpatialMediaParser: no spatial marker (read=$total, search=${searchEnd}B, mdat@$mdatIdx)")
        return SpatialMode.NONE
    }

    /**
     * `[size:u32 BE][marker:4]` 패턴으로 marker 검색 — size 가 그럴듯한 범위 안일 때만 인정.
     * 압축 비디오/오디오 페이로드에 우연히 4-byte 시퀀스가 매치되더라도 직전 size 가
     * 비현실적 범위(거대한 값, 0/1 외 작은 값 등) 면 reject.
     */
    private fun findVerifiedMarker(buf: ByteArray, length: Int, marker: ByteArray): Int {
        var from = 0
        while (true) {
            val idx = indexOf(buf, length, marker, from)
            if (idx < 0) return -1
            if (idx >= 4) {
                val size = ((buf[idx - 4].toInt() and 0xFF) shl 24) or
                        ((buf[idx - 3].toInt() and 0xFF) shl 16) or
                        ((buf[idx - 2].toInt() and 0xFF) shl 8) or
                        (buf[idx - 1].toInt() and 0xFF)
                // size 1 (largesize) 도 정상 — 이후 8 byte 가 실제 size
                if (size == 1 || size in MIN_PLAUSIBLE_BOX_SIZE..MAX_PLAUSIBLE_BOX_SIZE) {
                    return idx
                }
            }
            from = idx + 1
        }
    }

    private fun indexOf(haystack: ByteArray, length: Int, needle: ByteArray, fromIndex: Int = 0): Int {
        if (needle.isEmpty() || needle.size > length) return -1
        val limit = length - needle.size
        outer@ for (i in fromIndex..limit) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
