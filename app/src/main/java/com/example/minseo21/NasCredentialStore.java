package com.example.minseo21;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * NAS 접속 정보를 EncryptedSharedPreferences 에 저장/조회.
 * DsFileConfig 상수를 대체한다.
 */
public class NasCredentialStore {

    private static final String TAG  = "NasCred";
    private static final String PREFS_NAME = "nas_credentials";

    static final String KEY_BASE_URL  = "base_url";
    static final String KEY_LAN_URL   = "lan_url";
    static final String KEY_USER      = "user";
    static final String KEY_PASS      = "pass";
    static final String KEY_BASE_PATH = "base_path";
    static final String KEY_POS_DIR   = "pos_dir";

    private final SharedPreferences prefs;

    public NasCredentialStore(Context context) {
        SharedPreferences p = null;
        try {
            MasterKey masterKey = new MasterKey.Builder(context.getApplicationContext())
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            p = EncryptedSharedPreferences.create(
                    context.getApplicationContext(),
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            Log.e(TAG, "EncryptedSharedPreferences 초기화 실패, 일반 prefs 사용", e);
            p = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
        this.prefs = p;
    }

    /** 저장된 인증 정보가 있는지 (USER + PASS 모두 입력됐으면 충분). */
    public boolean hasCredentials() {
        return !getUser().isEmpty() && !getPass().isEmpty();
    }

    // URL은 DsFileConfig 기본값 사용 (주소는 앱에 고정)
    public String getBaseUrl()  { return prefs.getString(KEY_BASE_URL,  DsFileConfig.BASE_URL);  }
    public String getLanUrl()   { return prefs.getString(KEY_LAN_URL,   DsFileConfig.LAN_URL);   }
    // 계정정보는 저장된 값만 사용 — 없으면 빈 문자열 → hasCredentials() false → 설정 화면 표시
    public String getUser()     { return prefs.getString(KEY_USER,      ""); }
    public String getPass()     { return prefs.getString(KEY_PASS,      ""); }
    public String getBasePath() { return prefs.getString(KEY_BASE_PATH, DsFileConfig.BASE_PATH); }
    public String getPosDir()   { return prefs.getString(KEY_POS_DIR,   DsFileConfig.POS_DIR);   }

    /**
     * 사용자가 직접 저장한 커스텀 경로 (저장된 값이 없으면 빈 문자열 반환).
     * UI 에서 "현재 저장된 값" 표시용 — 비어 있으면 DsFileConfig fallback 사용 중.
     */
    public String getCustomBasePath() { return prefs.getString(KEY_BASE_PATH, ""); }
    public String getCustomPosDir()   { return prefs.getString(KEY_POS_DIR,   ""); }

    public void save(String baseUrl, String lanUrl, String user, String pass,
                     String basePath, String posDir) {
        SharedPreferences.Editor editor = prefs.edit()
                .putString(KEY_BASE_URL, baseUrl.trim())
                .putString(KEY_LAN_URL,  lanUrl.trim())
                .putString(KEY_USER,     user.trim())
                .putString(KEY_PASS,     pass);
        // 빈 문자열이면 저장하지 않음 (getter의 DsFileConfig fallback 사용)
        String bp = basePath.trim();
        if (!bp.isEmpty()) editor.putString(KEY_BASE_PATH, bp);
        else editor.remove(KEY_BASE_PATH);
        String pd = posDir.trim();
        if (!pd.isEmpty()) editor.putString(KEY_POS_DIR, pd);
        else editor.remove(KEY_POS_DIR);
        editor.apply();
        Log.i(TAG, "NAS 인증 정보 저장됨 (baseUrl=" + baseUrl + ", basePath=" + getBasePath() + ")");
    }

    /** 이전 버전 호환 — basePath/posDir 없이 저장 (기본값 유지). */
    public void save(String baseUrl, String lanUrl, String user, String pass) {
        save(baseUrl, lanUrl, user, pass, getCustomBasePath(), getCustomPosDir());
    }

    public void clear() {
        prefs.edit().clear().apply();
    }
}
