import assert from "node:assert/strict";
import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

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

test("verification scripts do not depend on workstation-specific drive paths", async () => {
  const roots = [
    path.join(repositoryRoot, "backend"),
    path.join(repositoryRoot, "deploy", "docker"),
  ];
  const violations = [];

  for (const root of roots) {
    for (const file of await listPowerShellFiles(root)) {
      const source = await readFile(file, "utf8");
      if (/(?:^|[\s'"(])(?:[A-Za-z]):[\\/]/mu.test(source)) {
        violations.push(path.relative(repositoryRoot, file).replaceAll("\\", "/"));
      }
    }
  }

  assert.deepEqual(
    violations,
    [],
    `Verification scripts must resolve tools from the repository or PATH:\n${violations.join("\n")}`,
  );
});

test("the repository owns its host verification preflight", async () => {
  const preflight = path.join(
    repositoryRoot,
    "backend",
    "tools",
    "check-verification-host.ps1",
  );
  const source = await readFile(preflight, "utf8");

  assert.match(source, /Host memory headroom/u);
  assert.match(source, /dynamic port headroom/u);
  assert.match(source, /4231, 4266/u);
  assert.match(source, /RequiredContainers = @\(\)/u);
  assert.doesNotMatch(source, /Clash|VPN|以太网|192\.168\./u);
});
