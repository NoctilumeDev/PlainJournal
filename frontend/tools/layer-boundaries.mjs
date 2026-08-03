import { promises as fs } from "node:fs";
import path from "node:path";

export const LAYER_ORDER = Object.freeze({
  shared: 0,
  entities: 1,
  features: 2,
  pages: 3,
  app: 4,
});

const LEGACY_DIRECTORIES = new Set([
  "api",
  "components",
  "stores",
  "styles",
  "views",
]);

const SOURCE_EXTENSIONS = new Set([".css", ".js", ".mjs", ".ts", ".vue"]);

function normalizePath(value) {
  return value.replaceAll("\\", "/");
}

export function classifyLayer(relativePath) {
  const [layer, slice] = normalizePath(relativePath).split("/");
  return Object.hasOwn(LAYER_ORDER, layer)
    ? { layer, slice: slice ?? null }
    : null;
}

export function validateLayerEdge(sourceRelative, targetRelative) {
  const source = classifyLayer(sourceRelative);
  const target = classifyLayer(targetRelative);
  if (!source) {
    return null;
  }

  if (!target) {
    const [targetRoot] = normalizePath(targetRelative).split("/");
    if (source.layer !== "app" && LEGACY_DIRECTORIES.has(targetRoot)) {
      return `${source.layer} cannot depend on legacy ${targetRoot}; migrate or expose a lower-layer boundary first`;
    }
    return null;
  }

  if (LAYER_ORDER[target.layer] > LAYER_ORDER[source.layer]) {
    return `${source.layer} cannot import the higher ${target.layer} layer`;
  }

  if (
    source.layer === target.layer
    && (source.layer === "features" || source.layer === "entities")
    && source.slice !== target.slice
  ) {
    return `${source.layer}/${source.slice} cannot reach across to ${target.layer}/${target.slice}`;
  }

  return null;
}

export function validatePublicEntry(sourceRelative, targetRelative) {
  const source = classifyLayer(sourceRelative);
  const target = classifyLayer(targetRelative);
  if (
    !target
    || !["shared", "entities", "features"].includes(target.layer)
  ) {
    return null;
  }
  if (
    source?.layer === target.layer
    && source.slice === target.slice
  ) {
    return null;
  }

  const parts = normalizePath(targetRelative).split("/");
  if (
    parts.length === 2
    || (
      parts.length === 3
      && ["index", "index.ts", "styles.css"].includes(parts[2])
    )
  ) {
    return null;
  }
  return `${target.layer}/${target.slice} must be consumed through its public index`;
}

export function extractRelativeImports(source) {
  const imports = new Set();
  const staticPattern = /\b(?:import|export)\s+(?:type\s+)?(?:[\w*{},\s]+?\s+from\s+)?["']([^"']+)["']/gu;
  const dynamicPattern = /\bimport\s*\(\s*["']([^"']+)["']\s*\)/gu;
  for (const pattern of [staticPattern, dynamicPattern]) {
    for (const match of source.matchAll(pattern)) {
      if (match[1]?.startsWith(".")) {
        imports.add(match[1]);
      }
    }
  }
  return [...imports];
}

export function validateVisualTokenOwnership(relativePath, source) {
  const normalized = normalizePath(relativePath);
  if (normalized.startsWith("packages/design-system/")) {
    return [];
  }

  const violations = [];
  if (/var\(\s*--pj-palette-/u.test(source)) {
    violations.push(`${normalized} consumes a foundation palette token; use a semantic token`);
  }
  if (/#[\da-fA-F]{3,8}\b/u.test(source) || /\b(?:rgb|hsl)a?\(/u.test(source)) {
    violations.push(`${normalized} contains a raw color literal outside the design-system`);
  }
  return violations;
}

async function collectSourceFiles(directory) {
  const files = [];
  for (const entry of await fs.readdir(directory, { withFileTypes: true })) {
    const absolute = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      files.push(...await collectSourceFiles(absolute));
    } else if (SOURCE_EXTENSIONS.has(path.extname(entry.name))) {
      files.push(absolute);
    }
  }
  return files;
}

