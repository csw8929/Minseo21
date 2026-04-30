package com.example.minseo21.xr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `XrConfig.detectByRatio` 매트릭스 단위 테스트.
 *
 * 실제 콘텐츠 형식별 frame 해상도를 기대 SpatialMode 와 매핑.
 */
class DimensionDetectionTest {

    // ── VR180 매치 (정확 2:1 / 1:1 ±0.5%) ────────────────────────────

    @Test fun vr180_4k_sbs() =
        assertEquals(SpatialMode.VR180_HEMISPHERE, XrConfig.detectByRatio(4096, 2048))

    @Test fun vr180_5_7k_sbs() =
        assertEquals(SpatialMode.VR180_HEMISPHERE, XrConfig.detectByRatio(5760, 2880))

    @Test fun vr180_8k_sbs() =
        assertEquals(SpatialMode.VR180_HEMISPHERE, XrConfig.detectByRatio(7680, 3840))

    @Test fun vr180_4k_tab_square() =
        assertEquals(SpatialMode.VR180_HEMISPHERE, XrConfig.detectByRatio(4096, 4096))

    @Test fun vr360_mono_full_equi_2to1() =
        assertEquals(SpatialMode.VR180_HEMISPHERE, XrConfig.detectByRatio(4096, 2048))

    // ── SBS_PANEL 매치 (≥ 3:1 Full-SBS) ─────────────────────────────

    @Test fun full_sbs_4k() =
        assertEquals(SpatialMode.SBS_PANEL, XrConfig.detectByRatio(7680, 2160))

    @Test fun full_sbs_1080p() =
        assertEquals(SpatialMode.SBS_PANEL, XrConfig.detectByRatio(3840, 1080))

    // ── NONE 매치 (일반 영상 / letterbox / FHD 미만) ─────────────────

    /** 일반 4K 영화 16:9 (3840×2160) — ratio 1.778. */
    @Test fun general_4k_16to9_returnsNone() =
        assertEquals(SpatialMode.NONE, XrConfig.detectByRatio(3840, 2160))

    /** Letterbox 크롭된 영화 (3836×1912) — ratio 2.006. ±0.5% 밖이라 NONE. */
    @Test fun letterboxedMovie_2_006_returnsNone() =
        assertEquals(SpatialMode.NONE, XrConfig.detectByRatio(3836, 1912))

    /** Cinemascope 2.39:1 — NONE. */
    @Test fun cinemascope_returnsNone() =
        assertEquals(SpatialMode.NONE, XrConfig.detectByRatio(2560, 1072))

    /** Half-SBS 1080p 16:9 (1920×1080) — ratio 1.778, NONE. 파일명 키워드 의존. */
    @Test fun halfSbs1080p_returnsNone() =
        assertEquals(SpatialMode.NONE, XrConfig.detectByRatio(1920, 1080))

    /** FHD 미만은 무조건 NONE. */
    @Test fun belowFhd_720p_returnsNone() =
        assertEquals(SpatialMode.NONE, XrConfig.detectByRatio(1280, 720))

    @Test fun belowFhd_squareSmall_returnsNone() =
        assertEquals(SpatialMode.NONE, XrConfig.detectByRatio(1080, 1080))

    // ── tolerance 경계 ───────────────────────────────────────────────

    /**
     * 정확한 2:1 의 ±0.5% 경계 테스트.
     * - 2.000 (4096×2048) → match
     * - 2.005 (예: 4010×2000) → 정확히 0.005 안쪽 → match
     * - 2.011 → 밖 → NONE
     */
    @Test fun ratio_exactly_2_0_matches() =
        assertEquals(SpatialMode.VR180_HEMISPHERE, XrConfig.detectByRatio(4096, 2048))

    @Test fun ratio_2_011_aboveTolerance_returnsNone() {
        // 2.011 → 0.011 차이 → tolerance(0.005) 밖
        // 4022/2000 = 2.011
        assertEquals(SpatialMode.NONE, XrConfig.detectByRatio(4022, 2000))
    }

    @Test fun ratio_1_005_withinTolerance_matches() {
        // 정사각 ±0.5% 안쪽: 2010/2000 = 1.005
        assertEquals(SpatialMode.VR180_HEMISPHERE, XrConfig.detectByRatio(2010, 2000))
    }

    // ── 음수/0 입력 방어 ────────────────────────────────────────────

    @Test fun zeroDimensions_returnsNone() =
        assertEquals(SpatialMode.NONE, XrConfig.detectByRatio(0, 0))

    @Test fun negativeDimensions_returnsNone() =
        assertEquals(SpatialMode.NONE, XrConfig.detectByRatio(-1, -1))
}
