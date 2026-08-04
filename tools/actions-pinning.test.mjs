import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import { describe, it } from "node:test";
import { fileURLToPath } from "node:url";

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);

describe("GitHub Actions supply-chain pinning", () => {
  it("pins every external action to an immutable commit SHA", async () => {
    const workflowRoot = path.join(repositoryRoot, ".github", "workflows");
    const workflowNames = (await fs.readdir(workflowRoot))
      .filter((name) => name.endsWith(".yml") || name.endsWith(".yaml"));
    const unpinned = [];

    for (const workflowName of workflowNames) {
      const workflow = await fs.readFile(
        path.join(workflowRoot, workflowName),
        "utf8",
      );
      for (const [index, line] of workflow.split(/\r?\n/u).entries()) {
        const match = /^\s*uses:\s*([^\s#]+)/u.exec(line);
        if (!match?.[1] || match[1].startsWith("./")) {
          continue;
        }
        const separator = match[1].lastIndexOf("@");
        const reference = separator >= 0
          ? match[1].slice(separator + 1)
          : "";
        if (!/^[0-9a-f]{40}$/u.test(reference)) {
          unpinned.push(`${workflowName}:${index + 1} ${match[1]}`);
        }
      }
    }

    assert.deepEqual(
      unpinned,
      [],
      `External actions must use full commit SHAs:\n${unpinned.join("\n")}`,
    );
  });
});
