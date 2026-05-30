---
name: sysdesign-frameworks
description: >-
  Reusable system design frameworks and reference data. Capacity estimation
  formulas (QPS, storage, bandwidth), Jeff Dean / Colin Scott latency numbers,
  building blocks (load balancer, replication, sharding, cache, CDN, message
  queue), CAP theorem and consistency models, rate limiting algorithms,
  Snowflake ID generation, consistent hashing, fan-out strategies (push/pull/
  hybrid), Hello Interview delivery framework. Loaded by sysdesign-design /
  sysdesign-sdd / sysdesign-impl when their respective triggers fire, OR
  auto-injected when the user asks about a specific framework.
triggers:
  - "back-of-envelope"
  - "개략적 규모"
  - "개략적인 규모 추정"
  - "regards추정"
  - "용량 추정"
  - "캐시 도입"
  - "캐시 전략"
  - "캐싱 전략"
  - "제프 딘"
  - "jeff dean"
  - "latency number"
  - "샤딩 전략"
  - "consistent hashing"
  - "안정 해시"
  - "fan-out"
  - "fan out"
  - "팬아웃"
  - "푸시 풀"
  - "rate limiter"
  - "rate limiting"
  - "처리율 제한"
  - "토큰 버킷"
  - "snowflake"
  - "유일 id"
  - "unique id"
  - "cap theorem"
  - "cap 정리"
  - "consistency model"
  - "일관성 모델"
  - "quorum"
  - "정족수"
  - "vector clock"
  - "벡터 시계"
  - "gossip protocol"
  - "merkle tree"
  - "delivery framework"
  - "hello interview framework"
  - "load balancer 알고리즘"
  - "db 선택"
  - "sql vs nosql"
---

# sysdesign-frameworks

Reference material loaded during system design conversations. Each framework
lives in its own file; read only the one(s) the current question needs.

## Index

| Topic | File | When to load |
|---|---|---|
| Back-of-envelope formulas + powers-of-2 + availability table + worked example | `capacity-estimation.md` | "DAU 100M이면 저장 얼마?" / "QPS 계산해줘" / "개략적 규모 추정" |
| Jeff Dean latency table + derivations | `latency-numbers.md` | "캐시 도입하면 얼마나 빨라?" / "DB → SSD → memory 차이" / "latency number" |
| Building blocks (LB, replication, sharding, cache, CDN, MQ, multi-region) | `building-blocks.md` | "이 컴포넌트 어떻게 구성?" / "수평 확장 전략?" / "로드밸런서 알고리즘" |
| SQL vs NoSQL decision + NoSQL sub-types | `db-selection.md` | "DB 선택 기준" / "sql vs nosql" / "cassandra vs postgres" |
| Cache patterns + eviction + hot key mitigation | `caching-strategies.md` | "캐시 전략" / "cache aside vs write-through" / "cache stampede" |
| CAP theorem + consistency models + quorum | `cap-and-consistency.md` | "cap 정리" / "eventual consistency" / "quorum" / "일관성 모델" |
| Replication, gossip, vector clocks, Merkle trees, circuit breaker, retry | `reliability-patterns.md` | "gossip protocol" / "vector clock" / "circuit breaker" / "retry backoff" |
| Hash ring + virtual nodes | `consistent-hashing.md` | "안정 해시" / "consistent hashing" / "샤딩 전략" |
| 5 rate-limit algorithms + Redis backend + HTTP 429 headers | `rate-limiting-algorithms.md` | "처리율 제한" / "rate limiter" / "토큰 버킷" / "leaky bucket" |
| UUID / Snowflake / ticket-server comparison + bit layout | `unique-id-generation.md` | "유일 id" / "snowflake" / "unique id generator" |
| Push / pull / hybrid fan-out + celebrity problem | `fan-out-strategies.md` | "fan-out" / "팬아웃" / "뉴스 피드" / "timeline 설계" |
| Alex Xu 4-step + Hello Interview 5-phase + seniority calibration | `delivery-frameworks.md` | "delivery framework" / "hello interview" / "인터뷰 구조" / "4 steps" |

## How agents use this skill

Read the index, then load only the file(s) relevant to the current decision.
Do not preload everything. Cite the source file in your response so the user
can dig deeper (e.g., "per `sysdesign-frameworks/capacity-estimation.md` ...").
