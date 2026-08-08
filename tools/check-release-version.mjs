import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const SEMVER_TAG = /^v(?<version>\d+\.\d+\.\d+)$/u;

export function versionFromReleaseTag(tag) {
  const match = SEMVER_TAG.exec(tag);
  if (!match) {
    throw new Error(`Release tag must use vMAJOR.MINOR.PATCH: ${tag}`);
  }
  return match.groups.version;
}

export function validateReleaseStatus(status, requestedTag) {
  const violations = [];
  if (!["release-candidate", "released"].includes(status)) {
    violations.push(`Unsupported verification status: ${status}`);
  }
  if (requestedTag && status !== "released") {
    violations.push(`Release tag requires status released, found ${status}`);
  }
  return violations;
}

export function validateFrontendVersionStatement(source, targetRelease, status) {
  const violations = [];
  const releasedStatement = `\`${targetRelease}\` 正式发布`;
  const candidateStatement = `\`${targetRelease}\` 验收候选`;
  if (status === "released") {
    if (!source.includes(releasedStatement)) {
      violations.push("frontend/README.md must state the released version");
    }
    if (source.includes(candidateStatement)) {
      violations.push("frontend/README.md still describes a released version as a candidate");
    }
  } else if (status === "release-candidate") {
    if (!source.includes(candidateStatement)) {
      violations.push("frontend/README.md must state the release candidate version");
    }
    if (source.includes(releasedStatement)) {
      violations.push("frontend/README.md describes a release candidate as released");
    }
  }
  return violations;
}

export function projectVersionFromPom(source, sourcePath) {
  const projectSection = source.replace(/<parent>[\s\S]*?<\/parent>/u, "");
  const match = /<artifactId>plainjournal-backend<\/artifactId>\s*<version>([^<]+)<\/version>/u
    .exec(projectSection);
  if (!match) {
    throw new Error(`Cannot find plainjournal-backend project version in ${sourcePath}`);
  }
  return match[1].trim();
}

export function parentVersionFromPom(source, sourcePath) {
  const parent = /<parent>([\s\S]*?)<\/parent>/u.exec(source);
  if (!parent || !/<groupId>com\.ecommerce\.platform<\/groupId>/u.test(parent[1])) {
    throw new Error(`Cannot find PlainJournal parent version in ${sourcePath}`);
  }
  const match = /<version>([^<]+)<\/version>/u.exec(parent[1]);
  if (!match) {
    throw new Error(`Cannot find PlainJournal parent version in ${sourcePath}`);
  }
  return match[1].trim();
}

async function listFiles(root, predicate) {
  const files = [];
  for (const entry of await fs.readdir(root, { withFileTypes: true })) {
    if (["node_modules", "target", "dist", "coverage", ".run", ".git"].includes(entry.name)) {
      continue;
    }
    const entryPath = path.join(root, entry.name);
    if (entry.isDirectory()) {
      files.push(...await listFiles(entryPath, predicate));
    } else if (predicate(entry.name, entryPath)) {
      files.push(entryPath);
    }
  }
  return files;
}

export async function validateReleaseVersion(repositoryRoot, requestedTag) {
  const violations = [];
  const baselinePath = path.join(repositoryRoot, ".github", "verification-baseline.json");
  const baseline = JSON.parse(await fs.readFile(baselinePath, "utf8"));
  let releaseVersion;
  try {
    releaseVersion = versionFromReleaseTag(baseline.targetRelease);
  } catch (error) {
    violations.push(error.message);
    return violations;
  }

  violations.push(...validateReleaseStatus(baseline.status, requestedTag));
  if (requestedTag && requestedTag !== baseline.targetRelease) {
    violations.push(
      `Release tag ${requestedTag} does not match verification baseline ${baseline.targetRelease}`,
    );
  }

  const backendRoot = path.join(repositoryRoot, "backend");
  const backendPomPath = path.join(backendRoot, "pom.xml");
  const backendPom = await fs.readFile(backendPomPath, "utf8");
  if (projectVersionFromPom(backendPom, backendPomPath) !== releaseVersion) {
    violations.push(
      `backend/pom.xml must use release version ${releaseVersion} without -SNAPSHOT`,
    );
  }

  const pomPaths = await listFiles(
    backendRoot,
    (name, filePath) => name === "pom.xml" && filePath !== backendPomPath,
  );
  for (const pomPath of pomPaths) {
    const parentVersion = parentVersionFromPom(await fs.readFile(pomPath, "utf8"), pomPath);
    if (parentVersion !== releaseVersion) {
      violations.push(
        `${path.relative(repositoryRoot, pomPath)} must inherit ${releaseVersion}, found ${parentVersion}`,
      );
    }
  }

  const frontendRoot = path.join(repositoryRoot, "frontend");
  const packagePaths = await listFiles(frontendRoot, (name) => name === "package.json");
  for (const packagePath of packagePaths) {
    const manifest = JSON.parse(await fs.readFile(packagePath, "utf8"));
    if (
      typeof manifest.name === "string"
      && (manifest.name === "plain-journal-frontend" || manifest.name.startsWith("@plain-journal/"))
      && manifest.version !== releaseVersion
    ) {
      violations.push(
        `${path.relative(repositoryRoot, packagePath)} must use ${releaseVersion}, found ${manifest.version}`,
      );
    }
  }

  const changelog = await fs.readFile(path.join(repositoryRoot, "CHANGELOG.md"), "utf8");
  if (!changelog.includes(`## [${releaseVersion}]`)) {
    violations.push(`CHANGELOG.md must include ${releaseVersion}`);
  }
  const releaseNotesPath = path.join(
    repositoryRoot,
    ".github",
    "release-notes",
    `${baseline.targetRelease}.md`,
  );
  try {
    await fs.access(releaseNotesPath);
  } catch {
    violations.push(`Missing release notes: ${path.relative(repositoryRoot, releaseNotesPath)}`);
  }

  const frontendReadme = await fs.readFile(path.join(frontendRoot, "README.md"), "utf8");
  violations.push(
    ...validateFrontendVersionStatement(frontendReadme, baseline.targetRelease, baseline.status),
  );

  return violations;
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : "";
if (invokedPath === fileURLToPath(import.meta.url)) {
  const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
  const requestedTag = process.argv[2];
  const violations = await validateReleaseVersion(repositoryRoot, requestedTag);
  if (violations.length > 0) {
    console.error(violations.join("\n"));
    process.exitCode = 1;
  } else {
    console.log("Release version boundary is current.");
  }
}
