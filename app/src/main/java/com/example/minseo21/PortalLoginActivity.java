package com.example.minseo21;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * QuickConnect 포털 WebView — _SSID 쿠키 자동 획득.
 * 1) 이미 _SSID 쿠키가 있으면 즉시 RESULT_OK.
 * 2) 없으면 포털 페이지 로드 + 자격증명 자동 주입 → 로그인 후 _SSID 감지.
 */
public class PortalLoginActivity extends AppCompatActivity {

    public static final String EXTRA_PORTAL_URL = "portal_url";
    private static final String TAG = "Portal";
    private static final long POLL_INTERVAL_MS = 500;

    private WebView webView;
    private NasCredentialStore credStore;
    private String portalUrl;
    private boolean cookieFound = false;
    private boolean credentialsInjected = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            if (cookieFound || isFinishing() || isDestroyed()) return;
            checkForCookie();
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_portal_login);

        credStore = new NasCredentialStore(this);
        portalUrl = getIntent().getStringExtra(EXTRA_PORTAL_URL);
        if (portalUrl == null || portalUrl.isEmpty()) {
            portalUrl = credStore.getBaseUrl();
        }

        TextView tvStatus = findViewById(R.id.tvPortalStatus);
        tvStatus.setText("NAS 포털 로그인 중: " + portalUrl);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(
                findViewById(R.id.webViewPortal), true);

        // 이미 _SSID 가 저장돼 있으면 바로 완료
        String existing = cookieManager.getCookie(portalUrl);
        if (existing != null && existing.contains("_SSID=")) {
            Log.i(TAG, "기존 _SSID 쿠키 재사용");
            credStore.savePortalCookie(existing);
            DsFileApiClient.setPortalCookie(existing);
            setResult(RESULT_OK);
            finish();
            return;
        }

        webView = findViewById(R.id.webViewPortal);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "pageFinished: " + url);
                // 로그인 폼이 있으면 자격증명 자동 주입 (한 번만)
                if (!credentialsInjected) {
                    credentialsInjected = true;
                    handler.postDelayed(() -> injectCredentials(view), 800);
                }
                // _SSID 폴링 시작
                if (!cookieFound) {
                    handler.removeCallbacks(pollTask);
                    handler.postDelayed(pollTask, POLL_INTERVAL_MS);
                }
            }
        });

        handler.post(pollTask); // 즉시 한 번 확인
        webView.loadUrl(portalUrl);
    }

    /** 로그인 폼에 저장된 계정/패스워드를 주입하고 자동 제출. */
    private void injectCredentials(WebView view) {
        if (cookieFound || isFinishing() || isDestroyed()) return;

        String user = escapeJs(credStore.getUser().isEmpty()
                ? DsFileConfig.USER : credStore.getUser());
        String pass = escapeJs(credStore.getPass().isEmpty()
                ? DsFileConfig.PASS : credStore.getPass());

        String js = "javascript:(function(){"
            // 입력값 세터 (React/Vue state 트리거용)
            + "var set=function(el,v){"
            +   "var s=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;"
            +   "s.call(el,v);"
            +   "el.dispatchEvent(new Event('input',{bubbles:true}));"
            +   "el.dispatchEvent(new Event('change',{bubbles:true}));"
            + "};"
            // 아이디 필드
            + "var u=document.querySelector('input[type=\"text\"],input[name=\"account\"],input[id*=\"account\"],input[id*=\"user\"]');"
            + "if(u)set(u,'" + user + "');"
            // 패스워드 필드
            + "var p=document.querySelector('input[type=\"password\"]');"
            + "if(p)set(p,'" + pass + "');"
            // 로그인 버튼 클릭 (0.5초 후)
            + "setTimeout(function(){"
            +   "var b=document.querySelector('button[type=\"submit\"],input[type=\"submit\"],.login-btn,.btn-login');"
            +   "if(b){b.click();return;}"
            +   "var f=document.querySelector('form');"
            +   "if(f)f.submit();"
            + "},500);"
            + "})()";

        Log.d(TAG, "자격증명 자동 주입");
        view.loadUrl(js);
    }

    private static String escapeJs(String s) {
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private void checkForCookie() {
        CookieManager cm = CookieManager.getInstance();
        String cookies = cm.getCookie(portalUrl);
        if (cookies == null) return;

        Log.d(TAG, "cookies: " + cookies.substring(0, Math.min(80, cookies.length())));

        if (cookies.contains("_SSID=")) {
            cookieFound = true;
            handler.removeCallbacks(pollTask);
            Log.i(TAG, "_SSID 획득: " + cookies.substring(0, Math.min(60, cookies.length())));
            credStore.savePortalCookie(cookies);
            DsFileApiClient.setPortalCookie(cookies);
            setResult(RESULT_OK);
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(pollTask);
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }
}
