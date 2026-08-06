import crypto from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const CURRENT_NUMBER_RANGE = [0, 25];
const REQUIRED_CURRENT_FILES = [
  "00-project-master-plan.md",
  "01-product-scope.md",
  "02-service-architecture.md",
  "03-core-state-machines.md",
  "04-data-ownership.md",
  "05-consistency-strategy.md",
  "06-version-matrix.md",
  "07-local-development-network.md",
  "08-identity-security.md",
  "09-redis-traffic-protection.md",
  "10-catalog-service.md",
  "11-inventory-service.md",
  "12-trade-service.md",
  "13-payment-service.md",
  "14-fulfillment-service.md",
  "15-marketing-service.md",
  "16-after-sale-refund.md",
  "17-technology-adoption-matrix.md",
  "18-observability-and-alerting.md",
  "19-compensation-governance.md",
  "20-payment-reconciliation.md",
  "21-inventory-reconciliation.md",
  "22-synchronous-call-resilience.md",
  "23-trade-scheduling-isolation.md",
  "24-distributed-tracing.md",
  "25-trade-fulfillment-reconciliation.md",
  "core-smoke.md",
  "project-history.md",
  "reference-baseline-and-pro-boundary.md",
  "verification-index.md",
  "verification-summary.md",
];
const REQUIRED_EVIDENCE_FILES = [
  "m0-m8-three-layer-acceptance-20260728.md",
  "v1.0.2-engineering-acceptance-20260804.md",
];
const RELATED_DOCUMENT_FILES = [
  "README.md",
  "backend/README.md",
  "frontend/README.md",
  "deploy/docker/README.md",
];

function expectedNumbers([minimum, maximum]) {
  return Array.from(
    { length: maximum - minimum + 1 },
    (_, index) => minimum + index,
  );
}

function numberedFiles(names) {
  return names
    .map((name) => {
      const match = name.match(/^(\d+)-.*\.md$/u);
      return match ? { name, number: Number(match[1]) } : null;
    })
    .filter(Boolean);
}

function compareNumbers(actual, expected, label, violations) {
  const actualSet = new Set(actual);
  for (const number of expected) {
    if (!actualSet.has(number)) {
      violations.push(`${label} is missing document number ${number}`);
    }
  }
  for (const number of actualSet) {
    if (!expected.includes(number)) {
      violations.push(`${label} contains unexpected document number ${number}`);
    }
  }
  if (actual.length !== actualSet.size) {
    violations.push(`${label} contains duplicate document numbers`);
  }
}

function markdownReferences(source, target) {
  const escaped = target.replace(/[.*+?^${}()|[\]\\]/gu, "\\$&");
  return new RegExp(`\\]\\((?:\\./)?${escaped}(?:[#?][^)]*)?\\)`, "u").test(source);
}

async function listMarkdownFiles(directory) {
  const entries = await fs.readdir(directory, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const fullPath = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      files.push(...await listMarkdownFiles(fullPath));
    } else if (entry.isFile() && entry.name.endsWith(".md")) {
      files.push(fullPath);
    }
  }
  return files;
}

