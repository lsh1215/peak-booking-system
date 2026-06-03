# V2 Latest Backend Rerun

실행 시각: 2026-06-03 KST

현재 로컬 `backend/` source 기준으로 빌드한 `linux/amd64` 이미지를 gcloud k3s 클러스터에 배포한 뒤 재실행한 V2 부하 테스트 증거 묶음이다. 판정 재검증에 필요한 summary, k6 stdout log, DB snapshot, Redis failover 주입 기록을 남겼다.

## 환경

| 항목 | 값 |
|---|---|
| Cloud / Zone | GCP `asia-northeast3-a` |
| 클러스터 | `peak-booking-k3s-20260601122904` |
| 노드 수 | GCE 6 nodes |
| 머신 타입 | 전체 노드 `e2-standard-2` |
| 하드웨어 스펙 | node당 2 vCPU / 8 GiB RAM, 총 12 vCPU / 48 GiB RAM |
| 노드 배치 | control-plane/Traefik 1, WAS 2, MySQL 1, Redis 1, LGTM 1 |
| 배포 이미지 | `peak-booking/backend:loadtest-20260603-210017` |
| 브랜치/커밋 | `codex/local-redis-fallback-queue` / `a6692e1` |
| backend diff | 없음 |
| 런타임 구성 | booking-service 2 replicas, MySQL 1, Redis primary/replicas + Sentinel, Traefik ingress |

## 결과 요약

| 시나리오 | Rate / Duration | k6 threshold | DB 최종 상태 | 판정 |
|---|---:|---|---|---|
| health | 20/s / 10s | PASS | inventory 변화 없음 | PASS |
| booking | 50/s / 20s | PASS | confirmed 10 | PASS |
| duplicate | 30/s / 20s | PASS | confirmed 10 | PASS |
| pg-timeout | 30/s / 20s | PASS | payment_unknown 10 | PASS |
| payment-failure | 30/s / 20s | PASS | confirmed 0, released 63 | PASS |
| realistic-mixed | 30/s / 20s | PASS | confirmed 10 | PASS |
| peak-500 | 500/s / 20s | PASS | confirmed 10 | PASS |
| peak-1000 | 1000/s / 20s | PASS | confirmed 10 | PASS |
| was-one-down | 100/s / 30s | PASS | confirmed 10 | PASS |
| redis-down | 100/s / 45s | PASS | confirmed 10 | PASS |

## 핵심 판단

- 모든 시나리오의 k6 threshold는 PASS다.
- DB snapshot 기준 confirmed/payment_unknown 합계는 항상 stock `10` 이하라 초과판매는 없다.
- `payment-failure`는 confirmed `0`, `pg-timeout`은 payment_unknown `10`으로 기대 상태를 만족한다.
- `redis-down`은 실행 5초 후 Redis master pod를 삭제했으며, `dropped_iterations=25`가 있었지만 시나리오 허용치 안에서 PASS했다.
- 테스트 후 Redis HA는 master 1개, connected replicas 2개, Sentinel 3개가 같은 master를 인식하는 상태로 복구 확인했다.

## 남긴 증거 파일

원시 증거 파일은 `logs/latest-backend-rerun/` 아래에 모았다.

| 파일 패턴 | 내용 |
|---|---|
| `LATEST_BACKEND_RERUN.md` | 이 요약 |
| `logs/latest-backend-rerun/summary.tsv` | 실행 대상, 이미지, 브랜치/커밋, 시나리오별 PASS/FAIL |
| `logs/latest-backend-rerun/*-summary.json` | k6 metric summary와 threshold 판정 |
| `logs/latest-backend-rerun/*-k6.out` | k6 stdout 실행 로그 |
| `logs/latest-backend-rerun/*-db-snapshot.txt` | 시나리오 종료 후 MySQL inventory/reservation/payment 상태 |
| `logs/latest-backend-rerun/redis-down-injection.txt` | Redis master pod 삭제 주입 기록 |
