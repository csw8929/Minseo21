# DS Video vs 우리 앱 동작 비교

> 분석 기준: 5G 외부망 QuickConnect 릴레이 접속 환경
> 작성일: 2026-04-11

---

## 1단계 — 릴레이 서버 탐색

| 항목 | DS Video | 우리 앱 |
|---|---|---|
| **방법** | `libsynoholepunch.so` (Synology 전용 NAT 홀펀칭 라이브러리) | QuickConnect Serv.php API 호출 → `relay_ip` / `relay_dn` 파싱 |
| **확정 URL** | `synr-tw3.GOMJI17.direct.quickconnect.to:28095` (HTTP) 바로 확정 | 동일한 호스트 발견, 단 처음엔 HTTPS:16811 먼저 시도 → SSL 인증서 오류 발생 |
| **속도** | 거의 즉시 (내부 라이브러리) | API 왕복 + 포트 프로브 필요 |

---

## 2단계 — 로그인 / 인증

| 항목 | DS Video | 우리 앱 |
|---|---|---|
| **세션 종류** | `VideoStation` 세션 1개 | `FileStation` 세션 (기본) + `VideoStation` 세션 별도 획득 |
| **방식** | 릴레이 HTTP:28095에 직접 POST | 포털 또는 릴레이에 GET/POST |
| **Secure SignIn 문제** | 없음 (내부 인증 방식 다름) | 릴레이 IP를 외부 IP로 인식 → code 400/407 발생 가능 → 포털 쿠키 우회 필요 |

---

## 3단계 — 파일 찾기

| 항목 | DS Video | 우리 앱 |
|---|---|---|
| **방법** | VideoStation 라이브러리에서 직접 조회 | FileStation 파일 목록 (`SYNO.FileStation.List`) |
| **영상 식별자** | `movieId` + `fileRecId` (VideoStation DB) | `filePath` (경로 기반) |
| **VideoStation 인덱싱** | 반드시 필요 | HLS 시에만 필요, 직접 스트림 시 불필요 |

---

## 4단계 — 스트리밍 방식 ← 핵심 차이

| 항목 | DS Video | 우리 앱 (수정 전) | 우리 앱 (수정 후) |
|---|---|---|---|
| **API** | `SYNO.VideoStation2.Streaming` | `SYNO.FileStation.Download` | `SYNO.VideoStation2.Streaming` (시도 A~F) |
| **형식** | HLS 트랜스코딩 (`.m3u8`) | 원본 파일 직접 다운로드 | HLS 트랜스코딩 (`.m3u8`) |
| **비트레이트** | 720p / 480p 등 낮춰서 전송 | 1080p 원본 그대로 | 720p / 480p로 낮춤 |
| **5G 릴레이 결과** | 끊김 없음 | 버퍼링 발생 | 끊김 없어야 함 |

---

## 5단계 — VS2.Streaming API 호출 포맷 ← 버그 원인

| 항목 | DS Video | 우리 앱 (수정 전) | 우리 앱 (수정 후) |
|---|---|---|---|
| **HTTP 방식** | GET URL 파라미터 또는 form-urlencoded POST | JSON body POST | GET URL 파라미터 (시도 A) / form-urlencoded POST (시도 B) |
| **`file` 파라미터 형식** | `file=[{"id":5549,"type":"home_video"}]` (URL-encoded) | `{"file": 5549}` (JSON body 내부) | `file=[{"id":5549,"type":"home_video"}]` |
| **API 응답** | `stream_id` 획득 성공 | code 120 `"file required"` | 성공 예상 |

### 오류 발생 원인 상세

VideoStation2.Streaming API는 `file` 파라미터를 **URL query string 또는 form-urlencoded body의 독립적인 파라미터**로 기대한다.
우리 앱은 `{"file": 5549, "format": "hls"}` 형태의 JSON body를 POST했는데, 이는 API가 인식하지 못하는 포맷이라 body 전체를 무시하고 `file required (code 120)`을 반환한 것.

---

## 6단계 — HLS 재생

| 항목 | DS Video | 우리 앱 |
|---|---|---|
| **재생 엔진** | Synology 자체 플레이어 | libVLC 3.6.0 |
| **스트림 URL 구조** | `entry.cgi?api=SYNO.VideoStation2.Streaming&method=stream&id=<streamId>&_sid=...` | 동일하게 구성 (수정 후) |
| **인증 방식** | `_sid` URL 파라미터만으로 충분 (쿠키 불필요) | 동일 |

---

## 전체 흐름 요약

```
DS Video (정상):
  릴레이 HTTP:28095
    → SYNO.API.Auth (VideoStation 세션)
    → SYNO.VideoStation2.Streaming open GET (file=[{id,type}])
    → stream_id 획득
    → SYNO.VideoStation2.Streaming stream (HLS m3u8, 720p)
    → 끊김 없이 재생

우리 앱 (수정 전, 버퍼링):
  릴레이 HTTP:28095
    → SYNO.API.Auth (FileStation 세션)
    → SYNO.FileStation.Download (1080p 원본 직접 전송)
    → 5G 릴레이 대역폭 초과 → 버퍼링

우리 앱 (수정 후, 예상):
  릴레이 HTTP:28095
    → SYNO.API.Auth (FileStation + VideoStation 세션)
    → SYNO.VideoStation2.Streaming open GET (file=[{id,type}])  ← 포맷 수정
    → stream_id 획득
    → SYNO.VideoStation2.Streaming stream (HLS m3u8, 720p)
    → 끊김 없이 재생
```

---

## 버퍼링 근본 원인

5G 릴레이 실측 대역폭 약 2~5 Mbps, 1080p 원본 파일 필요 대역폭 약 8~20 Mbps.
DS Video가 끊기지 않는 이유는 처음부터 릴레이 대역폭에 맞는 비트레이트로 트랜스코딩하기 때문이다.
우리 앱이 버퍼링이 발생한 이유는 트랜스코딩 없이 원본을 그대로 전송했기 때문이다.
