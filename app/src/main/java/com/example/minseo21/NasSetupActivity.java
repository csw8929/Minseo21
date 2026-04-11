package com.example.minseo21;

import android.content.Intent;
import android.os.Bundle;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * NAS 접속 정보 입력 화면.
 * 최초 실행 또는 설정 변경 시 표시.
 */
public class NasSetupActivity extends AppCompatActivity {

    /** 설정 화면에서 열릴 때 전달하는 엑스트라 — true면 이미 설정된 값 채워 넣음 */
    public static final String EXTRA_EDIT_MODE = "edit_mode";

    private EditText etBaseUrl, etLanUrl, etUser, etPass, etBasePath, etPosDir;
    private Button btnTest, btnSave;
    private TextView tvResult;
    private NasCredentialStore credStore;
    private boolean passwordVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nas_setup);

        credStore = new NasCredentialStore(this);

        etBaseUrl  = findViewById(R.id.etBaseUrl);
        etLanUrl   = findViewById(R.id.etLanUrl);
        etUser     = findViewById(R.id.etUser);
        etPass     = findViewById(R.id.etPass);
        etBasePath = findViewById(R.id.etBasePath);
        etPosDir   = findViewById(R.id.etPosDir);
        btnTest    = findViewById(R.id.btnTest);
        btnSave    = findViewById(R.id.btnSave);
        tvResult   = findViewById(R.id.tvResult);

        // 기본값을 hint 로 표시 (빈 칸 = DsFileConfig fallback 사용)
        etBasePath.setHint(DsFileConfig.BASE_PATH);
        etPosDir.setHint(DsFileConfig.POS_DIR);

        // 주소 필드: 항상 저장값(또는 DsFileConfig 기본값) 표시
        etBaseUrl.setText(credStore.getBaseUrl());
        etLanUrl.setText(credStore.getLanUrl());

        // 계정 정보: 저장된 값 있으면 표시, 없으면 DsFileConfig 기본값 표시
        boolean editMode = getIntent().getBooleanExtra(EXTRA_EDIT_MODE, false);
        if (editMode && credStore.hasCredentials()) {
            etUser.setText(credStore.getUser());
            // 비밀번호는 보안상 빈칸 — 변경하려면 다시 입력
            // 경로: 커스텀 값이 있으면 표시, 없으면 빈칸 (hint에 기본값 표시)
            etBasePath.setText(credStore.getCustomBasePath());
            etPosDir.setText(credStore.getCustomPosDir());
            // 이미 검증된 설정 — 저장 버튼 바로 활성화
            btnSave.setEnabled(true);
            btnSave.setAlpha(1.0f);
        } else if (!DsFileConfig.USER.isEmpty()) {
            // 저장된 계정 없고 DsFileConfig 기본값 있으면 미리 채우기 (테스트 편의)
            etUser.setText(DsFileConfig.USER);
            etPass.setText(DsFileConfig.PASS);
        }

        // 비밀번호 표시/숨기기 토글
        ImageButton btnToggle = findViewById(R.id.btnTogglePass);
        btnToggle.setOnClickListener(v -> {
            passwordVisible = !passwordVisible;
            int sel = etPass.getSelectionEnd();
            etPass.setTransformationMethod(passwordVisible
                    ? HideReturnsTransformationMethod.getInstance()
                    : PasswordTransformationMethod.getInstance());
            etPass.setSelection(sel);
        });

        btnTest.setOnClickListener(v -> runConnectionTest());

        btnSave.setOnClickListener(v -> {
            // 편집 모드에서 비밀번호 미입력 시 기존 비밀번호 유지
            String passToSave = etPass.getText().toString();
            if (passToSave.isEmpty() && editMode && credStore.hasCredentials()) {
                passToSave = credStore.getPass();
            }
            credStore.save(
                    etBaseUrl.getText().toString(),
                    etLanUrl.getText().toString(),
                    etUser.getText().toString(),
                    passToSave,
                    etBasePath.getText().toString(),
                    etPosDir.getText().toString()
            );
            // DsFileApiClient 에 새 인증 정보 적용
            DsFileApiClient.init(credStore);
            Toast.makeText(this, "NAS 설정이 저장되었습니다.", Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        });
    }

    private void runConnectionTest() {
        String baseUrl = etBaseUrl.getText().toString().trim();
        String user    = etUser.getText().toString().trim();
        String pass    = etPass.getText().toString();

        if (baseUrl.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            showResult(false, "NAS 주소, 아이디, 비밀번호를 모두 입력해 주세요.");
            return;
        }

        // 경로 필드: 비어 있으면 DsFileConfig 기본값 사용
        String basePath = etBasePath.getText().toString().trim();
        if (basePath.isEmpty()) basePath = DsFileConfig.BASE_PATH;
        String posDir = etPosDir.getText().toString().trim();
        if (posDir.isEmpty()) posDir = DsFileConfig.POS_DIR;

        // 임시로 테스트 인증 정보 적용 후 로그인 시도 (저장은 하지 않음)
        DsFileApiClient.init(baseUrl, etLanUrl.getText().toString().trim(), user, pass,
                basePath, posDir);

        btnTest.setEnabled(false);
        etBaseUrl.setEnabled(false);
        etLanUrl.setEnabled(false);
        etUser.setEnabled(false);
        etPass.setEnabled(false);
        showResult(false, "연결 중…");
        tvResult.setTextColor(0xFFAAAAAA);
        tvResult.setVisibility(View.VISIBLE);

        DsFileApiClient.login(new DsFileApiClient.Callback<String>() {
            @Override public void onResult(String sid) {
                if (isFinishing() || isDestroyed()) return;
                setFieldsEnabled(true);
                showResult(true, "✓ 연결 성공!");
                btnSave.setEnabled(true);
                btnSave.setAlpha(1.0f);
            }
            @Override public void onError(String msg) {
                if (isFinishing() || isDestroyed()) return;
                setFieldsEnabled(true);
                if (msg.startsWith("PORTAL_AUTH_REQUIRED:")) {
                    // 릴레이가 차단됨 — 포털 인증은 NAS 탭 첫 진입 시 자동 처리
                    showResult(true, "✓ NAS 주소 확인됨\n포털 인증 필요 — 저장 후 NAS 탭에서 자동으로 처리됩니다.");
                    btnSave.setEnabled(true);
                    btnSave.setAlpha(1.0f);
                } else {
                    showResult(false, "연결 실패: " + msg);
                    btnSave.setEnabled(false);
                    btnSave.setAlpha(0.5f);
                    // 실패 시 기존 인증 정보로 복원
                    DsFileApiClient.init(credStore);
                }
            }
        });
    }

    private void setFieldsEnabled(boolean enabled) {
        btnTest.setEnabled(enabled);
        etBaseUrl.setEnabled(enabled);
        etLanUrl.setEnabled(enabled);
        etUser.setEnabled(enabled);
        etPass.setEnabled(enabled);
        etBasePath.setEnabled(enabled);
        etPosDir.setEnabled(enabled);
    }

    private void showResult(boolean success, String msg) {
        tvResult.setVisibility(View.VISIBLE);
        tvResult.setText(msg);
        tvResult.setTextColor(success ? 0xFF2ECC71 : 0xFFFF6B6B);
    }

}

