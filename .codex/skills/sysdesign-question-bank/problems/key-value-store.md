# 키-값 저장소 설계 (Key-Value Store)

**Book chapter:** Alex Xu Vol.1 Ch.6 (pp.91–115)
**Slug:** key-value-store
**Scale class:** large (DynamoDB / Cassandra scale)
**Type:** Building-block reference

## Canned numbers (book's starting assumptions — Clarifying starting point)

- Key size ≤10KB; value size ≤10KB [p.92]
- Single server: ~32GB with 32KB/key avg [p.93]
- Distributed: consistent hashing across N nodes
- Replication: N=3 typical [p.97]
- Quorum: W+R>N for strong consistency [pp.98–100]

## Functional requirements (book's defaults)

- `put(key, value)` and `get(key)` operations
- Key size ≤10KB; value size ≤10KB [p.92]
- High availability, scalability, automatic scaling, tunable consistency, low latency [p.92]

## Non-functional requirements (book's defaults)

- Low latency reads and writes
- High availability (even during partition)
- Tunable consistency (CP or AP mode)
- Auto rebalancing on node join/leave

## Core decisions

| Decision | Options | Notes |
|---|---|---|
| Single vs distributed | Single (32GB limit) vs distributed | Distributed via consistent hashing [p.93] |
| CAP choice | CA (unrealistic) / CP / AP | AP for availability; CP for consistency [pp.94–96] |
| Partitioning | Modulo vs consistent hashing | Consistent hashing [p.96] |
| Replication | N servers clockwise (distinct physical) | N=3 typical [p.97] |
| Consistency model | W+R>N (strong) / W=1,R=N (fast write) / W=N,R=1 (fast read) | Quorum consensus [pp.98–100] |
| Conflict resolution | Last-write-wins vs vector clocks | Vector clocks for causality tracking [pp.100–103] |
| Failure detection | Centralized heartbeat vs gossip | Gossip protocol [pp.103–105] |
| Failure handling (temp) | Strict quorum vs sloppy quorum + hinted handoff | Sloppy quorum + hinted handoff [p.105] |
| Failure handling (perm) | Manual repair vs anti-entropy | Anti-entropy with Merkle trees [pp.106–107] |
| Write path | Simple KV vs LSM-tree | Commit log → bloom filter → SSTable [pp.109–111] |

## Key components

- Consistent hashing ring
- Replication across N coordinator nodes
- Quorum consensus (configurable W, R, N)
- Vector clocks (version tracking per key)
- Gossip protocol (decentralized failure detection)
- Bloom filter (fast negative lookup)
- SSTable + commit log (LSM-tree write path)
- Merkle tree (anti-entropy sync)

## Common traps

- Forgetting CAP tradeoff — strong consistency = lower availability [p.94]
- Synchronous replication blocks writes — hurts availability [p.97]
- Centralized heartbeat = SPOF [p.103]
- Confusing read-repair (lazy, triggered on read) with anti-entropy (proactive, background) [p.107]
- Putting all replicas on same physical rack — loses fault isolation [p.97]

## Deep-dive topics

- CAP theorem applied [pp.94–96]
- Vector clocks and version reconciliation [pp.100–103]
- Gossip protocol mechanics [pp.103–105]
- Merkle tree synchronization [pp.106–107]
- LSM-tree / SSTable write path [pp.109–111]

## Key design dimensions

Data partitioning (consistent hashing), Replication strategy, Consistency model (CAP, quorum), Failure detection/handling, Storage engine design

## Cross-references in sysdesign-frameworks

- `building-blocks.md` (data partitioning — consistent hashing; storage engine — LSM-tree)
- `scalability-patterns.md` (replication, quorum consensus)
- Prereq reading: consistent-hashing.md

**Source:** alex-xu-vol2-problems §key-value-store, Alex Xu Vol.1 Ch.6 (pp.91–115)
