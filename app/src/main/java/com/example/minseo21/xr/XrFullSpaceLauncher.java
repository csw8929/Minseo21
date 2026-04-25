package com.example.minseo21.xr;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

/**
 * XR 단말에서 다른 Activity 를 Full Space 모드로 띄우기 위한 헬퍼.
 *
 * Galaxy XR(SM-I610) 에서는 매니페스트 property 만으로는 Full Space 진입이 트리거되지 않고,
 * {@code startActivity} 에 {@code LaunchUtils.createBundleForFullSpaceModeLaunch} 로 만든
 * Bundle 을 전달해야 SystemUI 의 {@code DesktopTasksController} 가 Full Space 전환을 발동시킨다
 * (Spatial Film 앱의 실제 동작 메커니즘).
 *
 * 비-XR 단말에서는 {@link #startActivity(Activity, Intent)} 가 일반 startActivity 와 동일.
 */
public class XrFullSpaceLauncher {

    private static final String TAG = "SACH_XR";

    private androidx.xr.runtime.Session xrSession;

    public XrFullSpaceLauncher(Activity activity) {
        initSession(activity);
    }

    /** XR 단말이면 Full Space Bundle 로, 아니면 일반 startActivity. */
    public void startActivity(Activity activity, Intent intent) {
        if (xrSession != null) {
            try {
                Bundle opts = androidx.xr.scenecore.LaunchUtils
                        .createBundleForFullSpaceModeLaunch(xrSession, new Bundle());
                activity.startActivity(intent, opts);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "Full Space Bundle 생성 실패, 일반 런치로 폴백: " + t);
            }
        }
        activity.startActivity(intent);
    }

    private void initSession(Activity activity) {
        PackageManager pm = activity.getPackageManager();
        boolean isXr = pm.hasSystemFeature("android.hardware.type.xr")
                    || pm.hasSystemFeature("android.software.xr.api.spatial")
                    || pm.hasSystemFeature("android.hardware.xr.input.controller");
        if (!isXr) return;
        try {
            androidx.xr.runtime.SessionCreateResult result =
                    androidx.xr.runtime.Session.Companion.create(activity);
            if (result instanceof androidx.xr.runtime.SessionCreateSuccess) {
                xrSession = ((androidx.xr.runtime.SessionCreateSuccess) result).getSession();
            } else {
                Log.w(TAG, "XR Launcher Session 생성 실패: " + result);
            }
        } catch (Throwable t) {
            Log.w(TAG, "XR Launcher Session 생성 예외: " + t);
        }
    }
}
