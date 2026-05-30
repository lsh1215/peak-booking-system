---
name: adr
description: Generate an Architecture Decision Record for a technical decision
argument-hint: "<decision topic>"
disable-model-invocation: true
allowed-tools: Read, Glob, Grep, Write, Bash
---

You are generating an Architecture Decision Record (ADR) for this decision topic: **$ARGUMENTS**

---

## Step 1: Auto-Increment ADR Number

1. List all files in `docs/adr/` matching the pattern `ADR-*.md`
2. Extract the numeric prefix from each filename (e.g., `ADR-001-kafka-over-rabbitmq.md` -> `001`)
3. Find the highest number. The new ADR number = highest + 1, zero-padded to 3 digits
4. If `docs/adr/` does not exist or contains no ADR files, the new ADR number is `001`

Example:
- Existing: `ADR-001-...`, `ADR-002-...`, `ADR-003-...`
- New number: `004`

---

## Step 2: Interview the User

Before writing the ADR, ask the user these three questions. Wait for answers before proceeding.

**Questions to ask** (present all three at once):

1. **Problem context**: What specific problem or situation prompted this decision? What constraints or requirements drove the need to choose?

2. **Alternatives considered**: What other options were evaluated besides "$ARGUMENTS"? For each alternative, what were the key trade-offs?

3. **Decision rationale**: Why was this option chosen over the alternatives? What were the deciding factors (performance, team familiarity, cost, operational complexity, alignment with existing stack)?

---

## Step 3: Generate Slug

Create a URL-safe slug from the decision topic `$ARGUMENTS`:
- Lowercase all letters
- Replace spaces and special characters with hyphens
- Remove consecutive hyphens
- Trim leading/trailing hyphens

Example: "Kafka over RabbitMQ for async messaging" -> `kafka-over-rabbitmq-for-async-messaging`

---

## Step 4: Generate ADR Document

Using the answers from the interview, fill in this template completely. Do not leave any section blank.

```markdown
# ADR-{NNN}: {Title from $ARGUMENTS}

## Status

Proposed

## Date

{today's date in YYYY-MM-DD format}

## Context

{Describe the problem, the forces at play, and the constraints that make this decision necessary.
Include relevant technical context from the project: Java 21, Spring Boot 3.x, PostgreSQL, Kafka (KRaft), ElasticSearch + nori, current project phase.
2-4 sentences minimum.}

## Decision

{State the decision made clearly and directly.
Start with: "We will use..." or "We have decided to..."
1-3 sentences.}

## Alternatives Considered

| Option | Pros | Cons | Reason Not Chosen |
|--------|------|------|-------------------|
| {Option A — the chosen option} | {list pros} | {list cons} | Chosen |
| {Option B} | {list pros} | {list cons} | {why rejected} |
| {Option C, if applicable} | {list pros} | {list cons} | {why rejected} |

## Consequences

### Positive
- {consequence 1}
- {consequence 2}
- {consequence 3}

### Negative / Trade-offs
- {trade-off 1}
- {trade-off 2}

### Neutral
- {neutral impact 1, e.g., "Team will need to learn X"}

## Implementation Notes

| Aspect | Detail |
|--------|--------|
| Affected components | {list services or layers impacted} |
| Migration required | Yes / No — {brief description if yes} |
| Related skills | {e.g., `.omc/skills/event-driven.md` for Kafka conventions} |
| Related ADRs | {ADR-NNN if supersedes or relates to another decision, else "None"} |

## Metrics

How will we know this decision was correct? Define measurable success criteria:

| Metric | Target | Measurement Method |
|--------|--------|--------------------|
| {metric 1, e.g., "Message delivery latency"} | {target, e.g., "< 100ms p99"} | {how, e.g., "Prometheus histogram"} |
| {metric 2} | {target} | {how} |
| {metric 3, e.g., "Operational incidents caused by this technology"} | {target, e.g., "0 per month after 3-month stabilization"} | {how, e.g., "PagerDuty alert count"} |

## Review Date

{date 3 months from today — format YYYY-MM-DD}
Set a reminder to revisit this decision and update status to Accepted or Deprecated based on metrics.
```

---

## Step 5: Save the ADR

1. Create the `docs/adr/` directory if it does not exist
2. Save the completed ADR to:

```
docs/adr/ADR-{NNN}-{slug}.md
```

Where `{NNN}` is the auto-incremented number from Step 1 and `{slug}` is from Step 3.

3. After saving, print:
   - The absolute file path
   - The ADR number and title
   - Confirmation: "ADR-{NNN} saved. Update its status from 'Proposed' to 'Accepted' once the decision is ratified by the team."

---

## ADR Status Lifecycle Reference

| Status | Meaning |
|--------|---------|
| Proposed | Decision identified but not yet ratified |
| Accepted | Team has agreed; decision is in effect |
| Deprecated | Decision is no longer valid; superseded by a newer ADR |
| Superseded | Replaced — link to the new ADR number |

To update an ADR status later, edit the `## Status` section of the saved file and add a note with the date and reason.
