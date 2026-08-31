import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { functionalModules } from "../docs/visuals/data/functional-modules.data.js";
import { systemArchitecture } from "../docs/visuals/data/system-architecture.data.js";

function compareSets(actual, documented, label, violations) {
  for (const value of actual) {
    if (!documented.has(value)) {
      violations.push(`${label} is missing ${value}`);
    }
  }
  for (const value of documented) {
    if (!actual.has(value)) {
      violations.push(`${label} contains unknown ${value}`);
    }
  }
}

async function discoverApplications(repositoryRoot) {
  const servicesRoot = path.join(repositoryRoot, "backend", "services");
  const entries = await fs.readdir(servicesRoot, { withFileTypes: true });
  return new Set([
    "ecommerce-gateway",
    ...entries
      .filter((entry) => entry.isDirectory() && entry.name.endsWith("-service"))
      .map((entry) => entry.name),
  ]);
}

async function discoverFrontendApplications(repositoryRoot) {
  const frontendRoot = path.join(repositoryRoot, "frontend");
  const entries = await fs.readdir(frontendRoot, { withFileTypes: true });
  const applications = [];
  for (const entry of entries) {
    if (!entry.isDirectory() || !entry.name.endsWith("-web")) {
      continue;
    }
    try {
      await fs.access(path.join(frontendRoot, entry.name, "package.json"));
      applications.push(entry.name);
    } catch {
      // A similarly named folder without a package manifest is not a runnable frontend.
    }
  }
  return new Set(applications);
}

async function discoverServiceSchemas(repositoryRoot, violations) {
  const servicesRoot = path.join(repositoryRoot, "backend", "services");
  const entries = await fs.readdir(servicesRoot, { withFileTypes: true });
  const schemas = new Map();

  for (const entry of entries) {
    if (!entry.isDirectory() || !entry.name.endsWith("-service")) {
      continue;
    }
    const applicationPath = path.join(
      servicesRoot,
      entry.name,
      "src",
      "main",
      "resources",
      "application.yml",
    );
    const source = await fs.readFile(applicationPath, "utf8");
    const matches = [...source.matchAll(/jdbc:mysql:[^\n]*\/\$\{[^:}]+:(ecom_[a-z0-9_]+)\}/g)]
      .map((match) => match[1]);
    const uniqueMatches = [...new Set(matches)];
    if (uniqueMatches.length !== 1) {
      violations.push(
        `${entry.name} must declare exactly one primary owner schema in application.yml`,
      );
      continue;
    }
    schemas.set(entry.name, uniqueMatches[0]);
  }

  return schemas;
}

async function discoverGatewayTargets(repositoryRoot) {
  const source = await fs.readFile(
    path.join(
      repositoryRoot,
      "backend",
      "ecommerce-gateway",
      "src",
      "main",
      "resources",
      "application.yml",
    ),
    "utf8",
  );
  return new Set(
    [...source.matchAll(/uri:\s+lb(?::ws)?:\/\/([a-z0-9-]+)/g)]
      .map((match) => match[1]),
  );
}

function extractRendererClasses(source) {
  const classes = new Set();
  for (const match of source.matchAll(/\bclass="([^"]+)"/g)) {
    for (const className of match[1].split(/\s+/)) {
      if (className && !className.includes("${")) {
        classes.add(className);
      }
    }
  }
  return classes;
}

async function checkRendererIsolation(repositoryRoot, pageSources, rendererSources, violations) {
  const visualRoot = path.join(repositoryRoot, "docs", "visuals");
  const sharedStyles = await fs.readFile(
    path.join(visualRoot, "assets", "diagram.css"),
    "utf8",
  );
  const architectureStyles = await fs.readFile(
    path.join(visualRoot, "assets", "system-architecture.css"),
    "utf8",
  );

  if (/\.architecture(?:-|\b)/.test(sharedStyles)) {
    violations.push("shared diagram styles contain system architecture selectors");
  }
  if (/\.(?:functional|function)(?:-|\b)/.test(architectureStyles)) {
    violations.push("system architecture styles contain functional module selectors");
  }
  for (const forbiddenSelector of ["module-node", "domain-node", "entrance-node", "root-branch"]) {
    const selectorPattern = new RegExp(`(^|[\\s,>+~])\\.${forbiddenSelector}(?=[\\s:{.#>+~,\\[])`, "m");
    if (selectorPattern.test(architectureStyles)) {
      violations.push(`system architecture styles reuse functional selector ${forbiddenSelector}`);
    }
  }

  const architecturePage = pageSources.get("system-architecture.html");
  const functionalPage = pageSources.get("functional-modules.html");
  if (!architecturePage.includes("assets/system-architecture.css")) {
    violations.push("system architecture page does not load its isolated stylesheet");
  }
  if (functionalPage.includes("assets/system-architecture.css")) {
    violations.push("functional module page loads the system architecture stylesheet");
  }

  const architectureRenderer = rendererSources.get("system-architecture.js");
  const functionalRenderer = rendererSources.get("functional-modules.js");
  if (/\b(?:functional|function)-/.test(architectureRenderer)) {
    violations.push("system architecture renderer reuses functional tree classes");
  }
  if (/\barchitecture-/.test(functionalRenderer)) {
    violations.push("functional module renderer reuses system architecture classes");
  }

  const architectureClasses = extractRendererClasses(architectureRenderer);
  const functionalClasses = extractRendererClasses(functionalRenderer);
  const sharedClassAllowlist = new Set([
    "diagram-eyebrow",
    "diagram-reading-note",
    "diagram-vertical-flow",
    "flow-cue",
  ]);
  for (const className of architectureClasses) {
    if (functionalClasses.has(className) && !sharedClassAllowlist.has(className)) {
      violations.push(`visual renderers share implementation class ${className}`);
    }
  }

  if (!architectureClasses.has("architecture-tree-canvas")
    || !architectureClasses.has("architecture-mobile-canvas")) {
    violations.push("system architecture does not declare isolated desktop and mobile trees");
  }
  if (!functionalClasses.has("function-canvas")
    || !functionalClasses.has("function-mobile-canvas")) {
    violations.push("functional modules do not declare isolated desktop and mobile trees");
  }
}

