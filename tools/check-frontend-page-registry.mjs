import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const registryPath = path.join(repositoryRoot, "docs", "frontend-page-registry.json");
const routeFiles = {
  storefront: path.join(repositoryRoot, "frontend", "storefront-web", "src", "app", "router.ts"),
  admin: path.join(repositoryRoot, "frontend", "admin-web", "src", "router.ts"),
};
const allowedFamilies = new Set(["A", "B", "C", "D", "E", "F"]);

function fail(message) {
  throw new Error(`frontend page registry: ${message}`);
}

function routePaths(source) {
  return [...source.matchAll(/\bpath:\s*"([^"]+)"/gu)].map((match) => match[1]);
}

const registry = JSON.parse(await fs.readFile(registryPath, "utf8"));
if (!Array.isArray(registry.pages)) {
  fail("pages must be an array");
}
if (registry.targetPageCount !== 34) {
  fail(`targetPageCount must remain 34 until product scope changes, received ${registry.targetPageCount}`);
}
if (registry.implementedRouteCount !== registry.pages.length) {
  fail(`implementedRouteCount ${registry.implementedRouteCount} does not match ${registry.pages.length} records`);
}
if (registry.pages.length !== 33) {
  fail(`current routers are expected to expose 33 records, received ${registry.pages.length}`);
}
if (!Array.isArray(registry.unresolvedTargets) || registry.unresolvedTargets.length !== 1) {
  fail("the unresolved 34th target must remain explicit");
}

for (const [name, viewport] of Object.entries(registry.viewports ?? {})) {
  if (!Number.isInteger(viewport?.width) || !Number.isInteger(viewport?.height)) {
    fail(`${name} viewport must contain integer width and height`);
  }
}
for (const requiredViewport of ["desktop", "mobile"]) {
  if (!registry.viewports?.[requiredViewport]) {
    fail(`missing ${requiredViewport} viewport`);
  }
}

const ids = new Set();
for (const page of registry.pages) {
  if (ids.has(page.id)) {
    fail(`duplicate page id ${page.id}`);
  }
  ids.add(page.id);
  if (!(page.entry in routeFiles)) {
    fail(`${page.id} has unknown entry ${page.entry}`);
  }
  if (!allowedFamilies.has(page.family)) {
    fail(`${page.id} has unknown family ${page.family}`);
  }
  if (!page.samplePath?.startsWith("/")) {
    fail(`${page.id} must provide an absolute samplePath`);
  }
  if (!Array.isArray(page.factOwners) || !Array.isArray(page.keyStates) || page.keyStates.length === 0) {
    fail(`${page.id} must declare factOwners and at least one key state`);
  }
  const componentPath = path.join(repositoryRoot, ...page.component.split("/"));
  try {
    await fs.access(componentPath);
  } catch {
    fail(`${page.id} component does not exist: ${page.component}`);
  }
}

for (const [entry, routeFile] of Object.entries(routeFiles)) {
  const source = await fs.readFile(routeFile, "utf8");
  const actual = routePaths(source).sort();
  const registered = registry.pages
    .filter((page) => page.entry === entry)
    .map((page) => page.route)
    .sort();
  if (JSON.stringify(actual) !== JSON.stringify(registered)) {
    fail(`${entry} route set differs\nactual: ${actual.join(", ")}\nregistered: ${registered.join(", ")}`);
  }
}

console.log(
  `Frontend page registry verified: ${registry.pages.length} implemented routes, `
  + `${registry.targetPageCount - registry.pages.length} unresolved target.`,
);
