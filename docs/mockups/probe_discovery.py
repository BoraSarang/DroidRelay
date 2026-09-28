#!/usr/bin/env python3
"""
맥 메뉴바 앱의 서버 탐색 후보를 검증한다 (설계 검토용 — 실제 앱 코드는 아님).

사용: python3 docs/mockups/probe_discovery.py
"""
import socket, time, json, urllib.request
from concurrent.futures import ThreadPoolExecutor

PORT = 3000
SUBNET = "10.38.120"          # Mac 의 Wi-Fi 인터페이스에서 유도
# 실제로는: getifaddrs → en0 IPv4 + netmask → 서브넷 계산. 여기서는 하드코딩(측정용)


def probe(ip, timeout=0.35):
    """포트가 열려 있는지 확인하고 DroidRelay 면 정보를 가져온다"""
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(timeout)
    try:
        s.connect((ip, PORT))
    except Exception:
        return None
    finally:
        s.close()
    try:
        with urllib.request.urlopen(f"http://{ip}:{PORT}/api/info", timeout=0.6) as r:
            d = json.loads(r.read())
        if "version" in d and "port" in d:      # DroidRelay 시그니처
            return {"ip": ip, "port": d["port"], "version": d["version"]}
    except Exception:
        pass
    return None


def main():
    hosts = [f"{SUBNET}.{i}" for i in range(1, 255)]
    t0 = time.time()
    with ThreadPoolExecutor(max_workers=128) as ex:
        found = [r for r in ex.map(probe, hosts) if r]
    dt = time.time() - t0

    print(f"서브넷 /24 스캔: {len(hosts)}개 호스트, 병렬 128, port {PORT}")
    print(f"소요 시간      : {dt:.2f}초")
    print(f"발견          : {len(found)}건")
    for f in found:
        print(f"  → {f['ip']}:{f['port']}  v{f['version']}")

    # 게이트웨이 바로 조회 (핫스팟일 때 = 폰)
    t1 = time.time()
    g = probe("10.38.120.211")
    print(f"\n게이트웨이 직접 조회: {(time.time()-t1)*1000:.0f}ms → {g}")

    # 저장 후 재사용 시나리오
    print(f"\n재시도(캐시) 시: {0:.0f}ms (네트워크 왕복 1회)")


if __name__ == "__main__":
    main()
