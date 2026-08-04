import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

test("CI checks the actual PR or push diff instead of an empty worktree", async () => {
  const workflow = await fs.readFile(
    path.join(repositoryRoot, ".github", "workflows", "ci.yml"),
    "utf8",
  );

  assert.match(workflow, /fetch-depth:\s*0/u);
  assert.match(workflow, /git diff --check "\$PR_BASE_SHA\.\.\.\$GITHUB_SHA"/u);
  assert.match(workflow, /git diff --check "\$PUSH_BEFORE_SHA\.\.\.\$PUSH_SHA"/u);
  assert.match(workflow, /git show --check --format= "\$GITHUB_SHA"/u);
  assert.doesNotMatch(workflow, /run:\s*git diff --check\s*(?:\r?\n|$)/u);
});

test("Release accepts only stable semantic-version tags and validates repository versions", async () => {
  const workflow = await fs.readFile(
    path.join(repositoryRoot, ".github", "workflows", "release.yml"),
    "utf8",
  );
  const bundle = await fs.readFile(
    path.join(repositoryRoot, "tools", "build-release-bundle.mjs"),
    "utf8",
  );

  assert.match(workflow, /\^v\[0-9\]\+\\\.\[0-9\]\+\\\.\[0-9\]\+\$/u);
  assert.match(workflow, /node tools\/check-release-version\.mjs "\$tag"/u);
  assert.doesNotMatch(workflow, /v\[0-9\]\*\.\[0-9\]\*\.\[0-9\]\*/u);
  assert.match(bundle, /\/\^v\\d\+\\\.\\d\+\\\.\\d\+\$\/u/u);
  assert.doesNotMatch(bundle, /\(\?:\[-\+\]/u);
});
