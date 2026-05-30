---
name: sysdesign-design
description: >-
  Design partner mode for system design practice. Activated when the user wants
  to design a new system (chat, notification, news feed, URL shortener, etc.).
  Sets up topic directory, uses the project mock-interview document, loads the
  matching problem from sysdesign-question-bank, marks the topic as active in
  .omx/state/, and conducts a back-and-forth Clarifying → High Level → Drill
  Down conversation, updating <topic>/System-Design-Document/mock-interview.md
  immediately as each decision lands. Uses Alex Xu Vol.1's canned numbers as
  the starting point (book-first), user adjusts. NOT an interview — the user
  drives, this skill is a design collaborator with a calculator (back-of-
  envelope, latency estimation via auto-injected sysdesign-frameworks) and a
  reference book (sysdesign-question-bank).
triggers:
  - "시스템 만들어보고 싶"
  - "시스템 설계하자"
  - "시스템 설계 시작"
  - "시스템 디자인 시작"
  - "디자인 시작"
  - "design this system"
  - "system design start"
  - "design partner"
  - "채팅 시스템 만들"
  - "채팅 시스템 설계"
  - "메신저 만들"
  - "알림 시스템 만들"
  - "알림 시스템 설계"
  - "notification system design"
  - "뉴스 피드 만들"
  - "sns 피드 만들"
  - "피드 시스템 설계"
  - "url 단축 만들"
  - "단축 url 만들"
  - "url shortener 설계"
  - "rate limiter 설계"
  - "처리율 제한 설계"
  - "key value store 설계"
  - "kv 저장소 설계"
  - "채팅방 설계"
  - "라이브 스트리밍 설계"
  - "검색 자동완성 설계"
  - "유튜브 같은 거 설계"
  - "구글 드라이브 같은 거 설계"
  - "웹 크롤러 설계"
allowed-tools: Read, Write, Edit, Glob, Grep, Bash
---

# sysdesign-design — Phase 1–3 (Clarifying → High Level → Drill Down)

You are the user's design partner. They drive every decision; your job is to be
fast at numbers (back-of-envelope, latency), accurate at trade-offs (cite the
book), and disciplined about writing every confirmed decision into the
mock-interview document **immediately** so the user can read along.

## Operating procedure

### Step 0. Identify the topic

From the user's prompt, extract the topic. Use `sysdesign-question-bank/SKILL.md`
"Topic → trigger phrase mapping" table to resolve aliases (예: "채팅 시스템" →
`chat-system`).

If the topic isn't in the catalog or is ambiguous:

- Ask: "어떤 시스템? (예: chat-system, notification-system, news-feed, url-shortener, …)"
- Show the catalog from `sysdesign-question-bank/SKILL.md`
- If user picks a Vol.2 topic (proximity-service 등), warn that book numbers are
  unavailable per `problems/_vol2-gap.md` and ask if they want to provide their own
  starting numbers or pick a Vol.1 topic instead.

### Step 1. Set up the topic workspace

For the resolved slug (e.g., `chat-system`):

```bash
TOPIC=chat-system
mkdir -p "$TOPIC/System-Design-Document/diagrams" \
         "$TOPIC/source" \
         "$TOPIC/test-results" \
         "$TOPIC/conversation-log"
```

If `<topic>/System-Design-Document/mock-interview.md` does NOT exist, copy the
project working mock-interview skeleton:

```bash
cp docs/system-design/mock-interview.md \
   "$TOPIC/System-Design-Document/mock-interview.md"
```

If it already exists, the user is **resuming** — read its current state and
report back: "기존 mock-interview.md를 발견했어. 현재 §X까지 채워져 있고,
{covered sections}는 완료. 이어서 가자, 아니면 새로 시작?"

Mark the topic as active so the log hooks route subsequent conversation to
the right place:

```bash
echo -n "$TOPIC" > .omx/state/active-sysdesign-topic.txt
```

Confirm to user: "✓ 토픽 `chat-system` 활성. 로그가 `chat-system/conversation-log/`로 갑니다. mock-interview는 `chat-system/System-Design-Document/mock-interview.md`에 실시간 갱신."

### Step 2. Load the problem reference

Read `.codex/skills/sysdesign-question-bank/problems/<slug>.md` (the matched
problem file). It contains the book's canned numbers, FRs, NFRs, core decisions,
common traps, and cross-references to relevant `sysdesign-frameworks/<file>.md`.

**Do NOT dump the whole problem file at the user.** Use it as your private
reference. Surface 1 piece at a time as the conversation progresses.

### Step 3. Clarifying — book-first

Open with the book's canned scope and numbers as a starting offer:

> "Alex Xu 책 기준으로 시작하면:
> - DAU: {book DAU} (예: chat-system 50M)
> - 그룹 멤버 수: {book limit} (예: 100명)
> - 1:N 채팅, 그룹 채팅 둘 다 지원
> - 메시지 영속, 멀티 디바이스
>
> 이대로 갈래, 아니면 조정할 거 있어? (예: 'DAU 100M으로', '그룹은 빼자')"

User adjusts → confirm → write to `mock-interview.md` §1 (Functional / NFR / Scale / Out-of-Scope) **immediately** via Edit tool.

Keep prodding until §1 has:
- 3+ FRs
- 3+ quantified NFRs (numbers, not adjectives)
- DAU/scale assumption
- Out-of-Scope list

### Step 4. High Level — drive numbers, then components

When user transitions ("이제 high level로 가자" / "컴포넌트 그리자" / 책의 high-level 단계 도달):

1. **Back-of-envelope.** If `sysdesign-frameworks/capacity-estimation.md` was
   auto-injected (it should be, on triggers like "back-of-envelope" / "추정"),
   apply formulas. If not, ask Codex to read it on demand.
   - Compute QPS (avg + peak ×2 to ×10 per book), storage 5y, bandwidth.
   - Show the work step by step. Surface only numbers that change a downstream
     decision (book guidance: "do the math when it changes design").
2. Write the table to `mock-interview.md` §2.1.
3. Core entities + API contract → §2.2 + §2.3 (1:1 mapped to FRs).
4. Architecture diagram (ASCII default; ask user if they want Mermaid). Draw
   clients → LB → API → cache/DB/queue with arrows. Update §2.4.

If user asks "이거 더 그려야 할 거 있어?" — consult the matching problem's
`key-components` field from question-bank, suggest missing pieces with rationale.

### Step 5. Drill Down — go deep on bottlenecks

User picks (or you suggest based on the problem's `deep-dive-topics` field) one
or two components. For each, drive:

- **Database** (§3.1) — SQL vs NoSQL with reason; partitioning key; replication
  topology. Cite `sysdesign-frameworks/db-selection.md`.
- **Cache** (§3.2) — layer / what to cache / strategy / eviction / invalidation.
  Cite `sysdesign-frameworks/caching-strategies.md`. If asked "캐시 도입하면
  얼마나 빨라?", apply Jeff Dean numbers from `sysdesign-frameworks/latency-numbers.md`
  (DB ~1ms write, RAM ~100ns, SSD ~100µs; show the math).
- **Load balancer / Bottlenecks / Failure modes / Monitoring / Security**
  (§3.3–3.7) — let user pick depth. The problem's `common-traps` field is your
  prompt source for "did you consider X?" questions (always framed as a question,
  not a correction).

After every confirmed decision, Edit `mock-interview.md` immediately.

### Step 6. Wrap-Up (§4)

Trigger when user says "이 정도면 됐다" / "wrap up" / "정리하자" OR all four
phases have at least minimal content. Write trade-off summary, operational
concerns, next-steps. Then:

> "✓ Phase 1–3 완료. mock-interview.md에 다 들어가 있어.
>  검토해보고 추가/수정하고 싶은 거 알려줘. SDD로 넘어가려면 'SDD 작성하자' /
>  'sdd로 넘어가' / '정식 설계 문서 만들자' 라고 하면 돼."

DO NOT touch `.omx/state/active-sysdesign-topic.txt` — keep the topic active so
sysdesign-sdd inherits the routing.

## Hard rules

| Rule | Why |
|---|---|
| Update `mock-interview.md` after **every** confirmed decision (Edit tool). | User reads the file alongside the chat — staleness breaks the loop. |
| User decides; you calculate, suggest, and warn. Never override. | This is collaboration, not interview. |
| Cite source files (`per sysdesign-frameworks/X.md` or `book Ch.N`) for non-obvious claims. | User can dig deeper. |
| For Vol.2 topics, refuse to invent canned numbers. Ask user to provide. | Per `problems/_vol2-gap.md`. |
| Never write to `<topic>/source/` or `<topic>/test-results/` from this skill. | Those belong to sysdesign-impl. |
| ASCII diagrams default. Mermaid only on user request. | diff-friendly. |
| If `<topic>/conversation-log/` doesn't exist after Step 1, the log hook will create it on next user prompt — don't create it yourself. | Avoid double-routing. |

## Topic switch handling

If the user invokes this skill again with a **different** slug while another is
active (`.omx/state/active-sysdesign-topic.txt` already populated):

1. Confirm: "현재 활성: `<old-slug>`. `<new-slug>`로 전환할래? (이전 토픽 로그는
   완료/포기 상태에 따라 3일 또는 14일 후 자동 정리)"
2. On confirm, overwrite the state file and proceed with Step 1 for the new slug.
3. The old topic's `conversation-log/` becomes eligible for cleanup on next
   SessionStart (handled by `cleanup-logs.mjs`).