export async function inspectDocumentationStructure(repositoryRoot) {
  const violations = [];
  const docsRoot = path.join(repositoryRoot, "docs");
  const rootEntries = await fs.readdir(docsRoot, { withFileTypes: true });
  const rootMarkdown = rootEntries
    .filter((entry) => entry.isFile() && entry.name.endsWith(".md"))
    .map((entry) => entry.name)
    .sort();

  compareNumbers(
    numberedFiles(rootMarkdown).map(({ number }) => number),
    expectedNumbers(CURRENT_NUMBER_RANGE),
    "docs root",
    violations,
  );

  for (const requiredFile of REQUIRED_CURRENT_FILES) {
    if (!rootMarkdown.includes(requiredFile)) {
      violations.push(`docs root is missing ${requiredFile}`);
    }
  }

  const docsIndex = await fs.readFile(path.join(docsRoot, "README.md"), "utf8");
  for (const requiredFile of REQUIRED_CURRENT_FILES) {
    if (!markdownReferences(docsIndex, requiredFile)) {
      violations.push(`docs/README.md does not index ${requiredFile}`);
    }
  }

  const evidenceRoot = path.join(docsRoot, "evidence");
  const evidenceEntries = await fs.readdir(evidenceRoot, { withFileTypes: true });
  const evidenceMarkdown = evidenceEntries
    .filter((entry) => entry.isFile() && entry.name.endsWith(".md"))
    .map((entry) => entry.name)
    .sort();
  const expectedEvidence = ["README.md", ...REQUIRED_EVIDENCE_FILES].sort();
  if (
    evidenceMarkdown.length !== expectedEvidence.length
    || evidenceMarkdown.some((name, index) => name !== expectedEvidence[index])
  ) {
    violations.push(
      `docs/evidence must contain only: ${expectedEvidence.join(", ")}`,
    );
  }
  const evidenceIndex = await fs.readFile(path.join(evidenceRoot, "README.md"), "utf8");
  for (const requiredFile of REQUIRED_EVIDENCE_FILES) {
    if (!markdownReferences(evidenceIndex, requiredFile)) {
      violations.push(`docs/evidence/README.md does not index ${requiredFile}`);
    }
  }

  const archiveRoot = path.join(docsRoot, "archive");
  try {
    const archivedMarkdown = await listMarkdownFiles(archiveRoot);
    if (archivedMarkdown.length > 0) {
      violations.push("docs/archive contains stage logs; use Git history instead");
    }
  } catch (error) {
    if (error.code !== "ENOENT") {
      throw error;
    }
  }

  const currentFiles = [
    ...rootMarkdown.map((name) => path.join(docsRoot, name)),
    ...await listMarkdownFiles(path.join(docsRoot, "quality")),
  ];
  const relatedFiles = [];
  for (const relativePath of RELATED_DOCUMENT_FILES) {
    const file = path.join(repositoryRoot, relativePath);
    try {
      await fs.access(file);
      relatedFiles.push(file);
    } catch (error) {
      if (error.code !== "ENOENT") {
        throw error;
      }
    }
  }
  for (const file of [...currentFiles, ...relatedFiles]) {
    const relative = path.relative(repositoryRoot, file).replaceAll("\\", "/");
    const source = await fs.readFile(file, "utf8");
    if (
      /(?:^|[\s`"'(])[A-Za-z]:[\\/]/mu.test(source)
      || /(?:^|[\s`"'(])C:\/Users\//imu.test(source)
    ) {
      violations.push(`${relative} contains a machine-specific absolute path`);
    }
    if (
      currentFiles.includes(file)
      && path.basename(file) !== "verification-summary.md"
      && /\b\d+\s+(?:tests|个测试)\b/iu.test(source)
    ) {
      violations.push(`${relative} duplicates mutable test counts`);
    }
    if (
      /(?:\.\.\/)*docs\/(?:2[6-9]|[3-9]\d|10[0-6])-[^\s`)]*\.md/iu.test(source)
      || /docs\/archive\//iu.test(source)
    ) {
      violations.push(`${relative} references a removed stage-log document`);
    }
  }

  const allDocs = await listMarkdownFiles(docsRoot);
  const hashes = new Map();
  for (const file of allDocs) {
    const source = await fs.readFile(file);
    const hash = crypto.createHash("sha256").update(source).digest("hex");
    const relative = path.relative(repositoryRoot, file).replaceAll("\\", "/");
    const previous = hashes.get(hash);
    if (previous) {
      violations.push(`${relative} duplicates ${previous}`);
    } else {
      hashes.set(hash, relative);
    }
  }

  return {
    violations,
    currentDocuments: rootMarkdown.length,
    evidenceDocuments: evidenceMarkdown.length - 1,
    totalDocuments: allDocs.length,
  };
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : "";
if (invokedPath === fileURLToPath(import.meta.url)) {
  const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
  const result = await inspectDocumentationStructure(repositoryRoot);
  if (result.violations.length > 0) {
    console.error("Documentation structure violations:");
    for (const violation of result.violations) {
      console.error(`- ${violation}`);
    }
    process.exitCode = 1;
  } else {
    console.log(
      `Documentation structure passed: ${result.currentDocuments} current documents, `
      + `${result.evidenceDocuments} evidence snapshots, `
      + `${result.totalDocuments} Markdown documents in total.`,
    );
  }
}
