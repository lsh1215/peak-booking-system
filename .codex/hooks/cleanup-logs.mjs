#!/usr/bin/env node

// SessionStart hook: clean up stale legacy conversation logs.
//
// Project AI disclosure logs under docs/ai/*.md are intentionally not cleaned:
// they are audit artifacts for prompt/conversation transparency.
// Three retention tiers:
//   1. Catchall logs (.claude/logs/*.log)        → 3 days
//   2. Per-topic logs where topic is "shipped"   → 3 days
//      (shipped = <topic>/source/ contains files)
//   3. Per-topic logs where topic is "abandoned" → 14 days
//      (abandoned = inactive AND source/ is empty/missing)
//
// The currently-active topic (from .omx/state/active-sysdesign-topic.txt)
// is always preserved regardless of mtime.
//
// Empty conversation-log/ dirs are removed after their last .log is deleted.
// Runs silently on failure — never blocks session start.

import {
  readFileSync,
  readdirSync,
  statSync,
  unlinkSync,
  existsSync,
  rmdirSync,
} from "fs";
import { join } from "path";

const CATCHALL_RETENTION_DAYS = 3;
const SHIPPED_RETENTION_DAYS = 3;
const ABANDONED_RETENTION_DAYS = 14;

function readStdin() {
  try {
    return JSON.parse(readFileSync("/dev/stdin", "utf8"));
  } catch {
    return {};
  }
}

// If invoked from inside an OMC worktree, redirect to the main root so cleanup
// operates on the canonical topic dirs and catchall logs.
function getMainProjectDir(dir) {
  const m = dir.match(/^(.+)\/\.claude\/worktrees\/[^/]+/);
  return m ? m[1] : dir;
}

function readActiveTopic(projectDir) {
  for (const stateDir of [".omx", ".omc"]) {
    try {
      const slug = readFileSync(
        join(projectDir, stateDir, "state", "active-sysdesign-topic.txt"),
        "utf8"
      ).trim();
      if (!slug || slug.includes("/") || slug.includes("\\") || slug.startsWith(".")) continue;
      return slug;
    } catch {
      // try the next state directory
    }
  }
  return null;
}

function dirHasAnyFile(dir) {
  if (!existsSync(dir)) return false;
  try {
    const entries = readdirSync(dir, { withFileTypes: true });
    for (const e of entries) {
      if (e.isFile()) return true;
      if (e.isDirectory() && dirHasAnyFile(join(dir, e.name))) return true;
    }
  } catch {
    return false;
  }
  return false;
}

function cleanupLogDir(logDir, cutoffMs) {
  // Returns true if directory exists and was processed; deletes empty dir at end.
  if (!existsSync(logDir)) return;
  try {
    const entries = readdirSync(logDir);
    let remaining = 0;
    for (const file of entries) {
      const fullPath = join(logDir, file);
      if (!file.endsWith(".log")) {
        // preserve non-.log files (e.g., .keep, README.md) and count them
        remaining += 1;
        continue;
      }
      try {
        const st = statSync(fullPath);
        if (st.mtimeMs < cutoffMs) {
          unlinkSync(fullPath);
        } else {
          remaining += 1;
        }
      } catch {
        // skip unreadable entries
        remaining += 1;
      }
    }
    if (remaining === 0) {
      try {
        rmdirSync(logDir);
      } catch {
        // ignore — non-empty or missing
      }
    }
  } catch {
    // ignore
  }
}

function findTopicDirs(projectRoot) {
  // Returns slugs whose <projectRoot>/<slug>/conversation-log/ exists.
  const found = [];
  try {
    const entries = readdirSync(projectRoot, { withFileTypes: true });
    for (const e of entries) {
      if (!e.isDirectory()) continue;
      if (e.name.startsWith(".") || e.name === "node_modules") continue;
      const convDir = join(projectRoot, e.name, "conversation-log");
      if (existsSync(convDir)) {
        found.push(e.name);
      }
    }
  } catch {
    // ignore
  }
  return found;
}

function main() {
  try {
    const input = readStdin();
    const projectDir = getMainProjectDir(
      process.env.CLAUDE_PROJECT_DIR || input.cwd || process.cwd()
    );
    const now = Date.now();

    // Tier 1: catchall logs
    cleanupLogDir(
      join(projectDir, ".claude", "logs"),
      now - CATCHALL_RETENTION_DAYS * 24 * 60 * 60 * 1000
    );

    // Tier 2/3: per-topic logs
    const activeTopic = readActiveTopic(projectDir);
    const topics = findTopicDirs(projectDir);

    for (const slug of topics) {
      if (slug === activeTopic) continue; // never clean active topic

      const topicDir = join(projectDir, slug);
      const logDir = join(topicDir, "conversation-log");
      const sourceDir = join(topicDir, "source");
      const isShipped = dirHasAnyFile(sourceDir);

      const retentionDays = isShipped ? SHIPPED_RETENTION_DAYS : ABANDONED_RETENTION_DAYS;
      const cutoff = now - retentionDays * 24 * 60 * 60 * 1000;

      cleanupLogDir(logDir, cutoff);
    }
  } catch {
    // never block on cleanup failure
  }

  process.stdout.write(JSON.stringify({ continue: true }));
}

main();
