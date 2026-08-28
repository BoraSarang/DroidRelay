# 자체 서명 TLS 인증서 (server.p12)

웹 다운로드 "안전하지 않은 다운로드" 경고 회피용 HTTPS(8443) 인증서.
`mkcert` 로컬 CA 서명 인증서를 PKCS12로 변환해 번들한 것.

## 재생성 (폰 LAN IP가 바뀌었을 때)

```bash
# 1) 인증서 생성 (새 IP 포함)
mkcert -key-file key.pem -cert-file cert.pem 10.64.228.42 localhost 127.0.0.1 ::1

# 2) PKCS12 변환 (alias=relay, password=droidrelay01 — RelayServer.kt 상수와 일치)
openssl pkcs12 -export -inkey key.pem -in cert.pem -out server.p12 -name relay -password pass:droidrelay01

# 3) assets에 교체 후 재빌드
cp server.p12 apps/android/app/src/main/assets/certs/server.p12
```

## 맥 키체인 신뢰 (선택 — 경고 1회 감수 시 불필요)

브라우저가 최초 1회 "연결이 비공개로 설정되어 있지 않습니다 → 고급 → 계속"을 누르면 이후 다운로드가 정상 동작하므로, 아래 CA 등록은 선택사항입니다.

```bash
# 등록 (경고 없이 열기 원할 때)
security add-trusted-cert -r trustRoot -k ~/Library/Keychains/login.keychain-db "$(mkcert -CAROOT)/rootCA.pem"

# 제거
security delete-certificate -c "mkcert lee@lees-MacBook-Pro.local" ~/Library/Keychains/login.keychain-db
```

검증: `security verify-cert -c cert.pem` (등록 시 success / 미등록 시 NOT_TRUSTED)