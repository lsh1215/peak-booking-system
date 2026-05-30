# 분산 시스템을 위한 유일 ID 생성기 설계 (Unique ID Generator)

**Book chapter:** Alex Xu Vol.1 Ch.7 (pp.117–125)
**Slug:** unique-id-generator
**Scale class:** large (distributed-systems scale)
**Type:** Mock-ready

## Canned numbers (book's starting assumptions — Clarifying starting point)

- >10,000 IDs/sec [p.118]
- Twitter Snowflake: 64-bit total
  - 1-bit sign | 41-bit ms timestamp | 5-bit datacenter | 5-bit machine | 12-bit sequence
  - ~41 years from custom epoch
  - 1,024 machines (2^10), 4,096 IDs/ms/machine (2^12) [pp.121–123]

## Functional requirements (book's defaults)

- IDs unique across all servers
- Numerical only, fits in 64 bits
- Time-sortable (IDs sortable by generation time)
- >10,000 IDs/sec [p.118]

## Non-functional requirements (book's defaults)

- High availability
- Low latency (ID generation must not bottleneck callers)
- Uniqueness under distributed conditions (no global coordination)

## Core decisions

| Approach | Problem | Verdict |
|---|---|---|
| Multi-master replication (per-server offset auto-increment) | Doesn't scale across DCs; not time-ordered [p.119] | Rejected |
| UUID (128-bit) | Too large, non-numeric, not time-sortable [p.119] | Rejected |
| Ticket server (Flickr: centralized auto-increment) | SPOF [p.120] | Rejected |
| **Twitter Snowflake** | Requires NTP; clock-backwards risk | **Winner** [pp.121–123] |

**Snowflake bit layout:**
```
[1 sign][41 timestamp ms][5 datacenter][5 machine][12 sequence]
```
- Clock sync: NTP; clock-backwards = risk of duplicate IDs [p.124]
- Datacenter ID + Machine ID: assigned via config or ZooKeeper [p.122]

## Key components

- ID generator service (one per machine — no cross-machine coordination)
- 64-bit Snowflake structure
- Datacenter ID + Machine ID (config or ZooKeeper assignment)
- Sequence counter (resets each millisecond)
- NTP (clock synchronization)

## Common traps

- Auto-increment across shards without offset → duplicate IDs [p.119]
- UUID — too large, non-sortable [p.119]
- Centralized ticket server → SPOF [p.120]
- Clock skew (NTP adjustment backward) → duplicate or out-of-order IDs [p.124]
- Forgetting 41-bit timestamp overflow ~year 2083 (from custom epoch 2010)

## Deep-dive topics

- Snowflake bit layout and capacity math [pp.121–123]
- NTP sync and clock-backwards handling [p.124]
- ZooKeeper for machine ID assignment [p.122]

## Key design dimensions

Distributed coordination, ID design (bit packing), SPOF avoidance

## Cross-references in sysdesign-frameworks

- `building-blocks.md` (unique ID section — Snowflake is the reference answer)
- Used as sub-component in: url-shortener (base62 encoding of unique ID), chat-system (per-channel message ID)

**Source:** alex-xu-vol2-problems §unique-id-generator, Alex Xu Vol.1 Ch.7 (pp.117–125)
