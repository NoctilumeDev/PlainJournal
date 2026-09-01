import crypto from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const registry = JSON.parse(await fs.readFile(
  path.join(repositoryRoot, "docs", "frontend-page-registry.json"),
  "utf8",
));
const baselineRoot = path.join(
  repositoryRoot,
  "docs",
  "assets",
  "frontend-baseline",
  "2026-08-31",
);
const manifest = JSON.parse(await fs.readFile(path.join(baselineRoot, "manifest.json"), "utf8"));

function fail(message) {
  throw new Error(`frontend baseline: ${message}`);
}

function jpegSize(buffer) {
  if (buffer[0] !== 0xff || buffer[1] !== 0xd8) {
    fail("screenshot is not a JPEG");
  }
  let offset = 2;
  while (offset + 9 < buffer.length) {
    if (buffer[offset] !== 0xff) {
      offset += 1;
      continue;
    }
    const marker = buffer[offset + 1];
    offset += 2;
    if (marker === 0xd8 || marker === 0xd9) {
      continue;
    }
    const length = buffer.readUInt16BE(offset);
    if ([0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf]
      .includes(marker)) {
      return {
        height: buffer.readUInt16BE(offset + 3),
        width: buffer.readUInt16BE(offset + 5),
      };
    }
    offset += length;
  }
  fail("JPEG dimensions were not found");
}

const expectedCount = registry.pages.length * Object.keys(registry.viewports).length;
if (manifest.implementedPages !== registry.pages.length || manifest.screenshots !== expectedCount) {
  fail(`manifest count mismatch: expected ${expectedCount}, received ${manifest.screenshots}`);
}
if (!Array.isArray(manifest.pages) || manifest.pages.length !== expectedCount) {
  fail(`expected ${expectedCount} manifest records`);
}

const expectedKeys = new Set(
  registry.pages.flatMap((page) => Object.keys(registry.viewports)
    .map((viewport) => `${page.id}:${viewport}`)),
);
const actualKeys = new Set();

for (const record of manifest.pages) {
  const key = `${record.id}:${record.viewport}`;
  if (!expectedKeys.has(key)) {
    fail(`unexpected record ${key}`);
  }
  if (actualKeys.has(key)) {
    fail(`duplicate record ${key}`);
  }
  actualKeys.add(key);
  if (!record.file.endsWith(".jpg")) {
    fail(`${key} must use the .jpg extension emitted by the in-app browser`);
  }
  const filePath = path.resolve(repositoryRoot, record.file);
  if (!filePath.startsWith(repositoryRoot + path.sep)) {
    fail(`${key} escaped the repository root`);
  }
  const bytes = await fs.readFile(filePath);
  const size = jpegSize(bytes);
  const sha256 = crypto.createHash("sha256").update(bytes).digest("hex");
  if (bytes.length !== record.bytes || sha256 !== record.sha256) {
    fail(`${key} file content differs from the manifest`);
  }
  if (size.width !== record.width || size.height !== record.height) {
    fail(`${key} dimensions differ from the manifest`);
  }
  const viewport = registry.viewports[record.viewport];
  if (![viewport.width, viewport.width - 16].includes(size.width)) {
    fail(`${key} width ${size.width} is outside viewport ${viewport.width}`);
  }
}

if (actualKeys.size !== expectedKeys.size) {
  fail("one or more page/viewport pairs are missing");
}

console.log(
  `Frontend baseline verified: ${registry.pages.length} pages, ${manifest.screenshots} screenshots.`,
);
