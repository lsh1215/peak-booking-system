#!/usr/bin/env node

// Stop hook: append the latest assistant message from the transcript
// to docs/ai/conversation-log.md.

import {
  readFileSync,
  mkdirSync,
  appendFileSync,
  existsSync,
  statSync,
  rmdirSync,
} from "fs";
import { join } from "path";

function getMainProjectDir(dir) {
  const m = dir.match(/^(.+)\/\.(?:claude|codex)\/worktrees\/[^/]+/);
  return m ? m[1] : dir;
}

function readStdin() {
  try {
    return JSON.parse(readFileSync("/dev/stdin", "utf8"));
  } catch {
    return {};
  }
}

function ensureMarkdownFile(filePath, title) {
  if (existsSync(filePath)) return;
  appendFileSync(filePath, `# ${title}\n\n## Log\n`);
}

function sleep(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

function withFileLock(lockDir, fn) {
  const timeoutMs = 3000;
  const staleMs = 10000;
  const started = Date.now();

  while (true) {
    try {
      mkdirSync(lockDir);
      try {
        return fn();
      } finally {
        try {
          rmdirSync(lockDir);
        } catch {
          // ignore cleanup failure
        }
      }
    } catch (error) {
      if (error?.code !== "EEXIST") throw error;

      try {
        const age = Date.now() - statSync(lockDir).mtimeMs;
        if (age > staleMs) {
          rmdirSync(lockDir);
          continue;
        }
      } catch {
        // lock disappeared between checks
      }

      if (Date.now() - started > timeoutMs) {
        return fn();
      }
      sleep(25);
    }
  }
}

function pad(n) {
  return String(n).padStart(2, "0");
}

function localDateAndTime(d = new Date()) {
  const date = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  const time = `${date} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
  return { date, time };
}

// Walk the transcript JSONL backwards and pull the latest assistant turn's text.
// Transcript format varies slightly across versions; we accept either
// `{ role, content }` or `{ message: { role, content } }` and tolerate
// `content` being a string OR a list of `{ type, text }` blocks.
function getLatestAssistantText(transcriptPath) {
  if (!transcriptPath || !existsSync(transcriptPath)) return null;

  let content;
  try {
    content = readFileSync(transcriptPath, "utf8");
  } catch {
    return null;
  }

  const lines = content.split("\n").filter(Boolean);

  for (let i = lines.length - 1; i >= 0; i--) {
    let msg;
    try {
      msg = JSON.parse(lines[i]);
    } catch {
      continue;
    }

    const role = msg.role || msg.message?.role;
    if (role !== "assistant") continue;

    const body = msg.content ?? msg.message?.content;
    if (!body) continue;

    if (typeof body === "string") return body;
    if (Array.isArray(body)) {
      const text = body
        .filter((c) => c && c.type === "text" && typeof c.text === "string")
        .map((c) => c.text)
        .join("\n")
        .trim();
      if (text) return text;
    }
  }

  return null;
}

function main() {
  try {
    if (process.env.CODEX_AI_LOGGING_ENABLED !== "1") {
      process.stdout.write(JSON.stringify({ continue: true }));
      return;
    }

    const input = readStdin();
    const transcriptPath = input.transcript_path;
    const sessionId = (input.session_id || input.sessionId || "unknown").slice(0, 8);
    const projectDir = getMainProjectDir(
      process.env.CLAUDE_PROJECT_DIR || input.cwd || process.cwd()
    );

    const text = getLatestAssistantText(transcriptPath);
    if (!text) {
      process.stdout.write(JSON.stringify({ continue: true }));
      return;
    }

    const aiDir = join(projectDir, "docs", "ai");
    mkdirSync(aiDir, { recursive: true });

    const { date, time } = localDateAndTime();
    const conversationLog = join(aiDir, "conversation-log.md");

    withFileLock(join(aiDir, ".ai-log.lock"), () => {
      ensureMarkdownFile(conversationLog, "Conversation Log");
      appendFileSync(conversationLog, `\n## ${time} [${sessionId}] ASSISTANT\n\n${text}\n\n---\n`);
    });
  } catch {
    // never block on logging failure
  }

  process.stdout.write(JSON.stringify({ continue: true }));
}

main();
