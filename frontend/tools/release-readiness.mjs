import fs from "node:fs/promises";
import path from "node:path";

function requireText(source, expected, label, violations) {
  if (!source.includes(expected)) {
    violations.push(`${label} is missing: ${expected}`);
  }
}

function readReleaseDate(changelog, releaseVersion, violations) {
  const escapedVersion = releaseVersion.replace(/[.*+?^${}()|[\]\\]/gu, "\\$&");
  const heading = new RegExp(
    `^## \\[${escapedVersion}\\] - (\\d{4}-\\d{2}-\\d{2})$`,
    "mu",
  );
  const match = changelog.match(heading);
  if (!match) {
    violations.push(
      `CHANGELOG release heading is missing for version ${releaseVersion}`,
    );
    return null;
  }
  const releaseDate = match[1];
  const parsed = new Date(`${releaseDate}T00:00:00Z`);
  if (Number.isNaN(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== releaseDate) {
    violations.push(`CHANGELOG release date is invalid: ${releaseDate}`);
    return null;
  }
  return releaseDate;
}

export async function inspectReleaseReadiness(frontendRoot) {
  const violations = [];
  const repositoryRoot = path.resolve(frontendRoot, "..");
  const verificationBaselinePath = path.join(
    repositoryRoot,
    ".github",
    "verification-baseline.json",
  );
  const verificationBaseline = JSON.parse(
    await fs.readFile(verificationBaselinePath, "utf8"),
  );
  const releaseVersion = verificationBaseline.targetRelease.replace(/^v/u, "");
  const showcaseRelease =
    verificationBaseline.pendingRelease?.status === "released"
      ? verificationBaseline.pendingRelease.targetRelease
      : verificationBaseline.targetRelease;
  const paths = {
    readme: path.join(repositoryRoot, "README.md"),
    changelog: path.join(repositoryRoot, "CHANGELOG.md"),
    security: path.join(repositoryRoot, "SECURITY.md"),
    releaseChecklist: path.join(
      repositoryRoot,
      ".github",
      "RELEASE_CHECKLIST.md",
    ),
    releaseNotes: path.join(
      repositoryRoot,
      ".github",
      "release-notes",
      `${verificationBaseline.targetRelease}.md`,
    ),
    verificationBaseline: verificationBaselinePath,
    license: path.join(repositoryRoot, "LICENSE"),
    storefrontProductDetail: path.join(
      repositoryRoot,
      "docs",
      "assets",
      "showcase",
      showcaseRelease,
      "storefront-product-detail.png",
    ),
    storefrontCheckout: path.join(
      repositoryRoot,
      "docs",
      "assets",
      "showcase",
      showcaseRelease,
      "storefront-checkout.png",
    ),
    adminAfterSales: path.join(
      repositoryRoot,
      "docs",
      "assets",
      "showcase",
      showcaseRelease,
      "admin-after-sales.png",
    ),
  };

  const [
    readme,
    changelog,
    security,
    releaseChecklist,
    releaseNotes,
    license,
  ] = await Promise.all([
    fs.readFile(paths.readme, "utf8"),
    fs.readFile(paths.changelog, "utf8"),
    fs.readFile(paths.security, "utf8"),
    fs.readFile(paths.releaseChecklist, "utf8"),
    fs.readFile(paths.releaseNotes, "utf8"),
    fs.readFile(paths.license, "utf8"),
  ]);

  for (const marker of [
    `docs/assets/showcase/${showcaseRelease}/storefront-product-detail.png`,
    `docs/assets/showcase/${showcaseRelease}/storefront-checkout.png`,
    `docs/assets/showcase/${showcaseRelease}/admin-after-sales.png`,
    "pnpm demo:start",
    "reader@example.com",
    "admin@example.com",
    "LICENSE",
  ]) {
    requireText(readme, marker, "Root README release entry", violations);
  }
  for (const marker of [
    "Apache License 2.0",
    "tsconfig.base.json",
    "--no-build --pull never",
  ]) {
    requireText(changelog, marker, "CHANGELOG release entry", violations);
  }
  const releaseDate = readReleaseDate(changelog, releaseVersion, violations);
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
    `# PlainJournal ${verificationBaseline.targetRelease}`,
    "不增加业务域",
    "F12/CDP",
  ]) {
    requireText(releaseNotes, marker, "Release notes", violations);
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
    storefrontProductDetail: paths.storefrontProductDetail,
    storefrontCheckout: paths.storefrontCheckout,
    adminAfterSales: paths.adminAfterSales,
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
    releaseDate,
    screenshots,
    paths,
  };
}
