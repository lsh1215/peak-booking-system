---
name: test-first-prompting
description: >-
  Test-first prompting workflow for this booking system. Use when the user asks
  AI to first derive failure, race, duplicate, overload, Redis outage, payment
  failure, or retry-storm scenarios before implementation, and to pin those
  scenarios as tests before writing production code.
triggers:
  - "test-first prompting"
  - "테스트 먼저"
  - "테스트로 고정"
  - "장애 시나리오 먼저"
  - "경쟁 시나리오 먼저"
  - "중복 시나리오 먼저"
  - "failure scenario first"
  - "race scenario first"
allowed-tools: Read, Write, Edit, Glob, Grep, Bash
---

# Test-First Prompting

Use this skill to force AI-assisted development to start from failure evidence,
not implementation optimism.

## Goal

Before writing production code, derive concrete tests for:

- race conditions
- duplicate requests
- oversell
- undersell
- Redis outage
- payment failure or timeout
- app crash during ambiguous states
- retry storm and overload

## Workflow

1. Read `docs/requirements.md`.
2. Read `docs/testing/test-first-scenarios.md`.
3. Extract the invariant the user is about to implement.
4. Generate tests before production code.
5. Update `docs/testing/test-first-scenarios.md` with:
   - scenario ID
   - expected result
   - test level
   - implementation status
   - command/result once available
6. Only then implement the smallest production change needed to pass the test.

## Required Output Before Code

```markdown
## Invariant Under Test

{one sentence}

## Test Cases To Pin First

| ID | Scenario | Expected Result | Why It Matters |
|---|---|---|---|
| ... | ... | ... | ... |

## First Test To Implement

{specific test name/path and why it is first}
```

## Hard Rules

- Do not accept "works on happy path" as sufficient.
- Every booking write-path feature must have at least one duplicate or concurrency test.
- Every fallback feature must have a degraded dependency test.
- Every retry feature must have a retry cap or retry storm test.
- If the codebase has no test harness yet, create the smallest harness first.
- If a scenario cannot be automated yet, record it as manual or pending in `docs/testing/test-first-scenarios.md`.
