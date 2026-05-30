---
name: sysdesign-question-bank
description: >-
  Catalog of 12 canonical system design problems from Alex Xu Vol.1 with the
  book's canned numbers (DAU/QPS/storage), key decisions, common traps, and
  expected rubric categories per problem. Loaded explicitly by sysdesign-design
  when a topic is chosen — not auto-injected. Each problem has its own file
  under problems/. Vol.2 problems (proximity service, distributed message
  queue, etc.) are NOT in this catalog — see _vol2-gap.md.
disable-model-invocation: true
---

# sysdesign-question-bank

12 canonical problems from Alex Xu Vol.1 (Korean edition: 가상 면접 사례로 배우는
대규모 시스템 설계 기초). Each problem file contains the book's canned numbers,
core decisions, common traps, and deep-dive topics — used by sysdesign-design
as the Clarifying starting point.

## How sysdesign-design uses this

When the user picks a topic ("채팅 시스템 만들고 싶어"), sysdesign-design loads
the matching `problems/<slug>.md` and offers the book's canned numbers as the
starting assumptions. User then adjusts (e.g., "100M DAU로 가자").

## Catalog

| Slug | Title (KO / EN) | Type | Scale class |
|---|---|---|---|
| rate-limiter | 처리율 제한 장치의 설계 / Rate Limiter | Mock-ready (warm-up) | medium |
| consistent-hashing | 안정 해시 설계 / Consistent Hashing | Building-block reference | large |
| key-value-store | 키-값 저장소 설계 / Key-Value Store | Building-block reference | large |
| unique-id-generator | 분산 시스템을 위한 유일 ID 생성기 설계 / Unique ID Generator | Mock-ready | large |
| url-shortener | URL 단축기 설계 / URL Shortener | Mock-ready | medium-to-large |
| web-crawler | 웹 크롤러 설계 / Web Crawler | Mock-ready | large |
| notification-system | 알림 시스템 설계 / Notification System | Mock-ready | large |
| news-feed | 뉴스 피드 시스템 설계 / News Feed System | Mock-ready | large |
| chat-system | 채팅 시스템 설계 / Chat System | Mock-ready | large |
| search-autocomplete | 검색어 자동완성 시스템 / Search Autocomplete | Mock-ready | large |
| youtube | 유튜브 설계 / YouTube | Mock-ready | large |
| google-drive | 구글 드라이브 설계 / Google Drive | Mock-ready | large |

**Type definitions:**
- **Mock-ready**: Suitable as a standalone interview problem — bounded scope, clear requirements, tractable in 45–60 min.
- **Building-block reference**: Primarily an infrastructure concept used as a sub-component in other problems; rarely posed as a standalone interview topic.

## Recommended learner sequence

1. **rate-limiter** — bounded scope; algorithm comparison + Redis
2. **unique-id-generator** — short; introduces Snowflake
3. **url-shortener** — classic; API + hashing + caching + HTTP
4. **consistent-hashing** — pure infra concept; prereq for KV store and sharding
5. **key-value-store** — deepest theory (CAP, quorum, vector clock, gossip, Merkle); requires consistent-hashing
6. **notification-system** — multi-provider fan-out, queue decoupling
7. **web-crawler** — queue design, dedup (bloom), politeness
8. **news-feed** — fan-out write vs read tradeoff; foundational social
9. **search-autocomplete** — trie + batch pipeline
10. **chat-system** — real-time, stateful servers, message ordering
11. **google-drive** — chunking, delta sync, strong consistency, storage tiering
12. **youtube** — transcoding DAG, CDN optimization, pre-signed URLs, live streaming (most complex)

## Topic → trigger phrase mapping (for sysdesign-design)

| User says | Topic slug |
|---|---|
| 처리율 제한 / rate limiter / rate limiting / API throttling | rate-limiter |
| 안정 해시 / consistent hashing / consistent hash | consistent-hashing |
| 키-값 저장소 / key-value store / KV store / DynamoDB clone / Cassandra clone | key-value-store |
| 유일 ID / unique ID / distributed ID / snowflake / ID 생성기 | unique-id-generator |
| URL 단축기 / url shortener / tinyurl / bit.ly | url-shortener |
| 웹 크롤러 / web crawler / web crawling / 크롤러 | web-crawler |
| 알림 시스템 / notification system / push notification / 푸시 알림 | notification-system |
| 뉴스 피드 / news feed / 피드 / social feed / Instagram feed | news-feed |
| 채팅 시스템 / 채팅 서비스 / chat / messenger / WhatsApp clone / 메신저 | chat-system |
| 검색어 자동완성 / search autocomplete / typeahead / 자동완성 | search-autocomplete |
| 유튜브 / YouTube / 동영상 스트리밍 / video streaming | youtube |
| 구글 드라이브 / Google Drive / 파일 스토리지 / cloud storage / file sync | google-drive |

## Recurring cross-problem decisions (quick reference)

| Decision | Problems |
|---|---|
| Consistent hashing for partitioning | consistent-hashing, key-value-store, url-shortener, search-autocomplete |
| Cache layer (Redis/Memcached) | rate-limiter, url-shortener, news-feed, chat-system, search-autocomplete, youtube, google-drive |
| Message queue for decoupling | notification-system, news-feed, youtube, google-drive, web-crawler |
| Fan-out (write vs read vs hybrid) | news-feed, notification-system, chat-system |
| CDN for static/media | youtube, google-drive, news-feed |
| Bloom filter for dedup | web-crawler, key-value-store |
| WebSocket / real-time | chat-system, notification-system |

## Vol.2 gap

The following Vol.2 problems are NOT in this catalog (the Korean PDFs locally
available are both Vol.1):
- Proximity Service / Nearby Friends / Google Maps / Distributed Message Queue
  / Metrics Monitoring / Ad Click Aggregation / Hotel Reservation

See `problems/_vol2-gap.md` for the full list and acquisition note.

**Source:** alex-xu-vol2-problems.md (researched 2026-05-03); Alex Xu Vol.1 (2020), Korean translation Insight Press (2021).
