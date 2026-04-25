package com.example.minseo21.xr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** XrPlayerManager companion object 순수 함수 — Android 런타임 불요 */
class XrPlayerManagerTest {

    // ── sbsPatternMatch ──────────────────────────────────────────────────────

    @Test fun sbsPattern_underscore_sbs_lower() {
        assertTrue(XrPlayerManager.sbsPatternMatch("movie_sbs.mkv"))
    }

    @Test fun sbsPattern_underscore_sbs_upper() {
        assertTrue(XrPlayerManager.sbsPatternMatch("movie_SBS.mkv"))
    }

    @Test fun sbsPattern_underscore_3d() {
        assertTrue(XrPlayerManager.sbsPatternMatch("video_3D.mp4"))
    }

    @Test fun sbsPattern_dot_sbs_dot() {
        assertTrue(XrPlayerManager.sbsPatternMatch("film.SBS.mkv"))
    }

    @Test fun sbsPattern_bracket_sbs() {
        assertTrue(XrPlayerManager.sbsPatternMatch("movie [SBS].mkv"))
    }

    @Test fun sbsPattern_bracket_3d() {
        assertTrue(XrPlayerManager.sbsPatternMatch("clip [3D].mp4"))
    }

    @Test fun sbsPattern_no_match() {
        assertFalse(XrPlayerManager.sbsPatternMatch("normal_movie.mp4"))
    }

    @Test fun sbsPattern_empty() {
        assertFalse(XrPlayerManager.sbsPatternMatch(""))
    }

    // ── sbsRatioMatch ────────────────────────────────────────────────────────

    @Test fun sbsRatio_wide_4k_sbs_passes() {
        // 3840×1080 = ratio 3.555 — SBS 4K (두 눈 1920×1080 나란히)
        assertTrue(XrPlayerManager.sbsRatioMatch(3840, 1080))
    }

    @Test fun sbsRatio_wide_1080_sbs_passes() {
        // 3840×2160 / 2 = 1920×2160 이지만 SBS 원본은 3840×1080
        assertTrue(XrPlayerManager.sbsRatioMatch(7680, 2160))
    }

    @Test fun sbsRatio_normal_16_9_fails() {
        // 1920×1080 = ratio 1.77 — 일반 영상
        assertFalse(XrPlayerManager.sbsRatioMatch(1920, 1080))
    }

    @Test fun sbsRatio_zero_height_fails() {
        assertFalse(XrPlayerManager.sbsRatioMatch(3840, 0))
    }

    @Test fun sbsRatio_negative_height_fails() {
        assertFalse(XrPlayerManager.sbsRatioMatch(3840, -1))
    }

    @Test fun sbsRatio_exact_boundary_passes() {
        // ratio = 3.5 정확히
        assertTrue(XrPlayerManager.sbsRatioMatch(3500, 1000))
    }

    @Test fun sbsRatio_just_below_boundary_fails() {
        // ratio = 3.499... < 3.5
        assertFalse(XrPlayerManager.sbsRatioMatch(3499, 1000))
    }
}
