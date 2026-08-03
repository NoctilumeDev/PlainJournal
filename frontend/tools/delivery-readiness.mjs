import fs from "node:fs/promises";
import path from "node:path";

const IMAGE_EXTENSIONS = new Set([
  ".avif",
  ".gif",
  ".jpeg",
  ".jpg",
  ".png",
  ".svg",
  ".webp",
]);

const SOURCE_MARKERS = [
  {
    label: "deleted legacy workspace",
    pattern: /ecommerce-platform/iu,
  },
  {
    label: "legacy product identity",
    pattern: /DarkRoomLibrary/iu,
  },
  {
    label: "temporary public entry",
    pattern: /(?:测试|临时|演示)\s*(?:入口|页面|工作台)/u,
  },
  {
    label: "phase label exposed in product UI",
    pattern: /(?:^|[>\s])(?:M|V)\d+(?:\.\d+)*(?=[：:\s<])/u,
  },
  {
    label: "mock implementation exposed in product UI",
    pattern: /\bmock\s*(?:api|data|fixture)\b/iu,
  },
];

export const RETIRED_SELECTORS = [
  "admin-boundary-note",
  "admin-danger-text",
  "admin-feedback--error",
  "admin-state--error",
  "form-actions",
  "status-label--bounded",
];

async function listFiles(root, predicate) {
  const files = [];

  async function visit(directory) {
    let entries;
    try {
      entries = await fs.readdir(directory, { withFileTypes: true });
    } catch (error) {
      if (error?.code === "ENOENT") {
        return;
      }
      throw error;
    }

    for (const entry of entries) {
      const absolutePath = path.join(directory, entry.name);
      if (entry.isDirectory()) {
        await visit(absolutePath);
      } else if (predicate(absolutePath)) {
        files.push(absolutePath);
      }
    }
  }

  await visit(root);
  return files.sort();
}

function extractTemplate(source) {
  return source.match(/<template(?:\s[^>]*)?>([\s\S]*?)<\/template>/iu)?.[1] ?? "";
}

export function extractRoutePaths(source) {
  return [...source.matchAll(/\bpath:\s*["']([^"']+)["']/gu)]
    .map((match) => match[1]);
}

export function findDeliverySourceMarkers(source) {
  return SOURCE_MARKERS
    .filter(({ pattern }) => pattern.test(source))
    .map(({ label }) => label);
}

export function findUserVisiblePhaseLabels(source) {
  return [...source.matchAll(
    /["'`][^"'`\r\n]*\b(?:M|V)\d+(?:\.\d+)*(?=\s)[^"'`\r\n]*["'`]/gu,
  )].map((match) => match[0]);
}

function findDuplicates(values) {
  const counts = new Map();
  for (const value of values) {
    counts.set(value, (counts.get(value) ?? 0) + 1);
  }
  return [...counts.entries()]
    .filter(([, count]) => count > 1)
    .map(([value]) => value);
}

