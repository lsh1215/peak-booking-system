---
name: e2e-testing
description: E2E/QA testing with agent-browser CLI for token-efficient browser automation
triggers:
  - "e2e test"
  - "e2e 테스트"
  - "browser test"
  - "브라우저 테스트"
  - "qa test"
  - "qa 테스트"
  - "페이지 테스트"
  - "화면 테스트"
  - "agent-browser"
  - "end to end"
---

## E2E Testing Skill — agent-browser CLI

Browser automation for QA/E2E testing using Vercel's `agent-browser` CLI.
Uses accessibility tree refs (`@e1`, `@e2`) instead of CSS selectors — 5.7x more token-efficient than Playwright MCP.

### Prerequisites

```bash
npm install -g agent-browser
agent-browser install  # installs Chromium
```

### Core Workflow

**1. Open & Snapshot (understand the page)**
```bash
agent-browser open <URL> && agent-browser wait --load networkidle && agent-browser snapshot -i
```
- `snapshot -i` returns interactive elements only (buttons, inputs, links) with refs like `@e1`, `@e2`
- Read the accessibility tree to understand the page structure

**2. Interact (fill forms, click buttons)**
```bash
agent-browser fill @e3 "test@example.com"
agent-browser fill @e4 "password123"
agent-browser click @e5                    # Submit button
agent-browser wait --load networkidle
```

**3. Assert (verify results)**
```bash
agent-browser snapshot -i                  # Check new page state
agent-browser get text @e10                # Get specific element text
agent-browser get url                      # Check current URL
agent-browser screenshot result.png        # Visual evidence
```

**4. Chain Commands (single bash call)**
```bash
agent-browser open localhost:3000 && \
  agent-browser wait --load networkidle && \
  agent-browser snapshot -i
```

### Command Reference

| Command | Description |
|---------|-------------|
| `open <url>` | Navigate to URL |
| `snapshot` | Full accessibility tree with refs |
| `snapshot -i` | Interactive elements only (less tokens) |
| `click @ref` | Click element by ref |
| `fill @ref "text"` | Clear and fill input |
| `type @ref "text"` | Type into element (append) |
| `press Enter` | Press keyboard key |
| `select @ref "value"` | Select dropdown option |
| `check @ref` / `uncheck @ref` | Checkbox toggle |
| `scroll down 500` | Scroll direction + pixels |
| `wait @ref` | Wait for element to appear |
| `wait 2000` | Wait milliseconds |
| `wait --load networkidle` | Wait for network idle |
| `get text @ref` | Get element text content |
| `get url` | Get current page URL |
| `get value @ref` | Get input value |
| `screenshot [path]` | Take screenshot |
| `screenshot --full` | Full page screenshot |
| `screenshot --annotate` | Labeled screenshot for AI review |
| `eval "js code"` | Execute JavaScript |
| `close` | Close browser |

### Selector Types

```bash
@e1                          # Ref from snapshot (preferred)
"button:has-text('Submit')"  # CSS + text selector
"#email"                     # CSS ID selector
".btn-primary"               # CSS class selector
```

### Testing Patterns

**Auth Flow Test:**
```bash
agent-browser open localhost:3000/auth && agent-browser wait --load networkidle && agent-browser snapshot -i
# Read refs → find email input (@e3), password input (@e4), login button (@e5)
agent-browser fill @e3 "test@example.com" && agent-browser fill @e4 "password123" && agent-browser click @e5
agent-browser wait --load networkidle && agent-browser get url
# Assert: URL should be / (home page) after successful login
```

**Product Listing Test:**
```bash
agent-browser open localhost:3000/products && agent-browser wait --load networkidle
agent-browser snapshot -i
# Assert: product cards visible, filter sidebar present
agent-browser screenshot products-page.png
```

**Checkout Flow Test:**
```bash
# 1. Add to cart
agent-browser open localhost:3000/products/some-id && agent-browser wait --load networkidle
agent-browser snapshot -i
# Select size, click Add to Cart
agent-browser click @e8  # size button
agent-browser click @e12 # add to cart

# 2. Go to cart
agent-browser open localhost:3000/cart && agent-browser wait --load networkidle
agent-browser snapshot -i

# 3. Checkout
agent-browser click @e5  # proceed to checkout
agent-browser wait --load networkidle
agent-browser snapshot -i
# Fill shipping form, submit
```

### Test Report Pattern

After each test, create a structured result:

```
## Test: [Name]
- URL: [tested URL]
- Status: PASS / FAIL
- Steps:
  1. [action] → [expected] → [actual]
  2. ...
- Screenshot: [path if captured]
- Errors: [any errors encountered]
```

### Learned Patterns

**`snapshot -i` vs full `snapshot`:**
- `snapshot -i` shows only interactive elements (buttons, links, inputs) — great for form filling and clicking
- Full `snapshot` shows ALL content including text, headings, paragraphs — use when verifying page content, brand descriptions, product details
- When `snapshot -i` shows the same elements after navigation, the URL may have changed but text content differs — use full `snapshot` or `get url` to verify

**Zustand/localStorage verification:**
```bash
agent-browser eval "localStorage.getItem('auth-storage')"
agent-browser eval "JSON.parse(localStorage.getItem('key')).state.field"
```

**SPA hydration awareness:**
- After `agent-browser open` (full page load), zustand persist stores need ~100-500ms to rehydrate from localStorage
- Add `agent-browser wait 1000` or `agent-browser wait 2000` after navigation before checking auth-dependent state
- Client-side navigation (clicking links) preserves React state; `open` command does full page load

**Form submission debugging:**
- If button click doesn't navigate, check: (1) form validation errors via `snapshot` for error messages, (2) auth state, (3) console errors via `eval`
- Use `agent-browser eval "document.querySelector('[role=alert]')?.textContent"` to find error alerts

### Token Efficiency Rules

1. **Always use `snapshot -i`** (interactive only) instead of full `snapshot` — 60-80% less tokens
2. **Chain commands** with `&&` in a single bash call — one tool call instead of many
3. **Don't screenshot unless needed** — text assertions via `get text` are cheaper
4. **Close browser when done** — `agent-browser close`
5. **Use refs (`@e1`)** — never re-snapshot just to get CSS selectors
6. **Use `eval` for state checks** — cheaper than re-snapshotting to verify JS state

### Integration with Project

Typical test URLs for a 4-service e-commerce platform:
- Frontend: `http://localhost:3000`
- Product API: `http://localhost:8081`
- Order API: `http://localhost:8082`
- Payment API: `http://localhost:8083`
- Customer API: `http://localhost:8084`

Start services before testing:
```bash
# Terminal 1: Backend (from repo root)
cd backend-v2 && ./gradlew bootRun

# Terminal 2: Frontend
cd frontend && npm run dev

# Terminal 3: Tests (or use agent-browser directly)
agent-browser open http://localhost:3000
```

### Headless Mode (CI)

```bash
agent-browser --headless open <url> && agent-browser snapshot -i
```

### Persistent Sessions

```bash
# Save login state
agent-browser --session-name ecommerce open localhost:3000/auth
# Login manually or via commands
# Next time, session is restored:
agent-browser --session-name ecommerce open localhost:3000
```
