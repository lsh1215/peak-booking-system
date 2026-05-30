# Caching Strategies

## Cache Patterns [Ch. 1 §B.5]

| Pattern | Write path | Read path | Pros | Cons |
|---|---|---|---|---|
| **Cache-aside** (lazy loading) | App writes to DB directly | App checks cache → miss → read DB → populate cache | Most flexible; only caches what's actually read | Cache stampede on cold start; potential stale reads |
| **Write-through** | App writes to cache AND DB simultaneously | App reads from cache (always warm) | Strong consistency; no stale reads | Double write latency; cached data may be rarely read (wastes space) |
| **Write-around** | App writes to DB directly, bypasses cache | App reads from cache → miss → read DB → populate cache | Avoids caching write-once data | Cold reads always miss; higher read latency initially |
| **Read-through** | App writes to DB directly | Cache fetches from DB on miss (cache is in-line) | Simplifies application code | Cache becomes a dependency; implementation complexity |

**Default choice:** Cache-aside for most systems. Write-through when strong consistency is critical.

## TTL Strategy Tiers

| Tier | TTL range | Examples | Trade-off |
|---|---|---|---|
| Short | Seconds – minutes | Notification counts, trending topics, live scores | Fresh data; lower efficiency; more DB hits |
| Medium | Hours | Product catalogs, user profiles, search results | Balanced freshness and efficiency |
| Long | Days | Country lists, configuration, static reference data | Maximum efficiency; risk of stale data |

**Rule:** TTL should be shorter than the longest acceptable staleness for the use case.

## Eviction Policies

| Policy | Evicts | Best for | Trap |
|---|---|---|---|
| **LRU** (Least Recently Used) | Item not accessed for longest time | General purpose; temporal locality | Doesn't account for access frequency |
| **LFU** (Least Frequently Used) | Item accessed fewest times | Stable "hot" items that are rarely replaced | Slow to adapt to sudden popularity shifts |
| **FIFO** | Oldest inserted item | Simplicity | Ignores both recency and frequency |

**Default:** LRU is the default in Redis and most systems.

## Hot Key / Cache Stampede Mitigations

| Problem | Description | Mitigation |
|---|---|---|
| **Cache stampede** | Many concurrent misses on same key → all hit DB | Lease tokens (Facebook); mutex lock on miss; probabilistic early expiration |
| **Hot key** (celebrity problem) | Single key receives disproportionate reads | Replicate hot key across multiple cache nodes; local in-process cache tier |
| **Cache penetration** | Requests for non-existent keys bypass cache entirely | Cache null/sentinel values with short TTL; bloom filter to reject invalid keys |
| **Thundering herd** | All caches expire simultaneously | Jitter on TTL (randomize expiry across instances) |

## Consistency Challenge

Cache and DB can diverge. Solutions in order of preference:

1. **Delete-on-write:** On update, delete the cache entry (lazy reload on next read). Simple; most common.
2. **Write-through:** Update cache and DB together. Strong consistency; higher write cost.
3. **Accept eventual consistency:** For non-critical data (social counters, view counts).

## Facebook Scale Reference

Facebook operates **800+ cache servers** with 28 TB RAM total, achieving **~95% cache hit ratio** — only 5% of requests reach the database. They use **lease tokens** to prevent cache stampede: when a client receives a cache miss, the cache grants a lease token; only the token holder may populate the cache, and other concurrent misses wait rather than all querying the DB.

**Source:** alex-xu-vol1 §B.5 (Ch. 1)
