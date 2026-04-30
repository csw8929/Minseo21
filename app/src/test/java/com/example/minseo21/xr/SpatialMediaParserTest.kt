package com.example.minseo21.xr

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * `SpatialMediaParser.parseStream` 단위 테스트.
 *
 * MP4 컨테이너의 일부 바이트 시퀀스를 직접 합성해 parser 동작을 검증한다.
 * 안드로이드 SDK / ContentResolver 의존 없는 pure JVM 테스트.
 */
class SpatialMediaParserTest {

    /** Big-endian box header (size + 4-byte type) bytes. payload 미포함. */
    private fun boxHeader(type: String, totalSize: Int): ByteArray {
        require(type.length == 4) { "box type must be 4 chars" }
        val buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(totalSize)
        buf.put(type.toByteArray(Charsets.US_ASCII))
        return buf.array()
    }

    /** parser 가 NONE 반환을 인정하는 너무 짧은 input. */
    @Test
    fun emptyInput_returnsNone() {
        val result = SpatialMediaParser.parseStream(ByteArrayInputStream(ByteArray(0)))
        assertEquals(SpatialMode.NONE, result)
    }

    @Test
    fun tooShortInput_returnsNone() {
        val result = SpatialMediaParser.parseStream(ByteArrayInputStream(ByteArray(10)))
        assertEquals(SpatialMode.NONE, result)
    }

    /** moov 안에 sv3d 박스 마커가 있으면 VR180_HEMISPHERE. */
    @Test
    fun sv3dInMoov_returnsVr180Hemisphere() {
        // [ftyp 32B] [moov 64B containing sv3d marker preceded by plausible size]
        val ftyp = boxHeader("ftyp", 32) + ByteArray(24)  // ftyp 32 bytes total
        // moov payload (56B) — 임의 데이터 + sv3d 박스 헤더 (size 24, "sv3d") + 16B payload
        val moovPayload = ByteArray(20) +
                boxHeader("sv3d", 24) +
                ByteArray(16)
        val moov = boxHeader("moov", 8 + moovPayload.size) + moovPayload

        val input = ftyp + moov
        val result = SpatialMediaParser.parseStream(ByteArrayInputStream(input))
        assertEquals(SpatialMode.VR180_HEMISPHERE, result)
    }

    /** moov 안에 st3d 박스 (sv3d 없음) → SBS_PANEL. */
    @Test
    fun st3dWithoutSv3d_returnsSbsPanel() {
        val ftyp = boxHeader("ftyp", 32) + ByteArray(24)
        val moovPayload = ByteArray(20) +
                boxHeader("st3d", 16) +
                ByteArray(8)
        val moov = boxHeader("moov", 8 + moovPayload.size) + moovPayload

        val input = ftyp + moov
        val result = SpatialMediaParser.parseStream(ByteArrayInputStream(input))
        assertEquals(SpatialMode.SBS_PANEL, result)
    }

    /** sv3d 와 st3d 둘 다 있으면 sv3d 우선 (VR180). */
    @Test
    fun sv3dWinsOverSt3d() {
        val ftyp = boxHeader("ftyp", 32) + ByteArray(24)
        val moovPayload = ByteArray(20) +
                boxHeader("st3d", 16) +
                ByteArray(8) +
                boxHeader("sv3d", 24) +
                ByteArray(16)
        val moov = boxHeader("moov", 8 + moovPayload.size) + moovPayload

        val input = ftyp + moov
        val result = SpatialMediaParser.parseStream(ByteArrayInputStream(input))
        assertEquals(SpatialMode.VR180_HEMISPHERE, result)
    }

    /**
     * mdat 가 sv3d 보다 먼저 발견되면 search 영역이 mdat 까지로 축소되어 sv3d 미발견 → NONE.
     * (non-fast-start MP4 — moov 가 끝에 붙어 있는 경우의 시뮬레이션)
     */
    @Test
    fun mdatBeforeSv3d_truncatesSearch_returnsNone() {
        // ftyp → mdat → 그 뒤에 sv3d 가 와도 search 영역 mdat 까지로 잘리므로 미검출
        val ftyp = boxHeader("ftyp", 32) + ByteArray(24)
        val mdat = boxHeader("mdat", 24) + ByteArray(16)
        val sv3d = boxHeader("sv3d", 24) + ByteArray(16)

        val input = ftyp + mdat + sv3d
        val result = SpatialMediaParser.parseStream(ByteArrayInputStream(input))
        assertEquals(SpatialMode.NONE, result)
    }

    /**
     * sv3d 마커 직전 4 byte 가 비현실적 size (예: 0xFFFFFFFF) 면 false-positive 로 reject.
     * 압축 비디오/오디오 데이터에 우연히 sv3d 4-byte 시퀀스가 포함된 케이스 시뮬레이션.
     */
    @Test
    fun sv3dWithBogusSize_rejected_returnsNone() {
        // size 가 0xFFFFFFFF (max u32, 비현실적) → sanity check 에서 reject
        val bogus = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val sv3dBytes = "sv3d".toByteArray(Charsets.US_ASCII)
        val ftyp = boxHeader("ftyp", 32) + ByteArray(24)
        // moov 안에 잘못된 size 가 박힌 sv3d-like 시퀀스 — verify 안 통과
        val moovPayload = ByteArray(16) + bogus + sv3dBytes + ByteArray(16)
        val moov = boxHeader("moov", 8 + moovPayload.size) + moovPayload

        val input = ftyp + moov
        val result = SpatialMediaParser.parseStream(ByteArrayInputStream(input))
        assertEquals(SpatialMode.NONE, result)
    }

    /** 마커 자체가 없으면 NONE. */
    @Test
    fun noSpatialMarker_returnsNone() {
        val ftyp = boxHeader("ftyp", 32) + ByteArray(24)
        val moovPayload = ByteArray(40)
        val moov = boxHeader("moov", 8 + moovPayload.size) + moovPayload

        val input = ftyp + moov
        val result = SpatialMediaParser.parseStream(ByteArrayInputStream(input))
        assertEquals(SpatialMode.NONE, result)
    }
}
