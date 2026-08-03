import crypto from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const releaseTag = process.argv[2] ?? process.env.RELEASE_TAG;

if (!releaseTag || !/^v\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/u.test(releaseTag)) {
  throw new Error("Usage: node tools/build-release-bundle.mjs v1.0.1");
}

const verify = spawnSync(
  "git",
  ["rev-parse", "--verify", `${releaseTag}^{commit}`],
  {
    cwd: repositoryRoot,
    encoding: "utf8",
    windowsHide: true,
  },
);
if (verify.status !== 0) {
  throw new Error(`Git tag ${releaseTag} does not exist`);
}

const releaseRoot = path.join(repositoryRoot, "release");
await fs.rm(releaseRoot, { recursive: true, force: true });
await fs.mkdir(releaseRoot, { recursive: true });

const archiveName = `PlainJournal-${releaseTag}.zip`;
const archivePath = path.join(releaseRoot, archiveName);
const archive = spawnSync(
  "git",
  [
    "archive",
    "--format=zip",
    `--prefix=PlainJournal-${releaseTag}/`,
    `--output=${archivePath}`,
    releaseTag,
  ],
  {
    cwd: repositoryRoot,
    encoding: "utf8",
    windowsHide: true,
  },
);
if (archive.status !== 0) {
  throw new Error(archive.stderr || "git archive failed");
}

const digest = crypto
  .createHash("sha256")
  .update(await fs.readFile(archivePath))
  .digest("hex");
await fs.writeFile(
  path.join(releaseRoot, `${archiveName}.sha256`),
  `${digest}  ${archiveName}\n`,
  "ascii",
);

const manifest = {
  releaseTag,
  commit: verify.stdout.trim(),
  archive: archiveName,
  sha256: digest,
  verificationBaseline: ".github/verification-baseline.json",
  generatedAt: new Date().toISOString(),
};
await fs.writeFile(
  path.join(releaseRoot, `PlainJournal-${releaseTag}.manifest.json`),
  `${JSON.stringify(manifest, null, 2)}\n`,
  "utf8",
);

console.log(`Release bundle written to ${path.relative(repositoryRoot, releaseRoot)}.`);
