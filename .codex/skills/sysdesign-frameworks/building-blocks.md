# Building Blocks Reference

## Scaling Strategies [Ch. 1 §B.1]

| Type | Definition | When to use | Trap |
|---|---|---|---|
| **Vertical scaling** (scale up) | Upgrade single server: more CPU/RAM/disk | Early-stage; bursty; simplicity matters | Hard ceiling; exponential cost; single point of failure |
| **Horizontal scaling** (scale out) | Add more servers of equal capacity | At scale; fault tolerance needed | Requires stateless design + load balancer |

Key principle: start vertical for speed; migrate to horizontal as demand grows.

## Load Balancer [Ch. 1 §B.2]

**Definition:** Distributes incoming traffic across multiple servers. Users connect to LB's public IP; servers use private IPs.

**Algorithms:**

| Algorithm | How it works | Best for | Trap |
|---|---|---|---|
| Round Robin | Sequential cycling | Homogeneous servers, short requests | Ignores server capacity differences |
| Weighted Round Robin | More traffic to heavier servers | Mixed server specs | Requires manual weight tuning |
| Least Connections | Route to server with fewest active connections | Long-lived or uneven requests | Overhead of tracking connection counts |
| IP Hash (Sticky Sessions) | Hash client IP → same server always | Stateful apps | Breaks horizontal scaling benefits; uneven distribution |

**Benefits:** Zero-downtime scaling, blue-green deployments, automatic failover.

## Database Replication [Ch. 1 §B.3]

| Mode | Write nodes | Read nodes | Use case | Risk |
|---|---|---|---|---|
| **Master-Slave** | 1 master | N slaves | Read-heavy (1:10–1:50 ratio) | Slave lag; data loss on master crash |
| **Multi-master** | N masters | — | Multi-region active-active | Conflict resolution complexity |
| **Circular** | Ring of N | — | Some MySQL multi-master setups | Entire ring affected by single failure |

**Mitigation for slave lag:** Semi-synchronous replication — master waits for at least 1 slave ACK before confirming write.

## Database Sharding [Ch. 1 §B.4]

**Definition:** Distribute one DB across multiple shards, each storing a subset of data.

| Strategy | Mechanism | Pros | Cons |
|---|---|---|---|
| **Hash-based** | `shard_id = hash(key) % N` | Even distribution | Full reshard when N changes |
| **Range-based** | Value ranges (IDs 0–999K → shard 0) | Simple; efficient range queries | Hotspots if data is skewed |
| **Geographic** | Route by user location | Low latency for geo clusters | Hotspot if one region dominates |
| **Directory-based** | Lookup table maps key → shard | Most flexible | Lookup service = bottleneck / SPOF |

**Challenges:** Resharding (use consistent hashing), celebrity/hotspot (dedicate shards), cross-shard joins (denormalize).

## Caching Layers [Ch. 1 §B.5]

(Full detail in `caching-strategies.md`; summary here.)

**Definition:** Temporary fast-memory store (Redis, Memcached) to avoid repeated DB/compute calls.

**When to cache:** Frequently read, infrequently modified data.
**Do NOT cache:** Real-time data (stock prices, live scores), one-time tokens, large binaries (use CDN).

| Pattern | Who fetches on miss | Consistency | Common use |
|---|---|---|---|
| Cache-aside (lazy) | Application | Eventual | Most common general pattern |
| Write-through | Cache + DB simultaneously | Strong | Write-heavy + consistency critical |
| Write-around | Write to DB only; cache on read | Eventual | Write-once, read-rarely |
| Read-through | Cache fetches from DB | Strong | Simplifies app code |

**Eviction:** LRU (default), LFU (stable hot items), FIFO (simple, ignores patterns).
**Trap:** Cache stampede on cold start — use lease tokens or probabilistic early expiration.

## CDN [Ch. 1 §B.6]

**Definition:** Geographically distributed edge servers that cache static content nearest to users.

| Aspect | Detail |
|---|---|
| What to cache | Images, video, CSS, JS — static and semi-static |
| URL pattern | `cdn.example.com/logo.png` |
| Cache invalidation | Versioned URLs (`logo.png?v=2`) or TTL expiry |
| Cost model | Charged per GB transferred; cache only high-access assets |

**Latency example:** USA-origin → Pakistan user: ~200–300 ms without CDN; ~20–30 ms with CDN edge node (~10× faster).
**Netflix example:** ~1B hours/day streamed via Open Connect CDN inside ISP networks; avg start time <1 second globally.

## Stateless Web Tier [Ch. 1 §B.7]

**Problem:** Session stored in server memory → sticky sessions required → breaks horizontal scaling.
**Solution:** Web servers store NO user data. Session state lives in external shared store (Redis).

| Aspect | Detail |
|---|---|
| Session store | Redis with auto-expiration (e.g., 30 min) |
| Load balancing | Round Robin works — any server handles any request |
| Benefit | Zero-downtime deploys; immediate scale-out utility; session survives server crash |

**Principle:** "Stateless doesn't mean no state — it means state isn't tied to specific servers."

## Multi-Region / GeoDNS [Ch. 1 §B.8]

| Mode | Description | Use case |
|---|---|---|
| **Active-active** | Both datacenters serve traffic | Maximum availability |
| **Active-passive** | One primary + one standby | Simpler failover |

**GeoDNS:** Routes user to nearest datacenter by IP geolocation. On failure, redirects all traffic to surviving DC.
**Key challenges:** Data sync across regions (async → eventual consistency), DNS TTL propagation, independent load testing per region.

## Message Queue [Ch. 1 §B.9]

**Definition:** Durable in-memory buffer for async communication. Producers write; consumers read independently.

| Aspect | Detail |
|---|---|
| Key benefit | Decoupling: producers/consumers scale and fail independently |
| Scaling | Scale producers and consumers independently based on load |
| Durability | Messages survive consumer crashes (remain in queue) |
| Use cases | Image/video processing pipelines, email dispatch, event streaming, background jobs |

| System | Extra capability |
|---|---|
| RabbitMQ | Standard MQ |
| Apache Kafka | Durable log + replay; event sourcing; audit trails |
| Amazon SQS | Managed, at-least-once delivery |
| Google Pub/Sub | Managed, global fan-out |

**Trap:** Without a queue, a provider outage cascades to the producer (tight coupling).

## Logging, Metrics, Automation [Ch. 1 §B.10]

**Centralized logging:** ELK Stack, Splunk, CloudWatch Logs — enables cross-server search.

**Metrics to monitor:**

| Layer | Key metrics |
|---|---|
| Host | CPU (alert >80%), memory (alert >90%), disk I/O, network throughput |
| System | Requests/sec, p99/p50 response time, error rate, cache hit ratio |
| Business | DAU, conversion rate, revenue/hour |

**Automation:** Manual deploys to 20 servers ≈ 3 hours + high error risk. CI/CD pipeline ≈ 15 min + 0.1% error rate.
Deploy to staging first; auto-rollback on metric degradation.

**Source:** alex-xu-vol1 §B.1–B.10 (Ch. 1)
