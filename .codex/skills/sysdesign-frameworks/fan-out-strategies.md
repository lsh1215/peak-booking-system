# Fan-Out Strategies

## Push / Pull / Hybrid Taxonomy [Ch. 11 §news-feed]

| Strategy | Also called | Mechanism | Pros | Cons |
|---|---|---|---|---|
| **Push** | Fan-out on write | On post creation, precompute and write post ID to every follower's feed cache | Fast reads (feed cache is pre-warmed) | Wasted compute for users with many followers; celeb post → 10M+ cache writes; wastes compute for inactive users |
| **Pull** | Fan-in on read | On feed request, query all followees' post tables and merge in real time | No wasted write compute; always fresh | Slow reads at request time; O(k) DB queries where k = number of followees |
| **Hybrid** | Mixed | Push for normal users; pull for celebrities/high-follower accounts | Balances read speed and write efficiency | More complex routing logic |

## Celebrity / Hot Key Problem [Ch. 11]

**Scenario:** A celebrity with 30M followers posts a tweet.
- **Pure push:** 30M feed cache writes triggered synchronously → even at 1ms/write → 8 hours of sequential work.
- **Pure pull:** Every follower's feed read triggers a query back to the celebrity's posts → DB hotspot.

**Solution (Hybrid):**
- Identify "celebrity threshold" (e.g., >1M followers or >10K followers depending on system scale).
- **Normal users:** Fan-out on write — push post ID to follower feed caches immediately.
- **Celebrity accounts:** Do NOT fan-out on write. On feed read, merge celebrity posts from their post table with the pre-computed feed cache of non-celebrity followees.

```
Feed for user U =
  precomputed cache (normal followees, fan-out on write)
  + on-demand query (celebrity followees, fan-in on read)
```

## Decision Guide

| Condition | Choose |
|---|---|
| Read-heavy system; most users have ≤1K followers | Push (fan-out on write) |
| Write-heavy system; celebrities dominate traffic | Pull (fan-in on read) for celebrities |
| Mixed follower distribution (normal users + celebrities) | Hybrid |
| Users mostly inactive (low DAU/MAU ratio) | Pull (avoid wasted push compute to inactive feeds) |

## Implementation Details

| Aspect | Detail |
|---|---|
| Feed cache structure | Bounded list of post IDs per user (e.g., ~500 entries); store IDs only, not full post objects |
| Feed cache backing | Redis; bounded TTL to evict inactive users |
| Multi-tier cache | news-feed cache → post cache → user cache → friend list cache → counter cache |
| Inactive user handling | Do not pre-compute feeds for users inactive >30 days; rebuild lazily on login |

## Cross-References

| Problem | Fan-out usage |
|---|---|
| **News Feed System** (Vol.1 Ch.11, pp.185–196) | Canonical fan-out example; hybrid push/pull for celebrities |
| **Chat System group messages** (Vol.1 Ch.12, pp.213–215) | Group fan-out: copy message to each member's sync queue (inbox model); WeChat caps at 500 members for this reason |
| **Notification System** (Vol.1 Ch.10) | Fan-out via per-channel message queues (push model) |
| **Twitter Design** (youtube-insights V3) | Fan-out is the central decision point; 500M DAU, 1000:1 read-write ratio motivates hybrid |

**Source:** alex-xu-vol1 §B (Ch. 11, Ch. 12) + youtube-insights V3 (Design Twitter walkthrough)
