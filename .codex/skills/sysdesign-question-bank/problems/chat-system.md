# 채팅 시스템 설계 (Chat System)

**Book chapter:** Alex Xu Vol.1 Ch.12 (pp.197–221)
**Slug:** chat-system
**Scale class:** large (50M DAU; WhatsApp/WeChat/Messenger/Discord-class)
**Type:** Mock-ready

## Canned numbers (book's starting assumptions — Clarifying starting point)

- 50M DAU [p.198]
- Average 40 messages/day per user → ~23K messages/sec [p.198]
- Message size: few KB
- Group chat cap: 100 members [p.198]
- WeChat's real group cap for fan-out: 500 [p.214]

## Functional requirements (book's defaults)

- 1-on-1 chat
- Group chat (up to 100 members) [p.198]
- Online presence indicators
- Push notification for offline users
- Multi-device support [p.199]
- Message history persistence [p.199]

## Non-functional requirements (book's defaults)

- 50M DAU [p.198]
- Low latency message delivery
- Consistent message order across devices [p.199]
- High availability

## Core decisions

| Decision | Options | Winner / Notes |
|---|---|---|
| Connection protocol | HTTP polling vs long polling vs WebSocket | **WebSocket** — bidirectional + persistent [pp.200–203] |
| WebSocket direction | Receive only vs both directions | Both send and receive over WebSocket [p.202] |
| Chat server state | Stateless vs stateful | Stateful (connection-pinned); ZooKeeper for service discovery [pp.204–205] |
| Message storage | Relational DB vs KV store | KV store (HBase, Cassandra) — high write throughput, time-range queries [p.207] |
| Message ID | Global Snowflake vs per-channel local sequence | **Per-channel local sequence** — Snowflake doesn't preserve channel order [pp.208–210] |
| Group fan-out | No cap vs cap | Cap at 500 members for fan-out (WeChat's limit) [p.214] |
| Presence updates | Synchronous O(friends) vs pub/sub | Pub/sub per user — avoid O(friends) synchronous writes [pp.215–218] |

**1-on-1 message flow:**
Sender → Chat server A → Message sync queue → Receiver's chat server B → WebSocket → Receiver

**Group message flow:**
Sender → Chat server → Copy message to each member's sync queue (inbox fan-out)

**Presence mechanism:**
- Client sends heartbeat every X seconds to presence server
- Presence server tracks `last_active` timestamp
- Status changes published via pub/sub channels to subscribed friends

## Key components

- Chat servers (WebSocket, stateful — one connection per user)
- Service discovery (ZooKeeper — maps user to chat server)
- Message sync queue (per-user inbox — pending messages for delivery)
- Message storage (HBase or Cassandra — high write, time-range query)
- Presence servers (heartbeat tracking + pub/sub)
- Pub/sub channels (presence status fan-out)
- Push notification service (offline users — see Ch.10 / notification-system.md)
- API servers (stateless — login, user mgmt, group mgmt)
- Load balancer

## Common traps

- HTTP polling — high latency, server waste [p.200]
- Long polling: sender and receiver may connect to different servers → routing complexity [p.201]
- Relational DB for messages — write throughput too high; no joins needed [p.207]
- Global Snowflake for message ID — doesn't preserve per-channel order [p.209]
- Fan-out for 500+ member groups — extremely expensive (WeChat's 500 cap exists for this) [p.214]
- Synchronous presence updates O(friends) → use pub/sub [p.218]
- Forgetting multi-device: each device needs `cur_max_message_id` for sync [p.213]

## Deep-dive topics

- WebSocket handshake and persistent connection management [pp.202–203]
- Per-channel message ID (local sequence counter) [pp.208–210]
- Sync queue for 1-on-1 and group messaging [pp.211–215]
- Presence: heartbeat + pub/sub fan-out [pp.215–219]
- Multi-device `cur_max_message_id` sync [p.213]

## Key design dimensions

Real-time protocol (WebSocket vs polling), Message ordering / unique ID, Stateful server management, Storage engine for high-write, Online presence

## Cross-references in sysdesign-frameworks

- `building-blocks.md` (message queue for sync queues; KV store for message storage; WebSocket real-time section)
- `scalability-patterns.md` (stateful server + ZooKeeper service discovery; pub/sub for presence)
- Sub-components from: notification-system.md (offline push), unique-id-generator.md (per-channel ID concept)

**Source:** alex-xu-vol2-problems §chat-system, Alex Xu Vol.1 Ch.12 (pp.197–221)
