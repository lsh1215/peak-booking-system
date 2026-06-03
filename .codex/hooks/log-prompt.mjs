#!/usr/bin/env node

// UserPromptSubmit hook: append user prompt to project AI logs.
//
// Outputs:
//   - docs/ai/prompt-log.md: user prompts only, raw
//   - docs/ai/conversation-log.md: user + assistant conversation stream

import {
  readFileSync,
  mkdirSync,
  appendFileSync,
  existsSync,
  statSync,
  rmdirSync,
} from "fs";
import { join } from "path";

function readStdin() {
  try {
    return JSON.parse(readFileSync("/dev/stdin", "utf8"));
  } catch {
    return {};
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

function getMainProjectDir(dir) {
  const m = dir.match(/^(.+)\/\.(?:claude|codex)\/worktrees\/[^/]+/);
  return m ? m[1] : dir;
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

function main() {
  try {
    if (process.env.CODEX_AI_LOGGING_ENABLED !== "1") {
      process.stdout.write(JSON.stringify({ continue: true }));
      return;
    }

    const input = readStdin();
    const prompt = (input.prompt || "").trim();
    const sessionId = (input.session_id || input.sessionId || "unknown").slice(0, 8);
    const projectDir = getMainProjectDir(
      process.env.CLAUDE_PROJECT_DIR || input.cwd || process.cwd()
    );

    if (!prompt) {
      process.stdout.write(JSON.stringify({ continue: true }));
      return;
    }

    const aiDir = join(projectDir, "docs", "ai");
    mkdirSync(aiDir, { recursive: true });

    const { date, time } = localDateAndTime();
    const promptLog = join(aiDir, "prompt-log.md");
    const conversationLog = join(aiDir, "conversation-log.md");

    withFileLock(join(aiDir, ".ai-log.lock"), () => {
      ensureMarkdownFile(promptLog, "Prompt Log");
      ensureMarkdownFile(conversationLog, "Conversation Log");
      appendFileSync(promptLog, `\n### ${time} [${sessionId}]\n\n${prompt}\n`);
      appendFileSync(conversationLog, `\n## ${time} [${sessionId}] USER\n\n${prompt}\n`);
    });
  } catch {
    // never block on logging failure
  }

  process.stdout.write(JSON.stringify({ continue: true }));
}

main();
