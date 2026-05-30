# 검색어 자동완성 시스템 (Search Autocomplete System)

**Book chapter:** Alex Xu Vol.1 Ch.13 (pp.223–245)
**Slug:** search-autocomplete
**Scale class:** large (10M DAU, Google-scale)
**Type:** Mock-ready

## Canned numbers (book's starting assumptions — Clarifying starting point)

- 10M DAU [p.224]
- 10 searches/day per user → 100M searches/day
- 5-char avg query → ~5 autocomplete requests per search → 50 autocomplete reqs/user/day
- QPS: ~24K avg, ~48K peak [p.225]
- New query data: 10M × 10 × 20B = 0.4GB/week [p.225]
- Response time: <100ms [p.224]
- Top 5 suggestions only

## Functional requirements (book's defaults)

- Top 5 suggestions as user types [p.224]
- Ranked by search frequency [p.224]
- Lowercase alphabetic only [p.225]
- Based on historical search data (not real-time trends) [p.225]

## Non-functional requirements (book's defaults)

- 10M DAU [p.224]
- <100ms response [p.224]
- High relevance (frequency-ranked)
- Scalable to 48K QPS peak
- High availability

## Core decisions

| Decision | Options | Winner / Notes |
|---|---|---|
| Data structure | SQL LIKE vs Trie | **Trie** at scale; SQL `LIKE 'prefix%' ORDER BY frequency LIMIT 5` only for small [p.228] |
| Trie lookup complexity | Unoptimized O(p + c + clog(c)) | Optimization 1: cap max prefix depth (~50) [p.234] |
| Top-k retrieval | Full subtree scan vs cached top-k | **Optimization 2: cache top-N at each trie node → O(1) retrieval** [pp.234–235] |
| Trie update | Real-time vs batch | **Weekly batch rebuild + atomic swap** [pp.236–237] |
| Trie storage | In-memory only vs serialized | Serialize to MongoDB or KV store [p.237] |
| Log volume | Full logging vs sampling | Sample 1/N queries to reduce log volume [p.234] |
| Trie sharding | Single vs sharded | Shard by first character or query distribution [pp.241–242] |
| Client optimization | No cache vs AJAX debounce + browser cache | AJAX per keystroke + `Cache-Control: max-age=3600` [p.238] |

**Two-service architecture:**
1. **Data gathering service**: collects query logs → aggregators (Hadoop/Spark/Kafka) → trie builder (weekly batch)
2. **Query service**: serves `/v1/search/autocomplete?query=tw` → reads from in-memory trie cache replicas

## Key components

- Data gathering service (query log collector)
- Aggregators (Hadoop / Spark / Kafka Streams)
- Trie builder (weekly batch job)
- Trie DB (serialized trie in MongoDB or KV store)
- Trie cache (in-memory replicas, replicated across query servers)
- Query service (`/v1/search/autocomplete?query=<prefix>`)
- Workers (coordinate trie rebuild + atomic swap)

## Common traps

- Real-time trie rebuild — too expensive at 24K QPS [p.236]
- No top-k cache at each trie node → full subtree traversal on every query [p.234]
- Naive SQL LIKE — doesn't scale beyond small data [p.228]
- Logging every keystroke at 100M users — enormous volume; must sample [p.234]
- Single trie instance — SPOF + memory bottleneck [p.241]
- No Unicode / non-ASCII handling in trie [p.241]
- Forgetting browser-side cache → unnecessary autocomplete requests per keystroke

## Deep-dive topics

- Trie construction + traversal + top-k caching per node [pp.228–235]
- Weekly atomic trie swap (blue/green trie) [pp.236–237]
- Trie serialization format [p.237]
- AJAX debounce + browser cache headers [pp.238–239]
- Trie sharding strategies [pp.241–242]
- Batch vs real-time tradeoffs [p.243]
- Kafka + Spark Streaming for near-real-time trie updates [p.243]

## Key design dimensions

Data structure design (trie), Batch vs real-time processing, Caching (in-memory + browser), Data sharding, Low-latency API design

## Cross-references in sysdesign-frameworks

- `building-blocks.md` (cache section — in-memory trie replicas; batch processing section)
- `scalability-patterns.md` (stateless query tier; trie sharding)

**Source:** alex-xu-vol2-problems §search-autocomplete, Alex Xu Vol.1 Ch.13 (pp.223–245)
