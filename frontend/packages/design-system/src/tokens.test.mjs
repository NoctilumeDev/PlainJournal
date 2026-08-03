import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const source = await readFile(new URL("./tokens.css", import.meta.url), "utf8");
const baseSource = await readFile(new URL("./base.css", import.meta.url), "utf8");
const qingheBlock = source.match(/:root\[data-pj-theme="qinghe"\]\s*\{(?<body>[\s\S]*?)\}/u)?.groups?.body ?? "";
const bodyBlock = baseSource.match(/body\s*\{(?<body>[\s\S]*?)\}/u)?.groups?.body ?? "";

function tokenValue(name) {
  return source.match(new RegExp(`--${name}:\\s*(?<value>#[0-9a-f]{6})`, "iu"))?.groups?.value;
}

function relativeLuminance(hex) {
  const channels = hex.match(/[0-9a-f]{2}/giu).map((channel) => Number.parseInt(channel, 16) / 255);
  const linear = channels.map((channel) =>
    channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4);
  return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2];
}

function contrastRatio(foreground, background) {
  const foregroundLuminance = relativeLuminance(foreground);
  const backgroundLuminance = relativeLuminance(background);
  return (
    (Math.max(foregroundLuminance, backgroundLuminance) + 0.05)
    / (Math.min(foregroundLuminance, backgroundLuminance) + 0.05)
  );
}

test("keeps raw palette values inside the design-system token source", () => {
  assert.match(source, /--pj-palette-lotus-500:/u);
  assert.match(source, /--pj-surface-page:/u);
  assert.match(source, /--pj-action-primary:/u);
});

test("keeps risk and lifecycle status semantics outside theme overrides", () => {
  for (const status of [
    "success",
    "warning",
    "danger",
    "processing",
    "unknown",
    "attention",
    "refunded",
  ]) {
    assert.match(source, new RegExp(`--pj-status-${status}-text:`, "u"));
    assert.doesNotMatch(qingheBlock, new RegExp(`--pj-status-${status}-`, "u"));
  }
});

test("keeps compatibility aliases mapped to semantic tokens", () => {
  assert.match(source, /--pj-color-canvas:\s*var\(--pj-surface-page\)/u);
  assert.match(source, /--pj-color-danger:\s*var\(--pj-status-danger-text\)/u);
  assert.match(source, /--pj-content-width:\s*var\(--pj-layout-content\)/u);
});

test("keeps warning text above WCAG AA contrast on its semantic surface", () => {
  const warningText = tokenValue("pj-palette-amber-700");
  const warningSurface = tokenValue("pj-status-warning-surface");

  assert.ok(warningText);
  assert.ok(warningSurface);
  assert.ok(
    contrastRatio(warningText, warningSurface) >= 4.5,
    `warning contrast must be at least 4.5:1, got ${contrastRatio(warningText, warningSurface).toFixed(2)}:1`,
  );
});

test("keeps Subai secondary text above WCAG AA on soft and processing surfaces", () => {
  const secondaryText = tokenValue("pj-palette-ink-650");
  const softSurface = tokenValue("pj-palette-paper-150");
  const processingSurface = tokenValue("pj-status-processing-surface");

  assert.ok(secondaryText);
  assert.ok(softSurface);
  assert.ok(processingSurface);
  for (const surface of [softSurface, processingSurface]) {
    assert.ok(
      contrastRatio(secondaryText, surface) >= 4.5,
      `secondary text contrast must be at least 4.5:1, got ${contrastRatio(secondaryText, surface).toFixed(2)}:1`,
    );
  }
});

test("allows a 320px viewport to shrink around non-overlay scrollbars", () => {
  assert.match(bodyBlock, /min-width:\s*0;/u);
  assert.doesNotMatch(bodyBlock, /min-width:\s*20rem;/u);
});
