# URL 단축기 설계 (URL Shortener)

**Book chapter:** Alex Xu Vol.1 Ch.8 (pp.127–139)
**Slug:** url-shortener
**Scale class:** medium-to-large (100M URLs/day write, 10x read)
**Type:** Mock-ready

## Canned numbers (book's starting assumptions — Clarifying starting point)

- 100M URLs shortened per day [p.129]
- Write QPS: ~1,160/sec (100M ÷ 86,400); peak ~2,320/sec [p.129]
- Read QPS: ~11,600/sec (10:1 read-to-write ratio) [p.129]
- Storage: 100M × 365 days × 10 years × 100B/record ≈ 36.5TB [p.129]
- Cache: 20% of daily URLs
- Short URL length: 7 chars → base62^7 ≈ 3.5T combinations [p.130]

## Functional requirements (book's defaults)

- Long URL → short URL (e.g., `tinyurl.com/y7ke-ocwj`)
- Short URL → redirect to long URL
- High availability, scalability, fault tolerance [p.128]

## Non-functional requirements (book's defaults)

- Write QPS ~1,160 (peak ~2,320)
- Read QPS ~11,600
- Storage 36.5TB over 10 years
- Cache 20% of daily read traffic
- Short URL collision-free and URL-safe

## Core decisions

| Decision | Options | Winner / Notes |
|---|---|---|
| API design | REST | POST `/api/v1/data/shorten`, GET `/api/v1/shortUrl` [p.130] |
| Redirect type | 301 (permanent, browser-cached) vs 302 (temporary, always hits server) | 302 when analytics needed; 301 for bandwidth savings [pp.131–132] |
| Short URL generation | Hash + collision check (MD5/SHA-1 truncated) vs base62 of unique ID | **Base62 encoding of Snowflake ID — winner**: no collision [p.134] |
| Storage | Relational vs NoSQL | Single-table relational DB at this scale [p.135] |
| Caching | No cache vs Redis | Redis — hot mappings cached; DB is bottleneck otherwise [p.136] |

**301 vs 302 rule:**
- 301 → browser caches permanently → server loses visibility → bad for analytics
- 302 → every redirect hits server → good for click analytics

**Hash truncation problem:** MD5/SHA-1 → take first 7 chars → collision check in DB → if collision, append salt and retry → slow at scale [p.133].
**Base62 winner:** Snowflake ID → base62 encode → no collision check needed; downside: predictable sequential URLs [p.134].

## Key components

- Web servers (stateless, horizontally scalable)
- Relational DB (id → short_url → long_url mapping)
- Cache (Redis — hot short URL → long URL mappings)
- Unique ID generator (Snowflake, Ch.7)
- Load balancer

## Common traps

- Choosing 301 when analytics needed — browser caches, server loses redirect visibility [pp.131–132]
- Naive hash truncation → frequent collisions at scale [p.133]
- No cache → DB bottleneck on read-heavy traffic [p.136]
- Auto-increment ID without encoding → predictable/sequential short URLs (security issue) [p.134]
- Forgetting peak QPS = 2x average

## Deep-dive topics

- Hash vs base62 encoding [pp.132–134]
- 301 vs 302 redirect semantics [pp.131–132]
- Cache eviction strategy [p.136]
- DB sharding for growth [p.138]

## Key design dimensions

API design, Hashing/encoding, Caching, HTTP redirect semantics, DB choice/schema

## Cross-references in sysdesign-frameworks

- `building-blocks.md` (cache section — Redis hot path; ID generation section — Snowflake)
- `scalability-patterns.md` (stateless web tier; DB sharding for 36.5TB)
- Sub-components from: unique-id-generator.md (Snowflake ID), consistent-hashing.md (DB sharding)

**Source:** alex-xu-vol2-problems §url-shortener, Alex Xu Vol.1 Ch.8 (pp.127–139)
