# 알림 시스템 설계 (Notification System)

**Book chapter:** Alex Xu Vol.1 Ch.10 (pp.165–184)
**Slug:** notification-system
**Scale class:** large (10M push, 1M SMS, 5M email per day)
**Type:** Mock-ready

## Canned numbers (book's starting assumptions — Clarifying starting point)

- Mobile push notifications: 10M/day [p.166]
- SMS: 1M/day [p.166]
- Email: 5M/day [p.166]
- Soft real-time delivery (not strict latency SLA)

## Functional requirements (book's defaults)

- Mobile push (iOS APNs, Android FCM), SMS, email [p.166]
- Soft real-time delivery
- Opt-out support per user per channel [p.167]
- Multi-device per user

## Non-functional requirements (book's defaults)

- 10M push/day, 1M SMS/day, 5M email/day [p.166]
- High availability
- Low latency (soft real-time)
- Extensible to new notification channels

## Core decisions

| Decision | Options | Winner / Notes |
|---|---|---|
| Provider integration | Direct vs abstracted | Abstracted per channel — APNs (iOS), FCM (Android), Twilio/Nexmo (SMS), SendGrid/Mailchimp (email) [pp.167–169] |
| Coupling | Tight (synchronous call) vs loose (queue) | **Message queue per channel** to decouple senders from providers [p.171] |
| Failure handling | No retry vs exponential backoff | Retry with backoff on provider failure [p.173] |
| Deduplication | None vs idempotency key | Idempotency key per notification [p.174] |
| Spam prevention | None vs rate limit | Rate limit per user [p.174] |
| Event tracking | None vs analytics | Sent/delivered/clicked events for analytics [p.174] |

**Queue topology:** One queue per channel (push queue, SMS queue, email queue). Workers pull from queues and call providers.

## Key components

- Notification servers (receive trigger events, fan out to per-channel queues)
- Per-channel queues (push / SMS / email)
- Workers (pull from queues, call third-party providers)
- Third-party providers: APNs (iOS push), FCM (Android push), Twilio/Nexmo (SMS), SendGrid/Mailchimp (email)
- Device/user DB (user → device tokens, channels, opt-out status)
- Cache (user preferences, device tokens)
- Analytics service (delivery tracking)

## Common traps

- No queue → tight coupling, provider outage cascades to all senders [p.171]
- Sending to opted-out users → legal/regulatory risk [p.167]
- Missing deduplication → duplicate notifications on network retry [p.174]
- No retry → silent drops on transient provider failure [p.173]
- Single device-token table → slow lookup at scale [p.170]
- Not separating notification type (push vs SMS vs email) into separate queues → can't scale channels independently

## Deep-dive topics

- APNs vs FCM differences [pp.167–169]
- Queue-based decoupling [p.171]
- Retry with exponential backoff + deduplication [pp.173–174]
- Notification templates + analytics [p.174]

## Key design dimensions

Queue-based decoupling, Third-party integration, Reliability (retry, dedup), Fan-out, Multi-channel routing

## Cross-references in sysdesign-frameworks

- `building-blocks.md` (message queue section — queue-per-channel pattern; cache for device token lookup)
- `scalability-patterns.md` (worker pool for per-channel consumers)
- Used as sub-component in: chat-system.md (offline push notification)

**Source:** alex-xu-vol2-problems §notification-system, Alex Xu Vol.1 Ch.10 (pp.165–184)
