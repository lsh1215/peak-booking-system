# 유튜브 설계 (Design YouTube)

**Book chapter:** Alex Xu Vol.1 Ch.14 (pp.247–275)
**Slug:** youtube
**Scale class:** large (2B users/month, 5M uploads/day)
**Type:** Mock-ready

## Canned numbers (book's starting assumptions — Clarifying starting point)

- 5M DAU (daily active users) [p.249]
- 5M video uploads/day [p.249]
- 10% upload / 90% watch traffic split
- Average video size: 300MB → storage: 150TB/day [p.250]
- CDN cost: ~$150K/day at $0.02/GB [p.250]
- 79% of traffic is non-US [p.247]

## Functional requirements (book's defaults)

- Fast video upload, smooth streaming
- Web, mobile, Smart TV support [p.248]
- Comments, likes, subscriptions [p.248]
- Recommendations [p.248]

## Non-functional requirements (book's defaults)

- 5M uploads/day; 10/90 upload/watch split
- 300MB/video avg → 150TB/day storage
- $150K/day CDN cost at current scale
- Global audience (79% non-US)

## Core decisions

| Decision | Options | Winner / Notes |
|---|---|---|
| Upload flow | Synchronous to origin vs async pipeline | Client → LB → API → raw storage + metadata DB; transcoding pipeline async [p.251] |
| Streaming origin | Serve from API/origin vs CDN | **CDN — not origin** [pp.251–252] |
| Upload mechanism | Full upload then transcode vs parallel + pre-signed | **Pre-signed URL**: client uploads directly to cloud storage, bypasses API [pp.268–269] |
| Transcoding | Single format vs multi-format × multi-resolution | Multiple formats × resolutions (360p/480p/720p/1080p/4K) for adaptive streaming [pp.257–259] |
| Transcoding architecture | Single server vs DAG pipeline | **DAG for transcoding pipeline**: split → parallel task graph → merge [pp.259–263] |
| Adaptive bitrate | Fixed vs protocol-based | MPEG-DASH, HLS, MS Smooth Streaming, Adobe HDS [p.257] |
| Parallel upload | Single stream vs segmented | Split into GOP segments, upload concurrently [pp.267–268] |
| CDN cost optimization | One tier vs two tier | Popular videos at edge PoP; unpopular from origin or cheaper CDN [pp.270–271] |
| Safety | None vs DRM + encryption | DRM, AES encryption, watermarking [pp.269–270] |

**DAG transcoding pipeline stages:**
Preprocessor → DAG scheduler → Resource manager → Workers (encoder, thumbnailer, watermarker) → Temp storage → Completion handler

**Pre-signed URL flow:**
API server issues pre-signed S3 URL → Client uploads raw video directly to S3 → S3 event triggers transcoding pipeline (no API bandwidth bottleneck)

## Key components

- API servers (stateless — metadata, auth, upload coordination)
- Raw video storage (S3)
- Transcoding workers (DAG: video splitter, encoder, thumbnailer, watermarker)
- Transcoded storage (BLOB + CDN distribution)
- CDN (geo-routing to nearest PoP)
- Metadata DB + metadata cache
- Message queue (upload complete → transcode trigger)
- Resource manager (worker scheduling)
- Completion handler (notify + update metadata)

## Common traps

- Streaming from origin — can't handle global traffic at this scale [p.252]
- Single transcoding server — SPOF, no parallelism [p.259]
- Transcoding only after full upload — wastes time; pipeline raw upload and transcode [p.267]
- No pre-signed URLs — API becomes bandwidth bottleneck for large file uploads [p.268]
- Synchronous transcoding — users wait; must be async [p.254]
- Single CDN tier for all content — wastes cost on cold/infrequent content [p.271]
- Forgetting error classification: recoverable (retry) vs non-recoverable (fail fast) [pp.271–272]

## Deep-dive topics

- DAG transcoding pipeline design [pp.259–265]
- Adaptive bitrate protocols (MPEG-DASH, HLS) [pp.256–257]
- Pre-signed URL for direct-to-S3 upload [pp.268–269]
- CDN geo-routing and tiered cost optimization [pp.270–271]
- Recoverable vs non-recoverable error handling [pp.271–272]
- Live streaming extensions [p.273]

## Key design dimensions

Async upload pipeline, Transcoding (DAG, parallel, multi-format), CDN strategy, Metadata storage scalability, Reliability

## Cross-references in sysdesign-frameworks

- `building-blocks.md` (message queue for upload→transcode; CDN section; blob storage)
- `scalability-patterns.md` (pre-signed URL pattern; async pipeline; CDN tiering)

**Source:** alex-xu-vol2-problems §youtube, Alex Xu Vol.1 Ch.14 (pp.247–275)
