# CAP Theorem and Consistency Models

## CAP Theorem [Ch. 6 §C.1]

A distributed system cannot simultaneously guarantee all three of:

| Property | Definition |
|---|---|
| **C** — Consistency | Every read returns the most recent write (or an error). All nodes see identical data. |
| **A** — Availability | Every request receives a non-error response (data may be stale). |
| **P** — Partition Tolerance | System continues operating despite network partition (communication failure between nodes). |

**Practical reality:** Network partitions are unavoidable. The real choice is **CP vs AP**.

| Trade-off | During partition | Behavior |
|---|---|---|
| **CP** | Minority partition stops accepting writes; returns errors | Consistency preserved; availability sacrificed |
| **AP** | All nodes continue serving; may return stale data | Availability preserved; consistency relaxed (eventual) |
| **CA** | Impossible in distributed systems; partitions can always occur | Only applies to single-node RDBMS (not distributed) |

## CP vs AP Examples

| System | Default | Why |
|---|---|---|
| HBase | CP | Strong consistency required for big-data analytics |
| Zookeeper | CP | Distributed lock / leader election must be consistent |
| PostgreSQL (single-node) | Effectively CA | No partitions between nodes (single machine) |
| Cassandra | AP (tunable) | High availability; with `ALL` consistency level → CP |
| DynamoDB | AP (default) | High availability; consistent reads available per-request |
| DNS | AP | Availability critical; stale records are acceptable |
| CDN caches | AP | Edge nodes serve stale content during origin failure |

**Use CP when:** Financial transactions, distributed locks, leader election, configuration management.
**Use AP when:** Social feeds, user profiles, shopping carts, DNS, CDN caches.

## Consistency Models [Ch. 6 §C.2]

| Model | Guarantee | Cost | Use cases |
|---|---|---|---|
| **Strong consistency** | After write, all reads from any node return updated value | Highest latency; lowest availability (blocks until all replicas confirm) | Financial systems, distributed locks |
| **Weak consistency** | After write, reads may or may not see updated value; no guarantee on when | Lowest latency | VoIP, live video (stale state is meaningless) |
| **Eventual consistency** | Given no new updates, all replicas converge eventually | High availability; low latency; temporary stale reads | DNS propagation, Cassandra, DynamoDB, social feed rankings |
| **Read-your-writes** | After a client writes, that same client always reads the updated value (others may not) | Session-scoped | User profile updates, comment posting |
| **Monotonic reads** | A client that saw value V will never see an older value (no going backwards) | Session-scoped | Prevents "ghost" updates appearing and disappearing |

## Quorum Consensus (정족수 합의) [Ch. 6 §C.3]

Parameters: **N** = total replicas, **W** = write quorum (replicas that must ACK a write), **R** = read quorum (replicas that must respond to a read).

**Key rule:** If **W + R > N**, reads will always see at least one node with the latest write.

| Configuration | Write speed | Read speed | Use case |
|---|---|---|---|
| W=1, R=N | Fast | Slow | Write-heavy workloads |
| W=N, R=1 | Slow | Fast | Read-heavy workloads |
| W=N/2+1, R=N/2+1 | Balanced | Balanced | Strong consistency (common default) |

**Example with N=3:**
- W=2, R=2 → W+R=4 > N=3 → strong consistency
- W=1, R=1 → W+R=2 < N=3 → eventual consistency (fastest, weakest)

**Source:** alex-xu-vol1 §C.1–C.2 (Ch. 6)