async function checkPageAssets(repositoryRoot, violations) {
  const visualRoot = path.join(repositoryRoot, "docs", "visuals");
  const pages = [
    ["system-architecture.html", "system-architecture.js"],
    ["functional-modules.html", "functional-modules.js"],
  ];
  const pageSources = new Map();
  const rendererSources = new Map();
  for (const [page, script] of pages) {
    const source = await fs.readFile(path.join(visualRoot, page), "utf8");
    const renderer = await fs.readFile(path.join(visualRoot, script), "utf8");
    pageSources.set(page, source);
    rendererSources.set(script, renderer);
    for (const required of [
      "assets/paper.css",
      "assets/diagram.css",
      "assets/paper-atmosphere.js",
      script,
    ]) {
      if (!source.includes(required)) {
        violations.push(`${page} does not load ${required}`);
      }
    }
    if (source.includes("filter-shell") || source.includes("filters")) {
      violations.push(`${page} still contains a content-hiding filter`);
    }
    if (page === "functional-modules.html") {
      if (!renderer.includes("diagram-vertical-flow")) {
        violations.push("functional module tree does not declare its vertical reading flow");
      }
      if (renderer.includes("canvas-title")) {
        violations.push("functional module tree repeats the page title inside the canvas");
      }
      if (renderer.includes("diagram-scroll")) {
        violations.push("functional module tree still uses a horizontal scroll container");
      }
    }
  }
  await checkRendererIsolation(
    repositoryRoot,
    pageSources,
    rendererSources,
    violations,
  );
}

async function checkDraftCoverage(repositoryRoot, architecture, modules, violations) {
  const visualRoot = path.join(repositoryRoot, "docs", "visuals");
  const architectureDraft = await fs.readFile(
    path.join(visualRoot, "system-architecture-draft.md"),
    "utf8",
  );
  const moduleDraft = await fs.readFile(
    path.join(visualRoot, "functional-modules-draft.md"),
    "utf8",
  );

  for (const service of architecture.serviceGroups.flatMap((group) => group.services)) {
    if (!architectureDraft.includes(`\`${service.id}\``)) {
      violations.push(`system architecture draft is missing ${service.id}`);
    }
    if (!architectureDraft.includes(`\`${service.owner}\``)) {
      violations.push(`system architecture draft is missing ${service.owner}`);
    }
  }

  for (const module of modules.modules) {
    if (!moduleDraft.includes(`| ${module.title} |`)) {
      violations.push(`functional module draft is missing ${module.title}`);
    }
  }
}

async function checkSourceCoverage(repositoryRoot, architecture, actualSchemas, violations) {
  const serviceArchitecture = await fs.readFile(
    path.join(repositoryRoot, "docs", "02-service-architecture.md"),
    "utf8",
  );
  const dataOwnership = await fs.readFile(
    path.join(repositoryRoot, "docs", "04-data-ownership.md"),
    "utf8",
  );
  const dockerReadme = await fs.readFile(
    path.join(repositoryRoot, "deploy", "docker", "README.md"),
    "utf8",
  );

  if (!serviceArchitecture.includes(`\`${architecture.gateway.id}\``)) {
    violations.push(`service architecture source is missing ${architecture.gateway.id}`);
  }
  for (const [serviceId, schema] of actualSchemas) {
    if (!serviceArchitecture.includes(`\`${serviceId}\``)) {
      violations.push(`service architecture source is missing ${serviceId}`);
    }
    const ownershipDomain = serviceId.replace(/-service$/, "");
    if (!dataOwnership.includes(`| ${ownershipDomain} |`)) {
      violations.push(`data ownership source is missing ${ownershipDomain}`);
    }
    if (!dataOwnership.includes(`\`${schema}\``)) {
      violations.push(`data ownership source is missing ${schema}`);
    }
    if (!dockerReadme.includes(`\`${schema}\``)) {
      violations.push(`docker bootstrap documentation is missing ${schema}`);
    }
  }
}

