# Rate Limiting Algorithms

## Placement [Ch. 4 §C.5]

| Location | Reliability | Use case |
|---|---|---|
| **Client-side** | Unreliable — can be forged | Don't rely on this alone |
| **Server-side** (application code) | Reliable | Simple single-server deployments |
| **API Gateway / Middleware** | Recommended for microservices | Centralized enforcement; language-agnostic |

**HTTP response:** Return **HTTP 429 Too Many Requests** when limit exceeded.

## Storage Backend

**Redis** (in-memory cache) is the preferred backend:
- Fast (sub-millisecond reads/writes).
- TTL-based automatic expiration for time windows.
- **Atomic operations** via Lua scripts or Redis transactions — prevents race conditions in distributed deployments.

**Trap:** Naive in-process counters don't work in distributed setting without a shared store.
**Trap:** Two requests reading the same counter simultaneously without atomic ops → race condition → double-counting.

## The Five Algorithms [Ch. 4 §C.5]

### 1. Token Bucket (토큰 버킷)

**Mechanism:** A bucket holds up to `bucket_size` tokens. Tokens added at `refill_rate` tokens/second. Each request consumes 1 token. Empty bucket → 429.

| Parameter | Description |
|---|---|
| `bucket_size` | Max burst capacity |
| `refill_rate` | Tokens added per second |

| Pros | Cons |
|---|---|
| Memory efficient (2 variables) | Two parameters to tune |
| Allows controlled bursts (up to `bucket_size`) | Burst may be undesirable for some use cases |
| Widely adopted (Amazon, Stripe) | |

### 2. Leaky Bucket (누출 버킷)

**Mechanism:** Fixed-capacity FIFO queue. Incoming requests enqueued if space exists; otherwise dropped. Requests dequeued and processed at fixed `outflow_rate`.

| Parameter | Description |
|---|---|
| `bucket_size` | Queue capacity |
| `outflow_rate` | Requests processed per second |

| Pros | Cons |
|---|---|
| Guarantees stable output rate | Burst requests fill queue; newer requests starved |
| Memory efficient (bounded queue) | Old requests may block newer ones |
| Suitable for constant-rate processing | |

### 3. Fixed Window Counter (고정 윈도 카운터)

**Mechanism:** Time divided into fixed windows (e.g., each minute). Counter per window. Exceeds threshold → 429.

| Parameter | Description |
|---|---|
| `window_size` | Duration of each window (e.g., 60 seconds) |
| `request_limit` | Max requests per window |

| Pros | Cons |
|---|---|
| Memory efficient (1 counter) | **Boundary burst:** client can send 2× limit in a short span (end of window N + start of window N+1) |
| Easy to implement | |

### 4. Sliding Window Log (이동 윈도 로그)

**Mechanism:** Log of request timestamps stored (e.g., Redis sorted set). On each request, remove old timestamps outside the window, count remaining, then decide.

| Parameter | Description |
|---|---|
| `window_size` | Rolling time window |
| `request_limit` | Max requests in the window |

| Pros | Cons |
|---|---|
| Very accurate — no boundary burst | Memory-intensive: stores all timestamps including rejected requests |
| | Not suitable for very high traffic |

### 5. Sliding Window Counter (이동 윈도 카운터) — Hybrid

**Mechanism:** Interpolates between current and previous fixed-window counts:
```
estimate = current_window_count + prev_window_count × (1 - elapsed_fraction_of_current_window)
```

| Pros | Cons |
|---|---|
| Memory efficient (2 counters only) | Slightly approximate (assumes uniform distribution within windows) |
| Significantly reduces boundary burst vs. pure fixed window | Not perfectly exact |
| Works well in practice for most use cases | |

## Algorithm Comparison

| Algorithm | Memory | Burst-friendly | Accuracy | Complexity |
|---|---|---|---|---|
| Token Bucket | Low | Yes | High | Medium |
| Leaky Bucket | Low | No (smoothed) | High | Medium |
| Fixed Window Counter | Lowest | Yes (boundary issue) | Medium | Low |
| Sliding Window Log | High | No | Highest | Medium |
| Sliding Window Counter | Low | Partial | High | Medium |

## Rate Limiting HTTP Headers

| Header | Meaning |
|---|---|
| `X-RateLimit-Limit` | Maximum allowed requests per window |
| `X-RateLimit-Remaining` | Remaining requests in current window |
| `X-RateLimit-Retry-After` | Seconds until rate limit resets (included with 429 responses) |

**Source:** alex-xu-vol1 §C.5 (Ch. 4)
