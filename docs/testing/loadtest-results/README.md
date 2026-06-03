# Load Test Results

이 디렉터리는 부하 테스트 기술 문서를 뒷받침하는 **정리된 증거 묶음**이다. 위치는 `docs/testing/loadtest-results/`이며, 요약 문서와 최소한의 재검증 가능한 원시 증거를 Git으로 추적한다.

## 보관 정책

| 디렉터리 | 의미 |
|---|---|
| `V1/` | Redis 단일 인스턴스 + bounded DB fallback 후보 시절의 대표 결과 |
| `V1/logs/` | V1 k6 logs, summary JSON, inventory/cluster snapshots, suite TSV |
| `V2/` | Redis HA + failover pause/local queue fallback 구조의 대표 결과 |
| `V2/logs/` | V2 k6 logs, summary JSON, inventory/cluster snapshots, suite TSV |
| `V2/logs/latest-backend-rerun/` | 현재 backend source 기준 최신 V2 재실행 증거 |

## 추적 기준

남기는 파일:

- 사람이 읽는 요약 문서: `README.md`, `V1/LOADTEST_ANALYSIS.md`, `V2/LOADTEST_ANALYSIS.md`, `V2/LATEST_BACKEND_RERUN.md`
- 판정 재검증에 필요한 k6 summary/log: `*-summary.json`, `*.log`, `*-k6.out`, `SUITE_SUMMARY*.tsv`
- 정합성 확인용 DB snapshot: `*-inventory.txt`, `*-db-snapshot.txt`
- 장애/배포 상태 확인용 cluster/injection 기록: `*-cluster.txt`, `redis-down-injection.txt`

남기지 않는 파일:

- 대시보드 PNG 캡처
- 날짜별 중간 실행 전체 묶음
- 무효 실행 또는 최신 rerun으로 대체된 preflight 결과

새 부하 테스트를 실행하면 먼저 임시 로컬 디렉터리에 결과를 받은 뒤, 제출/리뷰에 필요한 최소 증거만 이 디렉터리 구조로 정리해서 이동한다.
