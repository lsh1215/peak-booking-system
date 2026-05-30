# System Design Interview Delivery Frameworks

## Alex Xu 4-Step Framework [Vol.1 Ch. 3]

Total interview time: ~45–60 minutes.

| Step | Name | Time budget | Goal | Common traps |
|---|---|---|---|---|
| 1 | Understand Problem & Establish Design Scope | 3–10 min | Ask clarifying questions; state assumptions in writing; define functional + non-functional requirements | Rushing to design; silent assumption-making |
| 2 | Propose High-Level Design & Get Buy-In | 10–15 min | Sketch architecture diagram; propose multiple approaches; do rough capacity check; get interviewer buy-in | Going too deep too early; skipping DB/storage discussion |
| 3 | Design Deep Dive | 10–25 min | Deep-dive on most critical components; handle edge cases; discuss trade-offs | Covering everything shallowly instead of going deep on critical parts |
| 4 | Wrap Up | 3–5 min | Identify bottlenecks; propose improvements; discuss failure modes; mention monitoring | Declaring design "done and perfect"; skipping failure mode discussion |

### Explicit Dos (Ch. 3)
- Always ask for clarification; do not assume.
- Communicate continuously — let the interviewer follow your thought process.
- Suggest multiple approaches if possible.
- Once blueprint is agreed, go deep on critical components first.
- Bounce ideas off the interviewer; treat them as a teammate.

### Explicit Don'ts (Ch. 3)
- Don't jump into a solution without clarifying requirements.
- Don't go into implementation details too early in Step 2.
- Over-engineering is a "real disease" — keep complexity proportional to requirements.
- Don't be silent — the interviewer needs to follow your reasoning.
- Don't claim your design is perfect; show awareness of its limitations.

## Hello Interview Delivery Framework [youtube-insights V1–V4]

Used across all four Hello Interview videos (Spotify, LeetCode walkthrough, Twitter, LeetCode mock). The dominant framework in current FAANG interview culture.

| Phase | Name | Time budget | What to produce |
|---|---|---|---|
| 1 | Requirements | 5–10 min | Functional requirements (feature list); Non-functional requirements (scale, latency, availability, consistency) |
| 2 | Core Entities | 2–3 min | Identify the main data objects (User, Post, Message, etc.) |
| 3 | API / Interface | 3–5 min | Key API endpoints or interface contracts (REST / WebSocket / etc.) |
| 4 | High-Level Design | 10–15 min | Architecture diagram covering end-to-end data flow; component responsibilities |
| 5 | Deep Dives | 10–20 min | Drill into the 2–3 most critical or interesting components; trade-offs; bottlenecks |

**Key interviewer behaviors (observed across all 4 videos):**
- Defer estimation until design-relevant: "Let's move — if we needed, we can calculate."
- Scope narrowing at the start: explicitly name 2–3 features in scope and 2–3 out of scope.
- Probe via product-framing questions: "Would users be okay if the leaderboard was 5 seconds stale?"
- Phase transition by nudge: "Okay, I think we have enough context — walk me through your architecture."
- Celebrity/power-user probe as standard deep-dive trigger in feed systems.

## Hello Interview Six Silent Scoring Dimensions [youtube-insights cross-synthesis]

These are the hidden dimensions interviewers evaluate (per Hello Interview Substack rubric):

| Dimension | What it tests |
|---|---|
| Dealing with Ambiguity | Can the candidate structure an open-ended problem without hand-holding? |
| Context-Driven Decision Making | Are architectural choices motivated by the stated requirements (scale, latency, consistency)? |
| Tradeoff Navigation | Does the candidate enumerate options and justify the chosen approach? |
| Collaboration and Feedback | Does the candidate treat the interviewer as a teammate; respond well to hints? |
| Practical Intuition | Does the design reflect real operational experience (monitoring, failure modes, cost)? |
| Depth Flexibility | Can the candidate go deep when probed, without losing the high-level thread? |

## Seniority Calibration Model [youtube-insights V2, V3]

| Level | Breadth | Depth | Style |
|---|---|---|---|
| **IC4 (Mid-level)** | 80% | 20% | Covers the full system breadth; goes deep on 1–2 components when prompted |
| **IC5 (Senior)** | 60% | 40% | Proactively identifies the 2–3 critical components and drives deep dives |
| **IC6+ (Staff+)** | Candidate leads 100% | Candidate leads 100% | Drives conversation; identifies bottlenecks proactively; proposes simple systems with clear scaling paths; connects architecture to product and business impact |

**Note:** Technical correctness alone does not maximize score. All four videos explicitly call out the need to connect technical decisions to product/user impact, fairness, and operational implications.

**Source:** alex-xu-vol1 §A.1 (Ch. 3) + youtube-insights cross-video synthesis (Videos 1–4)
