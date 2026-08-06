import { readdir } from "node:fs/promises";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const testRoots = [
  path.join(repositoryRoot, "tools"),
  path.join(repositoryRoot, "backend", "tools"),
];

async function listTests(directory) {
  const tests = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const absolutePath = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      tests.push(...await listTests(absolutePath));
    } else if (entry.name.endsWith(".test.mjs")) {
      tests.push(absolutePath);
    }
  }
  return tests;
}

const testFiles = (await Promise.all(testRoots.map(listTests))).flat().sort();
if (testFiles.length === 0) {
  throw new Error("No repository tool tests were found.");
}

const result = spawnSync(process.execPath, ["--test", ...testFiles], {
  cwd: repositoryRoot,
  stdio: "inherit",
});
if (result.error) {
  throw result.error;
}
process.exitCode = result.status ?? 1;
