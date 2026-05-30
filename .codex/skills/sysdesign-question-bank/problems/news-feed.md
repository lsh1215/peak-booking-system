# 뉴스 피드 시스템 설계 (News Feed System)

**Book chapter:** Alex Xu Vol.1 Ch.11 (pp.185–196)
**Slug:** news-feed
**Scale class:** large (10M DAU, Facebook/Instagram-class)
**Type:** Mock-ready

## Canned numbers (book's starting assumptions — Clarifying starting point)

- 10M DAU [p.186]
- Up to 5,000 friends per user [p.186]
- High read-to-write ratio (implied)
- News feed cache: bounded list of ~500 post IDs per user [p.192]

## Functional requirements (book's defaults)

- Publish posts (text, images, video)
- See reverse-chronological feed of friends/followees
- Mobile + web support
- 10M DAU [p.186]
- Up to 5,000 friends per user [p.186]

## Non-functional requirements (book's defaults)

- Fast feed load (sub-few-hundred-ms)
- High availability
- Supports images and video
- 10M DAU

## Core decisions

| Decision | Options | Winner / Notes |
|---|---|---|
| APIs | REST | POST `/v1/me/feed` (publish), GET `/v1/me/feed` (read) [p.186] |
| Fan-out strategy | Push (write) vs Pull (read) vs Hybrid | **Hybrid: push for normal users, pull for celebrities** [p.191] |
| Feed cache content | Full post data vs post IDs only | Post IDs only — fetched from post DB on render [p.192] |
| Cache bound | Unbounded vs bounded | ~500 post IDs per user [p.192] |
| Cache architecture | Single-tier vs multi-tier | Multi-tier: news feed / post / user / friend list / counter [p.194] |

**Fan-out comparison:**
| Strategy | Write cost | Read cost | Problem |
|---|---|---|---|
| Push (fan-out on write) | High (1 post → N friend writes) | Low (precomputed) | Celebrity hotspot: 10M follower = 10M writes per post [p.190] |
| Pull (fan-out on read) | Low | High (compute at request time) | Slow for normal users with 5K friends [p.189] |
| **Hybrid** | Medium | Low | Push for normal; pull for celebrities [p.191] |

**Celebrity threshold:** User with many followers → skip pre-push, let followers pull on read.

## Key components

- Post service (write post to DB + message queue)
- Fan-out service (push post IDs to friends' feed cache; skip celebrities)
- News feed service (read feed cache → fetch post data → assemble)
- News feed cache (Redis — bounded list of post IDs per user)
- Post DB + post cache
- User DB (friend/follower graph)
- Media storage (CDN for images/video)
- Message queue (post → fan-out decoupling)

## Common traps

- Pure push fails for celebrities (10M followers = 10M writes per post) [p.190]
- Pure pull fails for normal users (real-time compute across 5K friends is slow) [p.189]
- Storing full post data in feed cache → memory waste [p.192]
- Unbounded feed cache → unbounded memory growth [p.192]
- Precomputing feed for inactive users → wasted compute [p.191]
- No message queue between post service and fan-out → tight coupling

## Deep-dive topics

- Fan-out write vs read vs hybrid decision [pp.189–191]
- Bounded feed cache design [p.192]
- Multi-tier cache architecture [p.194]
- Graph traversal at scale (Facebook TAO reference) [p.196]

## Key design dimensions

Fan-out strategy, Caching (multi-tier), Data partitioning, Celebrity/hotspot handling

## Cross-references in sysdesign-frameworks

- `fan-out-strategies.md` (push/pull/hybrid is THE central decision for this problem)
- `building-blocks.md` (cache section — multi-tier feed cache; message queue section)
- `scalability-patterns.md` (celebrity hotspot handling; stateless web tier)

**Source:** alex-xu-vol2-problems §news-feed, Alex Xu Vol.1 Ch.11 (pp.185–196)
