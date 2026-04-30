package com.example.minseo21.xr

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.io.InputStream

/**
 * MP4 컨테이너의 sv3d / st3d / equi 박스 존재 여부로 [SpatialMode] 추정.
 *
 * Google Spatial Media V2 spec 의 박스 정밀 파싱(트리 descent + payload field 해석) 대신,
 * **byte-grep + mdat 경계 + sanity check** 의 conservative 구현:
 *
 * - moov 영역 안에서 4-byte 마커 `sv3d` / `st3d` 검색
 * - mdat 박스 시작 인덱스를 상한으로 잡아 압축 비디오 데이터 false-positive 차단
 * - 마커 직전 4 byte 가 그럴듯한 box size 인지(8 ~ 16MB) 검증 → false positive 추가 차단
 *
 * 결과:
 * - sv3d + equi.proj_bounds 모두 0 → [SpatialMode.VR360_SPHERE] (트림 없는 전방위)
 * - sv3d 발견 (equi 미발견 또는 bounds 가 0 이 아님) → [SpatialMode.VR180_HEMISPHERE] (앞 180°)
 * - st3d 만 발견 → [SpatialMode.SBS_PANEL] (평면 stereoscopic, sv3d 없음 → flat SBS)
 * - 둘 다 없음 / non-fast-start (mdat 가 moov 앞) / 비-로컬 scheme → [SpatialMode.NONE]
 *   (호출자가 파일명 heuristic 으로 fallback)
 *
 * **read budget 1MB** — moov 가 이보다 크면 NONE. 실제 VR180 MP4 의 moov 는 수십~수백 KB 수준.
 *
 * **content:// / file:// 만 처리.** http(s) 는 NONE — NAS streaming 진입에서 range request
 * 비용을 피하고 파일명 heuristic 에 맡긴다.
 *
 * st3d.stereo_mode 값 (1=top-bottom / 2=SBS) 정밀 해석은 후속 (P4+).
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
    private val MARKER_EQUI: ByteArray = "equi".toByteArray(Charsets.US_ASCII)
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

    @VisibleForTesting
    internal fun parseStream(input: InputStream): SpatialMode {
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
            val sphericalMode = classifySphericalMode(buf, searchEnd, sv3dIdx)
            Log.i(TAG, "SpatialMediaParser: sv3d @ $sv3dIdx → $sphericalMode (read=$total, mdat@$mdatIdx)")
            return sphericalMode
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
    private fun findVerifiedMarker(
        buf: ByteArray,
        length: Int,
        marker: ByteArray,
        fromIndex: Int = 0,
    ): Int {
        var from = fromIndex
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

    /**
     * sv3d 발견 후 그 이후 영역에서 `equi` 박스를 찾아 proj_bounds 4 필드를 읽어 분류.
     *
     * equi 박스 구조 (Spatial Media V2):
     * ```
     * [size:u32][type:'equi']  (8 byte header)
     * [version:u8][flags:u24]  (4 byte)
     * [bounds_top:u32]
     * [bounds_bottom:u32]
     * [bounds_left:u32]
     * [bounds_right:u32]       (총 28 byte)
     * ```
     * bounds 는 0.0.32 fixed-point (1.0 = 0xFFFFFFFF).
     *
     * - 모두 0 → 트림 없음 → [SpatialMode.VR360_SPHERE]
     * - 어느 하나라도 nonzero → 트림 있음 → [SpatialMode.VR180_HEMISPHERE]
     *   (VR180 표준: top=0, bottom=0, left=0x40000000, right=0x40000000)
     *
     * equi 박스 미발견 / 데이터 잘림 → 보수적으로 [SpatialMode.VR180_HEMISPHERE].
     */
    private fun classifySphericalMode(buf: ByteArray, searchEnd: Int, sv3dIdx: Int): SpatialMode {
        // sv3d 박스 안에서 equi 박스 검색. sv3dIdx 자체 이후부터 search.
        val equiIdx = findVerifiedMarker(buf, searchEnd, MARKER_EQUI, sv3dIdx + 4)
        if (equiIdx < 0) {
            Log.d(TAG, "SpatialMediaParser: equi 박스 미발견 → VR180_HEMISPHERE (보수적 default)")
            return SpatialMode.VR180_HEMISPHERE
        }
        // bounds 시작 = equiIdx + 4 (type 끝) + 4 (version + flags) = equiIdx + 8
        // bounds 끝 = equiIdx + 8 + 16 = equiIdx + 24
        if (equiIdx + 24 > searchEnd) {
            Log.d(TAG, "SpatialMediaParser: equi @ $equiIdx — bounds 영역 잘림 → VR180_HEMISPHERE")
            return SpatialMode.VR180_HEMISPHERE
        }
        val top = readU32BE(buf, equiIdx + 8)
        val bottom = readU32BE(buf, equiIdx + 12)
        val left = readU32BE(buf, equiIdx + 16)
        val right = readU32BE(buf, equiIdx + 20)
        val allZero = top == 0L && bottom == 0L && left == 0L && right == 0L
        Log.i(
            TAG,
            "SpatialMediaParser: equi @ $equiIdx bounds T=$top B=$bottom L=$left R=$right" +
                    " → ${if (allZero) "VR360_SPHERE" else "VR180_HEMISPHERE"}"
        )
        return if (allZero) SpatialMode.VR360_SPHERE else SpatialMode.VR180_HEMISPHERE
    }

    private fun readU32BE(buf: ByteArray, idx: Int): Long {
        return ((buf[idx].toLong() and 0xFFL) shl 24) or
                ((buf[idx + 1].toLong() and 0xFFL) shl 16) or
                ((buf[idx + 2].toLong() and 0xFFL) shl 8) or
                (buf[idx + 3].toLong() and 0xFFL)
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
