import assert from "node:assert/strict";
import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);

async function listPowerShellFiles(directory) {
  const files = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const absolutePath = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      files.push(...await listPowerShellFiles(absolutePath));
    } else if (entry.name.endsWith(".ps1")) {
      files.push(absolutePath);
    }
  }
  return files;
}

test("keeps every Catalog product-create verification call bound to an idempotency key", async () => {
  const scripts = await listPowerShellFiles(path.join(repositoryRoot, "backend"));
  const createPath = /["'][^"'\r\n]*\/admin\/products["']/gu;
  const calls = [];

  for (const script of scripts) {
    const source = await readFile(script, "utf8");
    for (const match of source.matchAll(createPath)) {
      const start = Math.max(0, match.index - 700);
      const end = Math.min(source.length, match.index + match[0].length + 700);
      calls.push({
        script,
        window: source.slice(start, end),
      });
    }
  }

  assert.ok(calls.length >= 4, "expected the maintained Catalog create verifiers");
  for (const call of calls) {
    assert.match(
      call.window,
      /Idempotency-Key/u,
      `${path.relative(repositoryRoot, call.script)} creates a Catalog product without an Idempotency-Key`,
    );
  }
});
