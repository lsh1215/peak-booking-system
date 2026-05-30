# Capacity Estimation — Back-of-Envelope Cheat Sheet

## Powers of Two

| Power | Exact value | Approximate | Name |
|-------|-------------|-------------|------|
| 2^10 | 1,024 | ~1 thousand | 1 KB |
| 2^20 | 1,048,576 | ~1 million | 1 MB |
| 2^30 | 1,073,741,824 | ~1 billion | 1 GB |
| 2^40 | ~1.1 trillion | ~1 trillion | 1 TB |
| 2^50 | ~1.1 quadrillion | ~1 quadrillion | 1 PB |

Key: 1 byte = 8 bits. Label every number with units.

## Availability Table

| Availability | Downtime/year | Downtime/day | "Nines" |
|---|---|---|---|
| 99% | ~87.6 hours | ~14.4 minutes | Two nines |
| 99.9% | ~8.76 hours | ~86.4 seconds | Three nines |
| 99.99% | ~52.6 minutes | ~8.6 seconds | Four nines |
| 99.999% | ~5.26 minutes | ~0.86 seconds | Five nines |

Cloud SLAs (Amazon, Google, Microsoft) typically target 99.9%+.

## Useful Constants

| Constant | Value | Quick approximation |
|---|---|---|
| Seconds per day | 86,400 | ~10^5 |
| Seconds per month | ~2.6M | 86,400 × 30 |
| Seconds per year | ~31.5M | 86,400 × 365 |

## Formula Library

### QPS Estimation

```
DAU = MAU × daily_ratio                         (e.g., 300M MAU × 50% = 150M DAU)
Average QPS = DAU × actions_per_day / 86,400
Peak QPS = 2 × Average QPS                      (rule of thumb)
```

### Storage Estimation

```
Daily storage  = DAU × events_per_day × avg_object_size
Annual storage = daily_storage × 365
N-year storage = annual_storage × N
```

### Bandwidth Estimation

```
Read bandwidth  = read_QPS  × avg_response_size
Write bandwidth = write_QPS × avg_request_size
```

### Server Count Estimation

```
Servers needed = Peak QPS / requests_per_server_per_second
```

## Worked Example: Twitter Scale [Ch. 2]

**Assumptions:**
- 300M MAU; 50% daily → 150M DAU
- 2 tweets/user/day average
- 10% of tweets contain media (~1 MB each)
- Data retained for 5 years

**QPS:**
```
Tweets QPS = 150M × 2 / 86,400 ≈ 3,500
Peak QPS   = 2 × 3,500 ≈ 7,000
```

**Storage (media only):**
```
Daily media  = 150M × 2 × 10% × 1 MB = 30 TB/day
5-year total = 30 TB × 365 × 5 ≈ 55 PB
```

## How to Use During Design

1. Compute QPS and storage early to anchor architecture choices.
2. If a number changes a design decision (e.g., >10K QPS forces sharding), surface it explicitly.
3. Round aggressively — use 10^5 for 86,400; focus on order of magnitude.
4. Write assumptions down; do not keep them in your head.
5. Label every number: "30 TB/day", not "30".

**Source:** alex-xu-vol1 §A.3 (Ch. 2)
