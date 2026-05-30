# AGENTS.md

This file is the project contract for AI coding agents. It is intentionally more operational than `README.md`: use it to decide what to read, what to protect, what to test, and how to split work.

## Instruction Scope

- This root `AGENTS.md` applies to the whole repository.
- If a nested `AGENTS.md` or `AGENTS.override.md` is added later, follow the nearest applicable file for files under that subtree.
- Direct user instructions in the current conversation override this file.
- Keep this file compact. Put long design rationale in `docs/`, not here.

## Project Mission

Build a limited-stock booking/payment backend for a midnight flash-sale scenario.

Core requirements:

- Stock is fixed at `10` units for the target product.
- Traffic can burst from normal `50 TPS` to `500~1000 TPS` for `1~5` minutes after `00:00`.
- The system assumes `2+` stateless application replicas.
- The system must preserve strict booking correctness under duplicate clicks, retries, payment failures, Redis failure, app instance failure, and DB pressure.

## Baseline Stack

- Java 21
- Spring Boot 3.5.x
- MySQL 8 as final source of truth
- Redis for cache/admission control, not final correctness
- JUnit 5, Spring Boot Test, Testcontainers for verification
- Load-test tool is still undecided

Do not introduce production dependencies casually. If adding a dependency, record the reason and rejected alternatives in `docs/decisions/DECISIONS.md`.

## Non-Negotiable Invariants

- Confirmed bookings must never exceed stock.
- One user must not create multiple confirmed bookings for the same limited product.
- Same `Idempotency-Key` plus same request body must return the same logical result.
- Same `Idempotency-Key` plus different request body must be rejected.
- Payment failure must not create a confirmed booking.
- Redis failure must not cause oversell.
- Retry behavior must be bounded and must not amplify a degraded dependency.
- Business correctness must not depend on JVM-local locks, local memory, local sessions, or a single application instance.

## Read First

For architecture or design work:

1. `docs/requirements.md`
2. `docs/decisions/DECISIONS.md`
3. `docs/research/source-backed-research-note.md`
4. `docs/testing/test-first-scenarios.md`
5. `docs/reviews/adversarial-review.md`
6. `docs/system-design/mock-interview.md` or `docs/system-design/sdd.md`

For backend implementation work:

1. `AGENTS.md`
2. nearest files of the same type once source exists
3. `docs/requirements.md`
4. `docs/testing/test-first-scenarios.md`
5. relevant `.codex/skills/*/SKILL.md`

## Working Modes

- Requirements/design: update `docs/system-design/`, `docs/decisions/`, and `docs/research/`.
- Backend implementation: use the Spring/domain/layer skills under `.codex/skills/`.
- Test-first work: use `.codex/skills/test-first-prompting/SKILL.md`.
- Adversarial review: use `.codex/skills/adversarial-review/SKILL.md`.
- First-principles design review: use `.codex/skills/first-principles-design-review/SKILL.md`.
- Spring bootstrap: use `.codex/skills/spring-bootstrap/SKILL.md` before hand-writing shared kernel classes.

## Parallel Session Rules

Multiple Codex sessions may run at the same time.

- Start each task by checking `git status --short`.
- Avoid editing the same document from two sessions unless the user explicitly coordinates it.
- Treat `docs/ai/conversation-log.md` and `docs/ai/prompt-log.md` as auto-managed raw logs. Do not hand-edit them unless the task is explicitly log cleanup.
- The logging hooks serialize append operations with `docs/ai/.ai-log.lock`, but semantic ordering can still interleave across sessions. Use timestamps and session ids when reviewing logs.
- If another session changed a file, preserve its work and adapt.

## Command Contract

The backend has not been generated yet. Until then, do not invent successful build/test commands.

Planned commands after Spring Boot bootstrap:

```bash
docker compose up -d mysql redis
./gradlew test
./gradlew bootRun
```

When code exists:

- Run the narrowest relevant test first.
- Run broader tests before claiming implementation is complete.
- If a command cannot run because the project is not bootstrapped yet, say so clearly.

## Backend Design Rules

- Prefer a modular monolith first. Do not split bounded contexts into microservices unless a decision record accepts the extra operational cost.
- Keep application servers stateless.
- Use MySQL constraints/transactions as final correctness guard.
- Use Redis only for cache/admission/coordination paths whose failure behavior is explicitly documented.
- Separate Checkout read-cache fallback from Booking write-path admission fallback.
- Booking write-path Redis failure must be either fail-closed or bounded DB fallback; never unlimited DB fallback.
- Put `@Transactional` on application service boundaries, not controllers.
- Do not catch generic exceptions in business code just to return fallback values. Let the global exception layer translate expected failures.
- Keep domain rules testable without infrastructure when possible.
- Record cross-cutting decisions in `docs/decisions/DECISIONS.md`.

## Testing Rules

- Start from invariants and failure modes before happy-path implementation.
- Every booking write-path feature needs a duplicate/concurrency test.
- Every fallback feature needs a degraded dependency test.
- Every retry feature needs a retry cap or retry storm test.
- Use Testcontainers for MySQL/Redis integration behavior once the backend exists.
- Update `docs/testing/test-first-scenarios.md` when adding or closing a scenario.

## Documentation Rules

- Keep `README.md` human-facing and concise.
- Keep `AGENTS.md` agent-facing and operational.
- Keep long rationale in `docs/`.
- Keep source-backed technical claims in `docs/research/source-backed-research-note.md`.
- Keep decisions in `docs/decisions/DECISIONS.md` with alternatives and rejection reasons.
- Keep AI disclosure in `docs/ai/AI_USAGE.md`.

## Git Rules

- Work on topic branches; this project currently uses the `codex/` branch prefix.
- Keep commits small and thematic.
- Do not stage auto-generated local runtime state.
- Do not revert user or other-session changes without explicit instruction.
- Before committing, check `git status --short` and verify only intended files are staged.

## Security And Privacy

- Do not commit secrets, API keys, local credentials, real user data, or private requirement originals.
- Use `.env.example` for documented configuration names only.
- Public documents must avoid organization-identifying context unless the user explicitly approves it.

## Definition Of Done

A change is done only when:

- the relevant document or code path is updated,
- the related decision/test/research note is updated when applicable,
- relevant checks were run or the reason they could not run is stated,
- `git status --short` has been reviewed,
- the final response names the changed files and residual risks.

## Reference Basis

This file follows the AGENTS.md convention described by OpenAI Codex docs and the public AGENTS.md format: repository instructions are separate from README, scoped by directory, and should contain concrete setup, test, style, and safety guidance for coding agents.
