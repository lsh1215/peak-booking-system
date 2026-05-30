# Database Selection Guide

## SQL vs NoSQL Decision Table [Ch. 1 §B.11]

| Dimension | SQL (Relational) | NoSQL |
|---|---|---|
| Schema | Strict, predefined | Flexible / schema-free |
| Transactions | ACID (Atomicity, Consistency, Isolation, Durability) | BASE (most); some support ACID per-document |
| Joins | Native, efficient | No native joins (denormalize instead) |
| Horizontal scaling | Hard (sharding is complex) | Built-in horizontal scale-out |
| Query model | Complex SQL, reporting, aggregations | Simple access patterns (KV lookup, range scans) |
| Consistency | Strong by default | Tunable; often eventual |
| Examples | PostgreSQL, MySQL, Oracle | Redis, DynamoDB, MongoDB, Cassandra, Neo4j |

## Choose SQL When

- Data model is well-understood upfront and relationships are complex.
- Strict consistency and ACID transactions are required (e.g., financial ledgers, inventory).
- Complex queries, reporting, or multi-table joins are needed.
- The team is small and operational simplicity matters.

## Choose NoSQL When

- Super-low latency is required (O(1) key lookups).
- Data is unstructured or semi-structured (JSON, blobs).
- Massive horizontal scale-out is needed (billions of rows, high write throughput).
- Access patterns are simple — no complex joins required.
- The data model may evolve rapidly (schema-free flexibility).

## NoSQL Sub-Types

| Sub-type | Examples | Data model | Best use cases |
|---|---|---|---|
| **Key-Value store** | Redis, DynamoDB (simple mode) | Hash map: key → opaque value | Sessions, caching, shopping carts, leaderboards; O(1) lookups |
| **Document store** | MongoDB, CouchDB | JSON-like documents, nested structure | Content management, catalogs, user profiles, flexible schemas |
| **Column-family store** | Cassandra, HBase | Wide rows; columns grouped into families | Time-series data, write-heavy analytics, event logs, IoT |
| **Graph database** | Neo4j, Amazon Neptune | Nodes + edges with properties | Social graphs, fraud detection, recommendation engines, knowledge graphs |

## Common Patterns in System Design Problems

| Problem | Recommended DB | Reason |
|---|---|---|
| User sessions / caching | Redis (KV) | Sub-millisecond reads, TTL support |
| News feed post storage | Cassandra or MySQL | High write throughput or strong relational integrity |
| Chat message history | HBase or Cassandra | Time-range queries, high write throughput |
| Friendship graph | Neo4j or adjacency list in RDBMS | Relationship traversal |
| URL shortener mappings | MySQL or DynamoDB | Simple KV lookup; ACID for dedup |
| Trie / autocomplete | MongoDB or custom KV | Document or serialized structure |
| Financial transactions | PostgreSQL | ACID, complex queries |

**Source:** alex-xu-vol1 §B.11 (Ch. 1)
