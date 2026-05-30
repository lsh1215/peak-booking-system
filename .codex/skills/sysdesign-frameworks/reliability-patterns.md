# Reliability Patterns

## Replication [Ch. 6 §C.3]

| Mode | Data loss risk | Write latency | Use case |
|---|---|---|---|
| **Synchronous** | Zero (all replicas confirm) | High (waits for all ACKs) | Financial data; zero-loss requirement |
| **Asynchronous** | Yes (recent writes lost on failure) | Low (fire-and-forget) | High-throughput writes; eventual consistency OK |
| **Quorum (W+R>N)** | Configurable | Configurable | Balance between the two; most distributed KV stores |

## Gossip Protocol [Ch. 6 §C.3]

**Mechanism:** Each node periodically selects a random subset of neighbors and exchanges state (membership list, health status). Information propagates exponentially through the cluster.

| Aspect | Detail |
|---|---|
| Purpose | Membership detection, failure detection, configuration propagation |
| Convergence | Exponential spread — information reaches all N nodes in O(log N) rounds |
| Used by | Cassandra, Amazon DynamoDB |
| Advantage over centralized heartbeat | No single point of failure; scales with cluster size |

**Trap:** Centralized heartbeat server = SPOF. Use gossip for decentralized failure detection.

## Vector Clocks [Ch. 6 §C.3]

**Purpose:** Track causality between versions of a data item across distributed nodes.

**Mechanism:** Each update increments the clock for the originating server. A vector clock is a list of `(server, version)` pairs.

| Situation | Resolution |
|---|---|
| Clock A dominates B | A's version happened-after B; use A |
| B dominates A | B's version happened-after A; use B |
| Neither dominates | Concurrent updates → conflict; require application-level resolution (e.g., last-write-wins or merge) |

**Used in:** Amazon Dynamo-style systems (original Dynamo paper), Riak.
**Limitation:** Vector size grows with number of servers; requires pruning strategy.

## Merkle Trees (Anti-Entropy) [Ch. 6 §C.3]

**Purpose:** Efficiently detect which data ranges have diverged between two replicas.

**Mechanism:** Binary hash tree where:
- Leaf nodes = hashes of data blocks
- Parent nodes = hashes of their children

**Sync process:**
1. Two nodes exchange root hashes. If equal → no divergence.
2. If different → compare child hashes recursively to isolate divergent subtrees.
3. Only sync the divergent data blocks — minimizes data transfer.

**Used by:** Cassandra (anti-entropy repair), Amazon DynamoDB.

## Failover Strategies [Ch. 6 §C.3]

| Type | Description | Recovery time | Cost |
|---|---|---|---|
| **Cold standby** | Standby off; manual intervention to start | Minutes–hours | Lowest |
| **Warm standby** | Standby receives updates but doesn't serve traffic | Seconds–minutes | Medium |
| **Hot standby** (active-active) | Both nodes serve traffic simultaneously | Near-instant | Highest |

**Requirements:** Heartbeat monitoring, consensus on failure (avoid split-brain), data synchronization before promotion.

## Circuit Breaker [Ch. 6 §C.3]

**Purpose:** Prevent cascading failures when a downstream service degrades.

| State | Behavior | Transition |
|---|---|---|
| **Closed** (normal) | Calls pass through; failures counted | N consecutive failures → Open |
| **Open** | Calls fail-fast immediately (no actual call) | After timeout → Half-Open |
| **Half-Open** | Test request allowed through | Success → Closed; Failure → Open |

**Benefit:** Fails fast rather than blocking threads, preventing upstream service exhaustion.

## Retry with Exponential Backoff + Jitter [Ch. 6 §C.3]

```
delay = base_delay × 2^attempt + random_jitter
```

| Aspect | Detail |
|---|---|
| Purpose | Retry transient failures without overwhelming the recovering service |
| Jitter | Random component prevents thundering herd (all clients retrying simultaneously) |
| Max retries | Cap at 3–5 attempts; give up after max_delay |
| Requirement | Operations must be **idempotent** — retried calls must not double-write |

## Idempotency [Ch. 6 §C.3]

**Definition:** Executing an operation multiple times produces the same result as executing it once.

| Implementation | Mechanism |
|---|---|
| Idempotency key | Client sends unique request ID; server deduplicates on key |
| Conditional writes | Write only if version/etag matches (optimistic locking) |
| Natural idempotency | Upsert (INSERT OR REPLACE), SET operations |

**Critical for:** Safe retries in distributed systems, payment processing, notification delivery dedup.

**Source:** alex-xu-vol1 §C.3 (Ch. 6)
