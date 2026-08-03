import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const SERVICE_DOMAINS = new Set([
  "analytics",
  "catalog",
  "chat",
  "fulfillment",
  "identity",
  "inventory",
  "marketing",
  "notification",
  "payment",
  "trade",
]);

const COMMON_PACKAGES = new Set([
  "api",
  "id",
  "idempotency",
  "observability",
  "security",
  "transaction",
]);

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

function relative(repositoryRoot, file) {
  return path.relative(repositoryRoot, file).replaceAll("\\", "/");
}

function collectDomainImports(source) {
  return [...source.matchAll(
    /^import\s+com\.ecommerce\.(analytics|catalog|chat|fulfillment|identity|inventory|marketing|notification|payment|trade)(?:[.;])/gmu,
  )].map((match) => match[1]);
}

function collectDependencyArtifacts(source) {
  const artifacts = [];
  for (const match of source.matchAll(/<dependency>([\s\S]*?)<\/dependency>/gu)) {
    const artifact = match[1].match(/<artifactId>([^<]+)<\/artifactId>/u)?.[1];
    if (artifact) {
      artifacts.push(artifact.trim());
    }
  }
  return artifacts;
}

export async function inspectBackendBoundaries(repositoryRoot) {
  const backendRoot = path.join(repositoryRoot, "backend");
  const violations = [];

  for (const domain of SERVICE_DOMAINS) {
    const serviceRoot = path.join(
      backendRoot,
      "services",
      `${domain}-service`,
      "src",
      "main",
      "java",
    );
    const javaFiles = await listFiles(serviceRoot, (file) => file.endsWith(".java"));

    for (const javaFile of javaFiles) {
      const source = await fs.readFile(javaFile, "utf8");
      for (const importedDomain of collectDomainImports(source)) {
        if (importedDomain !== domain) {
          violations.push(
            `${relative(repositoryRoot, javaFile)} imports sibling service package com.ecommerce.${importedDomain}`,
          );
        }
      }

      if (
        javaFile.endsWith("Controller.java")
        && /import\s+com\.ecommerce\.[^.]+\.infrastructure\.persistence\.mapper\./u.test(source)
      ) {
        violations.push(
          `${relative(repositoryRoot, javaFile)} imports a persistence mapper from a controller`,
        );
      }
    }

    const pomPath = path.join(backendRoot, "services", `${domain}-service`, "pom.xml");
    const pom = await fs.readFile(pomPath, "utf8");
    for (const artifact of collectDependencyArtifacts(pom)) {
      if (
        artifact.endsWith("-service")
        && artifact !== `${domain}-service`
        && SERVICE_DOMAINS.has(artifact.replace(/-service$/u, ""))
      ) {
        violations.push(
          `${relative(repositoryRoot, pomPath)} depends on sibling service artifact ${artifact}`,
        );
      }
    }
  }

  const gatewayRoot = path.join(
    backendRoot,
    "ecommerce-gateway",
    "src",
    "main",
    "java",
  );
  for (const javaFile of await listFiles(gatewayRoot, (file) => file.endsWith(".java"))) {
    const source = await fs.readFile(javaFile, "utf8");
    for (const importedDomain of collectDomainImports(source)) {
      violations.push(
        `${relative(repositoryRoot, javaFile)} imports service implementation package com.ecommerce.${importedDomain}`,
      );
    }
  }

  const commonRoot = path.join(
    backendRoot,
    "platform-common",
    "src",
    "main",
    "java",
    "com",
    "ecommerce",
    "platform",
    "common",
  );
  const commonFiles = await listFiles(commonRoot, (file) => file.endsWith(".java"));
  for (const javaFile of commonFiles) {
    const source = await fs.readFile(javaFile, "utf8");
    const commonRelative = path.relative(commonRoot, javaFile);
    const topPackage = commonRelative.split(path.sep)[0];

    if (!COMMON_PACKAGES.has(topPackage)) {
      violations.push(
        `${relative(repositoryRoot, javaFile)} uses unapproved platform-common package ${topPackage}`,
      );
    }
    if (
      /(?:jakarta\.persistence|org\.apache\.ibatis|com\.baomidou\.mybatisplus\.annotation|org\.springframework\.data\.)/u.test(source)
      || /@(Entity|Table|TableName|Mapper|Repository)\b/u.test(source)
    ) {
      violations.push(
        `${relative(repositoryRoot, javaFile)} introduces persistence concerns into platform-common`,
      );
    }
    for (const importedDomain of collectDomainImports(source)) {
      violations.push(
        `${relative(repositoryRoot, javaFile)} imports service package com.ecommerce.${importedDomain}`,
      );
    }
  }

  return {
    violations,
    checkedServices: SERVICE_DOMAINS.size,
    checkedCommonFiles: commonFiles.length,
  };
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : "";
if (invokedPath === fileURLToPath(import.meta.url)) {
  const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
  const result = await inspectBackendBoundaries(repositoryRoot);

  if (result.violations.length > 0) {
    console.error("Backend architecture boundary violations:");
    for (const violation of result.violations) {
      console.error(`- ${violation}`);
    }
    process.exitCode = 1;
  } else {
    console.log(
      `Backend architecture boundaries passed: ${result.checkedServices} services and `
      + `${result.checkedCommonFiles} platform-common sources checked.`,
    );
  }
}
