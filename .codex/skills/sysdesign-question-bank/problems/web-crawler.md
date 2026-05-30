# 웹 크롤러 설계 (Web Crawler)

**Book chapter:** Alex Xu Vol.1 Ch.9 (pp.141–163)
**Slug:** web-crawler
**Scale class:** large (1B pages/month)
**Type:** Mock-ready

## Canned numbers (book's starting assumptions — Clarifying starting point)

- 1B pages/month → ~400 pages/sec [p.143]
- Average page size: 500KB → 500TB/month raw HTML [p.143]
- 5-year storage: 30PB [p.143]
- Multi-threaded, per-domain rate-limited

## Functional requirements (book's defaults)

- Crawl from seed URLs; download HTML; extract URLs
- Store fetched pages for search engine indexing
- Scale to billions of pages [p.142]
- Robust to bad HTML, unresponsive servers, spider traps
- Politeness: respect robots.txt, don't overwhelm individual servers [p.142]
- Extensible to non-HTML content (images, PDFs)

## Non-functional requirements (book's defaults)

- 1B pages/month (~400 pages/sec sustained)
- 500KB/page avg → 500TB/month storage
- Multi-threaded with per-domain delay enforcement
- 30PB over 5 years

## Core decisions

| Decision | Options | Winner / Notes |
|---|---|---|
| URL Frontier queue | Single FIFO vs two-tier | Two-tier: priority queue (PageRank-style) + politeness queue (per-domain rate-limited) [pp.149–152] |
| DNS resolution | Per-request DNS vs cached DNS | Cached DNS resolver (TTL-aware) — DNS is a serious bottleneck [p.153] |
| Content dedup | No check vs hash comparison | Hash page body to detect duplicates [pp.155–156] |
| URL dedup | DB lookup vs bloom filter | Bloom filter on visited URLs — space-efficient [p.155] |
| Storage | Local disk vs distributed | Distributed: S3 or HDFS for raw HTML + metadata [p.157] |

**Two-tier URL Frontier:**
1. Prioritizer: assigns priority score (PageRank, traffic, freshness) → priority queues
2. Politeness queue: per-domain rate-limited queues → front queue selector → back queue router

## Key components

- Seed URL store
- URL Frontier: prioritizer + politeness queues (per-domain)
- DNS resolver (cached, TTL-aware)
- HTML downloader (multi-threaded, robots.txt aware)
- Content parser
- Content dedup checker (hash of page body)
- URL extractor + normalizer
- URL filter + dedup (bloom filter on visited URLs)
- Visited URLs DB
- Content storage (S3 / HDFS)

## Common traps

- Spider traps (infinite loop URLs) — need URL length limits + cycle detection [p.158]
- Single FIFO queue → no politeness control, overwhelms individual servers [p.149]
- Ignoring robots.txt — legal and ethical violation [p.153]
- No content dedup → wasted storage and indexing [p.155]
- Single DNS resolver → bottleneck [p.153]
- Storing content in relational DB → write throughput too low for 400 pages/sec

## Deep-dive topics

- Two-tier URL Frontier design [pp.149–152]
- Bloom filter for URL dedup [p.155]
- Politeness rate limiting per domain [p.150]
- Spider traps + duplicate content handling [pp.157–160]
- Extensibility to non-HTML (images, PDFs) [p.160]

## Key design dimensions

Queue design (priority + politeness), Deduplication (bloom filter), Distributed crawling, Politeness/robots.txt, Failure handling

## Cross-references in sysdesign-frameworks

- `building-blocks.md` (message queue section; bloom filter for space-efficient dedup)
- `scalability-patterns.md` (distributed storage at 30PB scale; worker pool patterns)

**Source:** alex-xu-vol2-problems §web-crawler, Alex Xu Vol.1 Ch.9 (pp.141–163)
