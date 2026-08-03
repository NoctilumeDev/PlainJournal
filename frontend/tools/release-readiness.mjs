import fs from "node:fs/promises";
import path from "node:path";

function requireText(source, expected, label, violations) {
  if (!source.includes(expected)) {
    violations.push(`${label} is missing: ${expected}`);
  }
}

export async function inspectReleaseReadiness(frontendRoot) {
  const violations = [];
  const repositoryRoot = path.resolve(frontendRoot, "..");
  const paths = {
    readme: path.join(repositoryRoot, "README.md"),
    changelog: path.join(repositoryRoot, "CHANGELOG.md"),
    security: path.join(repositoryRoot, "SECURITY.md"),
    releaseChecklist: path.join(
      repositoryRoot,
      ".github",
      "RELEASE_CHECKLIST.md",
    ),
    license: path.join(repositoryRoot, "LICENSE"),
    storefrontHome: path.join(
      repositoryRoot,
      "docs",
      "assets",
      "v7-4",
      "storefront-home.jpg",
    ),
    storefrontProduct: path.join(
      repositoryRoot,
      "docs",
      "assets",
      "v7-4",
      "storefront-product.jpg",
    ),
    adminGovernance: path.join(
      repositoryRoot,
      "docs",
      "assets",
      "v7-4",
      "admin-governance.jpg",
    ),
  };

  const [readme, changelog, security, releaseChecklist, license] = await Promise.all([
    fs.readFile(paths.readme, "utf8"),
    fs.readFile(paths.changelog, "utf8"),
    fs.readFile(paths.security, "utf8"),
    fs.readFile(paths.releaseChecklist, "utf8"),
    fs.readFile(paths.license, "utf8"),
  ]);

  for (const marker of [
    "docs/assets/v7-4/storefront-home.jpg",
    "docs/assets/v7-4/storefront-product.jpg",
    "docs/assets/v7-4/admin-governance.jpg",
    "pnpm demo:start",
    "reader@example.com",
    "admin@example.com",
    "LICENSE",
  ]) {
    requireText(readme, marker, "Root README release entry", violations);
  }
  for (const marker of [
    "[1.0.0] - 2026-08-03",
    "Apache License 2.0",
    "tsconfig.base.json",
    "--no-build --pull never",
  ]) {
    requireText(changelog, marker, "CHANGELOG release candidate", violations);
  }
  for (const marker of [
    "GitHub Private Vulnerability Reporting",
    "不要在公开 Issue",
    "演示账号边界",
  ]) {
    requireText(security, marker, "Security policy", violations);
  }
  for (const marker of [
    "不得自行提交、推送",
    "Apache License 2.0",
    "mvn clean verify",
    "pnpm check",
    "--no-build --pull never",
  ]) {
    requireText(releaseChecklist, marker, "Release checklist", violations);
  }
  for (const marker of [
    "Apache License",
    "Version 2.0, January 2004",
    "Copyright [yyyy] [name of copyright owner]",
  ]) {
    requireText(license, marker, "Apache-2.0 license", violations);
  }

  const screenshots = [];
  for (const [name, screenshotPath] of Object.entries({
    storefrontHome: paths.storefrontHome,
    storefrontProduct: paths.storefrontProduct,
    adminGovernance: paths.adminGovernance,
  })) {
    const stat = await fs.stat(screenshotPath);
    screenshots.push({
      name,
      path: screenshotPath,
      bytes: stat.size,
    });
    if (stat.size < 32 * 1024) {
      violations.push(`${name} screenshot is unexpectedly small`);
    }
    if (stat.size > 1024 * 1024) {
      violations.push(`${name} screenshot exceeds the 1 MiB README budget`);
    }
  }

  return {
    violations,
    licensePresent: true,
    licenseId: "Apache-2.0",
    screenshots,
    paths,
  };
}
