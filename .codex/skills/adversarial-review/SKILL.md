---
name: adversarial-review
description: >-
  Adversarial critic workflow for the limited-stock booking system. Use when the
  user asks AI to attack a design or implementation for Redis outage, oversell,
  undersell, duplicate booking/payment, retry storm, fallback collapse,
  correctness gaps, or operational blind spots.
triggers:
  - "adversarial review"
  - "critic mode"
  - "공격적으로 리뷰"
  - "비판적으로 공격"
  - "Redis 장애 공격"
  - "oversell 공격"
  - "undersell 공격"
  - "retry storm 공격"
  - "설계 공격"
allowed-tools: Read, Write, Edit, Glob, Grep, Bash
---

# Adversarial Review

Use this skill to attack the design before production assumptions harden.

## Review Scope

Always check:

- Redis outage
- oversell
- undersell
- duplicate booking
- duplicate payment
- idempotency replay mismatch
- retry storm
- DB hot row contention
- fallback path overload
- app crash after partial side effects
- missing metrics, alerts, or recovery hooks

## Workflow

1. Read `docs/requirements.md`.
2. Read `docs/decisions/DECISIONS.md`.
3. Read `docs/reviews/adversarial-review.md`.
4. If reviewing an implementation, read the relevant code and tests.
5. Produce findings ordered by severity.
6. For each finding, require one of:
   - code fix
   - test coverage
   - decision update
   - explicit accepted trade-off
7. Update `docs/reviews/adversarial-review.md` with new findings or status changes.

## Required Output

```markdown
## Verdict

{Pass / Pass with reservations / Needs changes / Unsafe}

## Findings

| ID | Severity | Attack | Finding | Required Response |
|---|---|---|---|---|
| ... | Critical/High/Medium/Low | ... | ... | ... |

## Attack Walkthrough

1. {How the failure happens}
2. {What invariant breaks}
3. {How to prove or prevent it}

## Required Next Action

{single highest-leverage next action}
```

## Severity Guide

- **Critical**: can create oversell, duplicate payment, data corruption, or false success response.
- **High**: can create undersell, outage amplification, retry storm, or unrecoverable ambiguous state.
- **Medium**: weakens operability, observability, latency, or incident recovery.
- **Low**: documentation or edge-case clarity issue.

## Hard Rules

- Be skeptical, not theatrical.
- Do not invent requirements; tie every attack back to `docs/requirements.md`.
- Do not close a finding without evidence.
- Prefer a failing test as the response to every correctness finding.
