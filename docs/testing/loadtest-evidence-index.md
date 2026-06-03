# Load Test Result Summary Form

> 부하 테스트 결과를 다시 작성하기 위한 단일 폼이다. 기존 v1 결과와 Redis 장애 fallback 수정 후 결과만 이 문서에 정리한다.

## 문서 메타데이터

| 항목 | 값 |
|---|---|
| 작성 날짜 | TODO |
| 대상 브랜치/커밋 | TODO |
| 테스트 환경 | TODO |
| 비교 대상 | v1 기존 결과 vs local queue fallback 수정 후 결과 |

## 요약 결론

- TODO:

## 비교 결과

| 구분 | v1 기존 결과 | 수정 후 결과 | 판정 | 비고 |
|---|---|---|---|---|
| 초과판매 여부 | TODO | TODO | TODO | TODO |
| confirmed count | TODO | TODO | TODO | TODO |
| occupied count | TODO | TODO | TODO | TODO |
| Redis 장애 중 응답 분포 | TODO | TODO | TODO | TODO |
| local queue accepted/full | 해당 없음 | TODO | TODO | TODO |
| worker drain 시간 | 해당 없음 | TODO | TODO | TODO |
| DB pressure | TODO | TODO | TODO | TODO |
| p95 latency | TODO | TODO | TODO | TODO |
| dropped iterations | TODO | TODO | TODO | TODO |

## 실행 명령

| 구분 | 명령 | 비고 |
|---|---|---|
| v1 기존 결과 | TODO | TODO |
| 수정 후 결과 | TODO | TODO |

## 원시 결과 위치

| 구분 | 파일/디렉토리 | 포함 내용 |
|---|---|---|
| v1 기존 결과 | TODO | TODO |
| 수정 후 결과 | TODO | TODO |

## DB Snapshot

| 구분 | confirmed | held | payment_unknown | released/failed | 비고 |
|---|---:|---:|---:|---:|---|
| v1 기존 결과 | TODO | TODO | TODO | TODO | TODO |
| 수정 후 결과 | TODO | TODO | TODO | TODO | TODO |

## 관측 지표

| 지표 | v1 기존 결과 | 수정 후 결과 | 해석 |
|---|---|---|---|
| Hikari active/pending | TODO | TODO | TODO |
| Redis timeout/failover | TODO | TODO | TODO |
| local queue active_count | 해당 없음 | TODO | TODO |
| local queue full count | 해당 없음 | TODO | TODO |
| PG confirm count | TODO | TODO | TODO |

## 남은 확인 사항

| ID | 항목 | 필요한 작업 | 우선순위 |
|---|---|---|---|
| TODO | TODO | TODO | TODO |
