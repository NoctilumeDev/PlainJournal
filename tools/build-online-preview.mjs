import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { execFileSync } from "node:child_process";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const sourceRoot = path.join(repositoryRoot, "online-preview");
const outputRoot = path.join(repositoryRoot, ".pages");
const assetRoot = path.join(outputRoot, "assets");
const visualRoot = path.join(outputRoot, "visuals");

await fs.rm(outputRoot, { recursive: true, force: true });
await fs.mkdir(assetRoot, { recursive: true });
const sourceCommit = execFileSync("git", ["rev-parse", "HEAD"], {
  cwd: repositoryRoot,
  encoding: "utf8",
}).trim();
if (!/^[0-9a-f]{40}$/u.test(sourceCommit)) {
  throw new Error("Online preview requires an exact source commit.");
}
const sourceMarker = '<a data-source-coordinate href="https://github.com/NoctilumeDev/PlainJournal/tree/main">维护主线</a>';
const hasLocalChanges = execFileSync("git", ["status", "--porcelain", "--untracked-files=no"], {
  cwd: repositoryRoot,
  encoding: "utf8",
}).trim().length > 0;
const template = await fs.readFile(path.join(sourceRoot, "index.html"), "utf8");
if (!template.includes(sourceMarker)) {
  throw new Error("Online preview is missing its source-coordinate marker.");
}
await fs.writeFile(path.join(outputRoot, "index.html"), template.replace(
  sourceMarker,
  `<a data-source-coordinate href="https://github.com/NoctilumeDev/PlainJournal/tree/${sourceCommit}">${sourceCommit.slice(0, 8)}${hasLocalChanges ? "（含本地修改）" : ""}</a>`,
), "utf8");
await fs.copyFile(path.join(sourceRoot, "styles.css"), path.join(outputRoot, "styles.css"));
await fs.copyFile(path.join(sourceRoot, "favicon.svg"), path.join(outputRoot, "favicon.svg"));
await fs.cp(path.join(repositoryRoot, "docs", "visuals"), visualRoot, {
  recursive: true,
  filter(source) {
    return !path.basename(source).startsWith("design-qa");
  },
});

for (const image of [
  "storefront-home.jpg",
  "storefront-product.jpg",
  "admin-governance.jpg",
]) {
  await fs.copyFile(
    path.join(repositoryRoot, "docs", "assets", "v7-4", image),
    path.join(assetRoot, image),
  );
}

for (const image of ["system-architecture.png", "functional-modules.png"]) {
  await fs.copyFile(
    path.join(repositoryRoot, "docs", "assets", "visuals", image),
    path.join(assetRoot, image),
  );
}

console.log(`Online preview written to ${path.relative(repositoryRoot, outputRoot)}.`);
