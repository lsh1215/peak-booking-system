# Unique ID Generation in Distributed Systems

## Requirements [Ch. 7 §C.6]

- Globally unique across all servers and datacenters
- Numeric only (fits in 64-bit BIGINT)
- Time-sortable (for efficient indexing and ordering)
- High throughput: >10,000 IDs/second minimum
- No coordination bottleneck

## Four Approaches Compared

| Approach | Description | Pros | Cons |
|---|---|---|---|
| **Multi-master replication** | Each DB server auto-increments by N (server count). Server 1: 1,3,5…; Server 2: 2,4,6… | No external coordinator | Not time-ordered across servers; doesn't scale across DCs; hard to add servers |
| **UUID** | 128-bit random number (e.g., `550e8400-e29b-41d4-a716-446655440000`) | Simple; no coordination; extremely low collision probability | 128-bit (2× storage); non-numeric; not time-sortable; not human-readable |
| **Ticket server** | Centralized single DB using `AUTO_INCREMENT` as ID source | Simple; proven (Flickr used this) | Single point of failure; bottleneck at scale |
| **Twitter Snowflake** | 64-bit structured ID (see below) | Time-sortable; no coordination; 64-bit; high throughput | Requires clock synchronization (NTP) |

**Recommended: Twitter Snowflake** for distributed systems requiring time-sortable, numeric, 64-bit IDs.

## Snowflake Bit Layout

```
 63      62                  22   17     12    11               0
 ┌──────┬──────────────────────┬────────┬────────┬────────────────┐
 │  0   │  timestamp (41 bits) │ DC (5) │ MC (5) │ sequence (12)  │
 └──────┴──────────────────────┴────────┴────────┴────────────────┘
  sign     milliseconds since      DC ID   Machine  IDs per ms
  bit      custom epoch             0-31    ID 0-31   0-4095
```

| Field | Bits | Capacity | Notes |
|---|---|---|---|
| Sign bit | 1 | — | Always 0 (reserved for future use) |
| Timestamp | 41 | ~69.7 years | Milliseconds since custom epoch (e.g., Jan 1, 2020) |
| Datacenter ID | 5 | 2^5 = 32 datacenters | Configured at service startup |
| Machine ID | 5 | 2^5 = 32 machines per DC | Configured at service startup (e.g., via ZooKeeper) |
| Sequence number | 12 | 2^12 = 4,096 IDs per ms per machine | Resets to 0 each millisecond |

**Total:** 1 + 41 + 5 + 5 + 12 = **64 bits**

**Max throughput:** 32 DCs × 32 machines × 4,096 IDs/ms = ~4.2 billion IDs/ms (theoretical max).

## Clock Skew and NTP

**Problem:** If a machine's clock moves backwards (e.g., NTP correction), the same millisecond timestamp can be reused → duplicate IDs.

| Mitigation | Description |
|---|---|
| Wait out the skew | If `current_time < last_generated_time`, wait until clock catches up |
| NTP with bounded drift | Configure NTP to step clocks only within a safe threshold |
| Sequence number as buffer | 4,096 IDs per ms per machine provides buffer for minor sub-ms jitter |

**Trap:** Clock skew is real in distributed environments — never assume clocks are perfectly synchronized.

## Decision Guide

| Requirement | Choose |
|---|---|
| Simple, no distributed concern | Ticket server (if SPOF is acceptable) |
| Globally unique, don't need ordering | UUID |
| Globally unique, time-sortable, high throughput | **Snowflake** |
| Already have multi-master DB setup, low scale | Multi-master replication |

**Source:** alex-xu-vol1 §C.6 (Ch. 7)