export async function inspectVisualDocs(
  repositoryRoot,
  architecture = systemArchitecture,
  modules = functionalModules,
) {
  const violations = [];
  const actualApplications = await discoverApplications(repositoryRoot);
  const actualFrontends = await discoverFrontendApplications(repositoryRoot);
  const actualSchemas = await discoverServiceSchemas(repositoryRoot, violations);
  const actualGatewayTargets = await discoverGatewayTargets(repositoryRoot);
  const architectureApplications = new Set([
    architecture.gateway.id,
    ...architecture.serviceGroups.flatMap((group) => group.services.map((service) => service.id)),
  ]);
  compareSets(actualApplications, architectureApplications, "system architecture", violations);

  const architectureFrontends = new Set(
    architecture.experiences.map((experience) => experience.id),
  );
  compareSets(
    actualFrontends,
    architectureFrontends,
    "system architecture frontends",
    violations,
  );

  const functionalFrontends = new Set(
    modules.entrances.map((entrance) => entrance.subtitle.split(/[\s/]/, 1)[0]),
  );
  compareSets(
    actualFrontends,
    functionalFrontends,
    "functional module frontends",
    violations,
  );

  const schemaOwners = architecture.serviceGroups
    .flatMap((group) => group.services)
    .map((service) => service.owner);
  if (new Set(schemaOwners).size !== schemaOwners.length) {
    violations.push("system architecture contains duplicate owner schemas");
  }

  const documentedSchemas = new Map(
    architecture.serviceGroups.flatMap((group) =>
      group.services.map((service) => [service.id, service.owner]),
    ),
  );
  for (const [serviceId, actualSchema] of actualSchemas) {
    const documentedSchema = documentedSchemas.get(serviceId);
    if (documentedSchema && documentedSchema !== actualSchema) {
      violations.push(
        `schema ownership for ${serviceId} is ${actualSchema}, not ${documentedSchema}`,
      );
    }
  }

  const ownerServiceIds = new Set(
    architecture.serviceGroups.flatMap((group) => group.services.map((service) => service.id)),
  );
  compareSets(
    ownerServiceIds,
    new Set(architecture.gatewayTargets),
    "gateway topology",
    violations,
  );
  if (new Set(architecture.gatewayTargets).size !== architecture.gatewayTargets.length) {
    violations.push("gateway topology contains duplicate service targets");
  }
  compareSets(
    actualGatewayTargets,
    new Set(architecture.gatewayTargets),
    "gateway routes",
    violations,
  );

  const architectureServiceTitles = new Set(
    architecture.serviceGroups.flatMap((group) => group.services.map((service) => service.title)),
  );
  for (const call of architecture.synchronous) {
    if (!architectureServiceTitles.has(call.from)) {
      violations.push(`synchronous topology references unknown ${call.from}`);
    }
    if (!architectureServiceTitles.has(call.to)) {
      violations.push(`synchronous topology references unknown ${call.to}`);
    }
  }
  for (const service of [
    ...architecture.eventFlow.producers,
    ...architecture.eventFlow.consumers,
  ]) {
    if (!architectureServiceTitles.has(service)) {
      violations.push(`event topology references unknown ${service}`);
    }
  }

  const moduleReferences = new Set(
    modules.modules.flatMap((module) => [
      module.owner,
      ...module.collaborators,
    ]),
  );
  for (const service of moduleReferences) {
    if (!actualApplications.has(service)) {
      violations.push(`functional modules reference unknown ${service}`);
    }
  }

  const moduleIds = modules.modules.map((module) => module.id);
  if (new Set(moduleIds).size !== moduleIds.length) {
    violations.push("functional modules contain duplicate module ids");
  }

  const treeModuleIds = modules.entrances.flatMap((entrance) =>
    entrance.domains.flatMap((domain) => domain.moduleIds));
  compareSets(
    new Set(moduleIds),
    new Set(treeModuleIds),
    "functional module tree",
    violations,
  );
  if (new Set(treeModuleIds).size !== treeModuleIds.length) {
    violations.push("functional module tree contains duplicate module results");
  }

  await checkPageAssets(repositoryRoot, violations);
  await checkDraftCoverage(repositoryRoot, architecture, modules, violations);
  await checkSourceCoverage(repositoryRoot, architecture, actualSchemas, violations);

  return {
    violations,
    applications: actualApplications.size,
    frontends: actualFrontends.size,
    gatewayRoutes: actualGatewayTargets.size,
    ownerSchemas: schemaOwners.length,
    modules: moduleIds.length,
  };
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : "";
if (invokedPath === fileURLToPath(import.meta.url)) {
  const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
  const result = await inspectVisualDocs(repositoryRoot);
  if (result.violations.length > 0) {
    console.error("Visual documentation violations:");
    for (const violation of result.violations) {
      console.error(`- ${violation}`);
    }
    process.exitCode = 1;
  } else {
    console.log(
      `Visual docs passed: ${result.applications} applications, `
      + `${result.frontends} frontends, ${result.gatewayRoutes} gateway targets, `
      + `${result.ownerSchemas} owner schemas, ${result.modules} functional modules.`,
    );
  }
}