export async function inspectDeliveryReadiness(frontendRoot) {
  const violations = [];
  const warnings = [];
  const routerFiles = {
    storefront: path.join(frontendRoot, "storefront-web", "src", "app", "router.ts"),
    admin: path.join(frontendRoot, "admin-web", "src", "router.ts"),
  };
  const routePaths = {};

  for (const [application, routerFile] of Object.entries(routerFiles)) {
    const source = await fs.readFile(routerFile, "utf8");
    const paths = extractRoutePaths(source);
    routePaths[application] = paths;
    for (const duplicate of findDuplicates(paths)) {
      violations.push(`${application} router repeats path ${duplicate}`);
    }
  }

  const applicationRoots = [
    path.join(frontendRoot, "storefront-web", "src"),
    path.join(frontendRoot, "admin-web", "src"),
  ];
  const productionSources = [];
  for (const applicationRoot of applicationRoots) {
    productionSources.push(...await listFiles(
      applicationRoot,
      (file) => file.endsWith(".vue") && !file.includes(".test."),
    ));
  }

  for (const sourceFile of productionSources) {
    const source = await fs.readFile(sourceFile, "utf8");
    const template = extractTemplate(source);
    for (const marker of findDeliverySourceMarkers(template)) {
      violations.push(
        `${path.relative(frontendRoot, sourceFile)} exposes ${marker}`,
      );
    }
  }

  const demoFixturePath = path.join(frontendRoot, "e2e", "mock-api.mjs");
  const demoFixture = await fs.readFile(demoFixturePath, "utf8");
  for (const label of findUserVisiblePhaseLabels(demoFixture)) {
    violations.push(
      `e2e/mock-api.mjs exposes phase label in demo fixture: ${label}`,
    );
  }

  const stylesheetRoots = [
    path.join(frontendRoot, "storefront-web", "src"),
    path.join(frontendRoot, "admin-web", "src"),
    path.join(frontendRoot, "packages"),
  ];
  const stylesheets = [];
  for (const stylesheetRoot of stylesheetRoots) {
    stylesheets.push(...await listFiles(
      stylesheetRoot,
      (file) => file.endsWith(".css"),
    ));
  }
  for (const stylesheet of stylesheets) {
    const source = await fs.readFile(stylesheet, "utf8");
    for (const selector of RETIRED_SELECTORS) {
      if (source.includes(`.${selector}`)) {
        violations.push(
          `${path.relative(frontendRoot, stylesheet)} retains .${selector}`,
        );
      }
    }
  }

  const readme = await fs.readFile(path.join(frontendRoot, "README.md"), "utf8");
  if (/下一坐标为 V[1-6]/u.test(readme)) {
    violations.push("frontend/README.md still points to a completed visual phase");
  }
  if (
    !readme.includes("V1–V7.4 已全部完成")
    || !readme.includes("v1.0.0")
    || !readme.includes("Apache-2.0")
  ) {
    violations.push(
      "frontend/README.md does not state V7.4 completion and the Apache-2.0 v1.0.0 release boundary",
    );
  }

  const assetRoots = [
    path.join(frontendRoot, "storefront-web", "public"),
    path.join(frontendRoot, "storefront-web", "src", "assets"),
    path.join(frontendRoot, "admin-web", "public"),
    path.join(frontendRoot, "admin-web", "src", "assets"),
  ];
  const assets = [];
  for (const assetRoot of assetRoots) {
    const assetFiles = await listFiles(
      assetRoot,
      (file) => IMAGE_EXTENSIONS.has(path.extname(file).toLowerCase()),
    );
    for (const assetFile of assetFiles) {
      const stat = await fs.stat(assetFile);
      const asset = {
        path: path.relative(frontendRoot, assetFile).replaceAll("\\", "/"),
        bytes: stat.size,
      };
      assets.push(asset);
    }
  }

  const optimizedAssets = assets.filter((asset) =>
    /-\d+\.(?:avif|webp)$/u.test(asset.path));
  const originalAssets = assets.filter((asset) =>
    !/-\d+\.(?:avif|webp)$/u.test(asset.path));
  const assetPaths = new Set(assets.map((asset) => asset.path));

  for (const original of originalAssets.filter((asset) =>
    asset.path.endsWith(".png"))) {
    const stem = original.path.replace(/\.png$/u, "");
    const hasAvif = [...assetPaths].some((assetPath) =>
      assetPath.startsWith(`${stem}-`) && assetPath.endsWith(".avif"));
    const hasWebp = [...assetPaths].some((assetPath) =>
      assetPath.startsWith(`${stem}-`) && assetPath.endsWith(".webp"));
    if (!hasAvif || !hasWebp) {
      violations.push(
        `${original.path} does not have both AVIF and WebP delivery variants`,
      );
    }
  }

  for (const optimized of optimizedAssets) {
    if (optimized.bytes > 256 * 1024) {
      violations.push(
        `${optimized.path} exceeds the 256 KiB responsive variant budget`,
      );
    }
  }

  return {
    violations,
    warnings,
    routePaths,
    productionVueFiles: productionSources.length,
    assets,
    originalAssets,
    optimizedAssets,
    totalAssetBytes: assets.reduce((total, asset) => total + asset.bytes, 0),
    totalOptimizedAssetBytes: optimizedAssets
      .reduce((total, asset) => total + asset.bytes, 0),
  };
}
