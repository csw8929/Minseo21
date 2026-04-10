package com.example.minseo21;

/**
 * NAS 접속 정보 — .gitignore 에 등록됨. 절대 커밋하지 말 것.
 * 레포에는 DsFileConfig.template.java 만 포함됨.
 */
public class DsFileConfig {
    /** QuickConnect 또는 직접 접근 URL (trailing slash 없이) — canonical URL / DB 키 기준 */
    static final String BASE_URL  = "https://gomji17.tw3.quickconnect.to";
    /** 같은 LAN에 있을 때 시도할 로컬 NAS 주소 (빠른 연결용). 없으면 "" */
    static final String LAN_URL   = "http://192.168.45.65:5000";
    static final String USER      = "";
    static final String PASS      = "";
    /** NAS 기본 탐색 경로 */
    static final String BASE_PATH = "/video";
    /** 크로스 디바이스 이어보기용 위치 파일 저장 폴더 */
    static final String POS_DIR   = "/video/.minseo";
}
