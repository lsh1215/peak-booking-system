# 안정 해시 설계 (Consistent Hashing)

**Book chapter:** Alex Xu Vol.1 Ch.5 (pp.77–89)
**Slug:** consistent-hashing
**Scale class:** large (most acute at >10 servers)
**Type:** Building-block reference

## Canned numbers (book's starting assumptions — Clarifying starting point)

Not a product problem — pure infrastructure concept.
Hash ring: SHA-1 address space = 2^160 positions [p.79].
Virtual nodes: v=100–200 per server typical [pp.84–86].

## Functional requirements (book's defaults)

- Minimize key remapping when servers are added or removed
- Distribute keys uniformly across servers
- Support adding/removing servers without full rehash [p.77]

## Non-functional requirements (book's defaults)

- Uniform key distribution
- Minimal data movement on topology change
- Scalability (handles N → N+1 or N-1 cleanly)

## Core decisions

| Decision | Problem | Solution |
|---|---|---|
| Naive `k mod N` | Majority of keys remapped on add/remove [p.78] | Hash ring |
| Basic ring (1 position/server) | Non-uniform distribution + domino effect on removal [p.83] | Virtual nodes |
| Virtual node count | More = better uniformity; more = more memory [pp.84–86] | v=100–200 typical |
| Key-to-server lookup | Linear scan | Clockwise traversal to first server on ring [p.80] |

## Key components

- Hash ring (e.g., SHA-1 mod 2^32 or 2^160)
- Hash function (consistent, uniform)
- Server nodes + virtual node mapping (server → multiple ring positions)
- Key-to-server lookup (sorted structure for clockwise traversal)

## Common traps

- One position per server → skewed distribution, hot spots [p.83]
- Server removal hotspot: next clockwise server absorbs all departed server's keys
- Forgetting that more virtual nodes = more memory
- Assuming uniform distribution without virtual nodes

## Deep-dive topics

- Virtual nodes uniformity proof [pp.84–86]
- Real-world usage: DynamoDB, Cassandra, memcached [p.88]
- Rebalancing during topology change [p.86]

## Key design dimensions

Data partitioning, Load balancing, Handling server failures/rebalancing

## Cross-references in sysdesign-frameworks

- `building-blocks.md` (data partitioning section — consistent hashing is the standard answer)
- Used as a sub-decision in: key-value-store, url-shortener (DB sharding), search-autocomplete (trie sharding)

**Source:** alex-xu-vol2-problems §consistent-hashing, Alex Xu Vol.1 Ch.5 (pp.77–89)