function resolveRelativeTarget(sourceFile, specifier, sourceRoot) {
  const absolute = path.resolve(path.dirname(sourceFile), specifier);
  return normalizePath(path.relative(sourceRoot, absolute));
}

async function readJson(file) {
  return JSON.parse(await fs.readFile(file, "utf8"));
}

export async function inspectFrontendBoundaries(frontendRoot) {
  const violations = [];
  let layeredFiles = 0;
  let checkedImports = 0;

  for (const application of ["storefront-web", "admin-web"]) {
    const sourceRoot = path.join(frontendRoot, application, "src");
    const files = await collectSourceFiles(sourceRoot);
    for (const file of files) {
      const sourceRelative = normalizePath(path.relative(sourceRoot, file));
      if (classifyLayer(sourceRelative)) {
        layeredFiles += 1;
      }
      const source = await fs.readFile(file, "utf8");
      for (const specifier of extractRelativeImports(source)) {
        checkedImports += 1;
        const targetRelative = resolveRelativeTarget(file, specifier, sourceRoot);
        const reasons = [
          validateLayerEdge(sourceRelative, targetRelative),
          validatePublicEntry(sourceRelative, targetRelative),
        ].filter(Boolean);
        for (const reason of reasons) {
          violations.push(`${application}/${sourceRelative} -> ${specifier}: ${reason}`);
        }
      }
    }
  }

  const foundationRoot = path.join(frontendRoot, "packages", "foundation");
  const foundationPackage = await readJson(path.join(foundationRoot, "package.json"));
  if (Object.keys(foundationPackage.exports ?? {}).some((entry) => entry.includes("style"))) {
    violations.push("foundation must not export visual styles; use @plain-journal/design-system");
  }

  const designSystemRoot = path.join(frontendRoot, "packages", "design-system");
  const designSystemPackage = await readJson(path.join(designSystemRoot, "package.json"));
  if (!designSystemPackage.exports?.["./tokens.css"] || !designSystemPackage.exports?.["./base.css"]) {
    violations.push("design-system must own both tokens.css and base.css exports");
  }

  const uiRoot = path.join(frontendRoot, "packages", "ui");
  const uiPackage = await readJson(path.join(uiRoot, "package.json"));
  if (!uiPackage.exports?.["."] || !uiPackage.exports?.["./styles.css"]) {
    violations.push("ui must expose both component and styles entries");
  }
  if (!uiPackage.peerDependencies?.["@plain-journal/design-system"]) {
    violations.push("ui must declare the design-system token contract");
  }
  if (uiPackage.dependencies?.["@plain-journal/foundation"]) {
    violations.push("ui primitives must not depend on business foundation contracts");
  }

  for (const application of ["storefront-web", "admin-web"]) {
    const applicationPackage = await readJson(path.join(frontendRoot, application, "package.json"));
    if (!applicationPackage.dependencies?.["@plain-journal/design-system"]) {
      violations.push(`${application} must declare @plain-journal/design-system directly`);
    }
    if (!applicationPackage.dependencies?.["@plain-journal/ui"]) {
      violations.push(`${application} must declare @plain-journal/ui directly`);
    }
    const mainSource = await fs.readFile(path.join(frontendRoot, application, "src", "main.ts"), "utf8");
    if (mainSource.includes("@plain-journal/foundation/styles/")) {
      violations.push(`${application} still imports styles from foundation`);
    }
    if (!mainSource.includes('@plain-journal/ui/styles.css')) {
      violations.push(`${application} must load shared UI primitive styles`);
    }
  }

  const visualRoots = [
    path.join(frontendRoot, "packages", "ui", "src"),
    path.join(frontendRoot, "storefront-web", "src"),
    path.join(frontendRoot, "admin-web", "src"),
  ];
  for (const visualRoot of visualRoots) {
    const files = await collectSourceFiles(visualRoot);
    for (const file of files) {
      const extension = path.extname(file);
      if (![".css", ".vue"].includes(extension)) {
        continue;
      }
      const source = await fs.readFile(file, "utf8");
      const relative = normalizePath(path.relative(frontendRoot, file));
      violations.push(...validateVisualTokenOwnership(relative, source));
    }
  }

  return { violations, layeredFiles, checkedImports };
}
