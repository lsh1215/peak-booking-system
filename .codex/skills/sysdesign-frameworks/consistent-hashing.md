# Consistent Hashing (안정 해시)

## Why Naive Hash%N Fails [Ch. 5 §C.4]

With simple `hash(key) % N` sharding:
- Adding 1 server (N → N+1) forces remapping of ~N/(N+1) ≈ all keys.
- Removing 1 server (N → N-1) forces remapping of ~1/N ≈ all keys.
- Each resharding event requires mass data migration — expensive and disruptive.

**Example:** 4 servers → 5 servers: ~80% of all keys must be remapped.

## Hash Ring Concept [Ch. 5 §C.4]

**Mechanism:**
1. Map the hash output space to a conceptual circular ring (e.g., SHA-1 → 0 to 2^160−1).
2. Map each server to a position on the ring by hashing its name/IP.
3. Map each key to a position on the ring by hashing the key.
4. To find a key's server: traverse clockwise from the key's position until hitting a server node.

**Adding a server:** Only keys between the new server's position and its predecessor need remapping.
**Removing a server:** Only keys that were assigned to the removed server move to the next clockwise server.

**Remapping cost:** On average, only **k/N** keys remapped (k = total keys, N = number of servers).

## Basic Ring Problem

A single position per server leads to:
- **Non-uniform distribution:** By chance, servers may cover very different arc lengths → unequal load.
- **Domino effect:** On server removal, the next clockwise server receives all the removed server's load.

## Virtual Nodes (가상 노드) [Ch. 5 §C.4]

**Solution:** Assign each physical server multiple positions on the ring (virtual nodes).

| Aspect | Detail |
|---|---|
| Typical count | 100–200 virtual nodes per physical server |
| Benefit | More uniform key distribution; lower variance in key counts per server |
| Failure effect | On removal, load distributes across many servers (not just one) |
| Trade-off | More metadata to maintain (each virtual node needs a ring entry) |

**Uniformity:** As virtual node count increases, distribution variance decreases. With 200 virtual nodes, standard deviation of key count per server is typically <5%.

## Real-World Usage

| System | Usage |
|---|---|
| Amazon DynamoDB | Consistent hashing for partition key routing |
| Apache Cassandra | Token ring with virtual nodes |
| Discord | Chat message routing across chat servers |
| Akamai CDN | Cache server selection |

## Summary: When to Use

Use consistent hashing whenever:
- You need to add/remove servers without full data migration.
- Data is distributed across many nodes (cache cluster, DB shards).
- Even key distribution matters for load balancing.

**Trap:** One virtual node per server → skewed distribution. Always use 100+ virtual nodes.
**Trap:** Forgetting that more virtual nodes = more metadata memory overhead.

**Source:** alex-xu-vol1 §C.4 (Ch. 5)
