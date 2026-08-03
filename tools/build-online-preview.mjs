import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const sourceRoot = path.join(repositoryRoot, "online-preview");
const outputRoot = path.join(repositoryRoot, ".pages");
const assetRoot = path.join(outputRoot, "assets");

await fs.rm(outputRoot, { recursive: true, force: true });
await fs.mkdir(assetRoot, { recursive: true });
await fs.copyFile(path.join(sourceRoot, "index.html"), path.join(outputRoot, "index.html"));
await fs.copyFile(path.join(sourceRoot, "styles.css"), path.join(outputRoot, "styles.css"));
await fs.copyFile(path.join(sourceRoot, "favicon.svg"), path.join(outputRoot, "favicon.svg"));

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

console.log(`Online preview written to ${path.relative(repositoryRoot, outputRoot)}.`);
