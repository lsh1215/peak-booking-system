# Latency Numbers Every Programmer Should Know

## Jeff Dean Canonical Table [Ch. 2]

| Operation | Latency | Notes |
|---|---|---|
| L1 cache reference | ~1 ns | CPU on-chip |
| L2 cache reference | ~10 ns | CPU on-chip |
| Main memory (RAM) access | ~100 ns | |
| Send 1 KB over 1 Gbps network | ~10 μs | |
| Read 4 KB randomly from SSD | ~100 μs | |
| Database insert (PostgreSQL commit) | ~1 ms | |
| Round-trip within same datacenter | ~0.5 ms | |
| Long-distance packet (CA → Netherlands → CA) | ~150 ms | |

**Time unit conversions:**
- 1 ns = 10^-9 s
- 1 μs = 10^-6 s = 1,000 ns
- 1 ms = 10^-3 s = 1,000 μs

## Key Takeaways

| Insight | Implication |
|---|---|
| Memory is ~1,000× faster than SSD | Avoid disk seeks; cache frequently accessed data in RAM |
| SSD is ~150× faster than cross-region network | Prefer local reads over remote calls when possible |
| Datacenter RTT (~0.5 ms) vs. cross-region (~150 ms) | Multi-region adds ~300× latency to synchronous replication |
| Simple compression is fast (nanoseconds) | Compress before network transfer; cost is usually worth it |

## Colin Scott Interactive Model

Colin Scott maintains an interactive version of the latency table that shows how numbers have changed over hardware generations:
https://colin-scott.github.io/personal_website/research/interactive_latency.html

Consult this tool when the interviewer asks about hardware trends or when you need to reason about whether latency numbers have changed since the Jeff Dean era. Do not fabricate interpolated values — use the tool directly.

## Common Derivations

### Cache Hit Savings

```
Without cache: DB query ≈ 1 ms
With cache hit: RAM lookup ≈ 0.1 μs = 0.0001 ms
Speedup: ~10,000×
```

### Network vs. Disk Choice

- If data fits in RAM and access is random: prefer in-memory (Redis/Memcached).
- If data must be durable and sequential: SSD is acceptable (~100 μs reads).
- Never cross a datacenter boundary for low-latency reads (<1 ms budget).

### Replication Latency Budget

```
Synchronous cross-region replication adds ~150 ms to write path.
Async replication avoids this but risks data loss on failure.
For <10 ms write p99: restrict synchronous replication to same datacenter only.
```

**Source:** alex-xu-vol1 §A.3 (Ch. 2) — originally Jeff Dean, Google
