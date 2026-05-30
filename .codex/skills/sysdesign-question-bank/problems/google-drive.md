# 구글 드라이브 설계 (Design Google Drive)

**Book chapter:** Alex Xu Vol.1 Ch.15 (pp.277–299)
**Slug:** google-drive
**Scale class:** large (50M DAU, 10M uploaders/day)
**Type:** Mock-ready

## Canned numbers (book's starting assumptions — Clarifying starting point)

- 50M DAU [p.279]
- 10M file uploads/day [p.279]
- Average storage per user: 10GB → total: 500PB [p.279]
- Upload QPS: ~240 avg, ~480 peak [p.280]
- Max single file size: 10GB [p.280]
- Eventual consistency for sync is acceptable; metadata must be strongly consistent

## Functional requirements (book's defaults)

- Upload/download files from any device
- Sync file changes across devices automatically
- Share files with others [p.278]
- Revision history
- All file types supported [p.278]
- Mobile photo auto-backup [p.278]

## Non-functional requirements (book's defaults)

- 50M DAU, 10M uploads/day
- 10GB/user avg → 500PB total storage
- 240 QPS uploads (480 peak)
- No data loss
- Fast sync with low bandwidth usage
- Eventual consistency for sync acceptable; strong consistency for metadata

## Core decisions

| Decision | Options | Winner / Notes |
|---|---|---|
| Upload API | Simple vs resumable | Resumable for large files; simple for small [pp.280–281] |
| Upload chunking | Full file vs 4MB blocks | **4MB blocks; only changed blocks on update (delta sync)** [pp.285–286] |
| Block dedup | No dedup vs content-addressed | Content-hash as block key → block-level dedup [p.285] |
| Metadata DB | NoSQL vs relational | **Relational (ACID)**: namespace / file / file_version / block / device tables [pp.289–291] |
| Sync conflicts | Last-write-wins vs present both | Last-write-wins OR present both versions [pp.285–286] |
| Notification protocol | WebSocket vs long polling | **Long polling (NOT WebSocket)** — file changes infrequent, no bidirectional needed [p.295] |
| Cold storage | All hot vs tiered | Rarely-accessed files → S3 Glacier [p.295] |
| Revisions | Full copies vs block deltas | Store as block sets; old blocks in cold storage [p.295] |
| Durability | Single region vs multi-region | S3 multi-region for durability [p.283] |
| Consistency | Eventual vs strong for metadata | Strong consistency for metadata DB; cache + DB sync [pp.290–291] |

**Delta sync logic:**
On file save → compute block hashes → compare with server → upload only changed blocks → server assembles new version from existing + new blocks

**Long polling rationale:**
File sync events are infrequent (not continuous stream). Long polling is simpler and sufficient. WebSocket would be overkill and add unnecessary complexity for this access pattern.

## Key components

- Block storage servers (chunked, content-hash deduped)
- Metadata DB (relational, ACID — namespace, file, file_version, block, device)
- Metadata cache
- Sync service (coordinates delta computation and block distribution)
- Notification service (long polling — notifies clients of file changes)
- Offline backup queue (local queue; syncs on reconnect)
- API servers (stateless)
- Cold storage (S3 Glacier — old revisions, infrequently accessed)
- CDN (hot files)
- Message queue (async sync tasks)

## Common traps

- Full file upload on every change → bandwidth waste; need delta sync [pp.285–286]
- Single file storage server — SPOF; need S3 multi-region [p.283]
- WebSocket for sync notifications — overkill; long polling is enough [p.295]
- Storing full file versions → wasteful; store block deltas [p.285]
- Keeping everything in hot storage — cold content must be tiered to S3 Glacier [p.295]
- No conflict resolution policy → silent inconsistency visible to users [p.286]
- No block dedup → same content uploaded multiple times across users [p.285]
- Eventual consistency for metadata → stale version info during concurrent edits

## Deep-dive topics

- Block chunking + delta sync [pp.285–286]
- Content-addressed block deduplication [p.285]
- Relational metadata schema design [pp.289–291]
- ACID consistency for metadata [pp.290–291]
- Sync conflict resolution [p.286]
- Storage tiering (hot → warm → cold) [p.295]
- Long polling notification mechanism [p.295]
- Resumable upload protocol [pp.280–281]

## Key design dimensions

File chunking + delta sync, Deduplication + content-addressed storage, Metadata schema, Strong consistency (ACID), Notification mechanism, Storage tiering

## Cross-references in sysdesign-frameworks

- `building-blocks.md` (blob storage — S3 multi-region; cache for metadata; message queue for async sync)
- `scalability-patterns.md` (storage tiering; long polling vs WebSocket tradeoff; delta sync pattern)

**Source:** alex-xu-vol2-problems §google-drive, Alex Xu Vol.1 Ch.15 (pp.277–299)
