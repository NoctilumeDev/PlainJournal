import fs from "node:fs/promises";
import path from "node:path";

function requireText(source, expected, label, violations) {
  if (!source.includes(expected)) {
    violations.push(`${label} is missing: ${expected}`);
  }
}

export function extractNginxBlock(source, declaration) {
  const declarationIndex = source.indexOf(declaration);
  if (declarationIndex < 0) {
    return "";
  }
  const openingBrace = source.indexOf("{", declarationIndex);
  if (openingBrace < 0) {
    return "";
  }
  let depth = 0;
  for (let index = openingBrace; index < source.length; index += 1) {
    if (source[index] === "{") {
      depth += 1;
    } else if (source[index] === "}") {
      depth -= 1;
      if (depth === 0) {
        return source.slice(declarationIndex, index + 1);
      }
    }
  }
  return "";
}

export async function inspectProductionDeployment(frontendRoot) {
  const violations = [];
  const deploymentRoot = path.join(frontendRoot, "deploy", "nginx");
  const nginxPath = path.join(deploymentRoot, "default.conf.template");
  const dockerfilePath = path.join(deploymentRoot, "Dockerfile");
  const composePath = path.join(deploymentRoot, "compose.yml");
  const deploymentReadmePath = path.join(deploymentRoot, "README.md");
  const environmentExamplePath = path.join(deploymentRoot, ".env.example");
  const demoPath = path.join(frontendRoot, "demo", "README.md");
  const packagePath = path.join(frontendRoot, "package.json");
  const containerFixturePath = path.join(
    frontendRoot,
    "tools",
    "production-container-fixture.ps1",
  );
  const screenshotScriptPath = path.join(
    frontendRoot,
    "tools",
    "capture-release-screenshots.mjs",
  );

  const [
    nginx,
    dockerfile,
    compose,
    deploymentReadme,
    environmentExample,
    demo,
    packageSource,
    containerFixture,
    screenshotScript,
  ] = await Promise.all([
    fs.readFile(nginxPath, "utf8"),
    fs.readFile(dockerfilePath, "utf8"),
    fs.readFile(composePath, "utf8"),
    fs.readFile(deploymentReadmePath, "utf8"),
    fs.readFile(environmentExamplePath, "utf8"),
    fs.readFile(demoPath, "utf8"),
    fs.readFile(packagePath, "utf8"),
    fs.readFile(containerFixturePath, "utf8"),
    fs.readFile(screenshotScriptPath, "utf8"),
  ]);
  const packageJson = JSON.parse(packageSource);
  const indexBlock = extractNginxBlock(nginx, "location = /index.html");
  const assetsBlock = extractNginxBlock(nginx, "location ^~ /assets/");
  const imagesBlock = extractNginxBlock(nginx, "location ^~ /images/");
  const apiBlock = extractNginxBlock(nginx, "location /api/");
  const websocketBlock = extractNginxBlock(nginx, "location /ws/");
  const fallbackBlock = extractNginxBlock(nginx, "location / {");

  requireText(
    fallbackBlock,
    "try_files $uri $uri/ /index.html;",
    "Nginx History fallback",
    violations,
  );
  requireText(
    indexBlock,
    'Cache-Control "no-store, no-cache, must-revalidate"',
    "Nginx index cache policy",
    violations,
  );
  requireText(
    assetsBlock,
    'Cache-Control "public, max-age=31536000, immutable"',
    "Nginx hashed asset cache policy",
    violations,
  );
  requireText(
    assetsBlock,
    "try_files $uri =404;",
    "Nginx missing hashed asset failure",
    violations,
  );
  requireText(
    imagesBlock,
    'Cache-Control "public, max-age=86400, stale-while-revalidate=604800"',
    "Nginx stable image cache policy",
    violations,
  );
  requireText(
    imagesBlock,
    "try_files $uri =404;",
    "Nginx missing image failure",
    violations,
  );
  requireText(
    apiBlock,
    "proxy_pass http://plainjournal_gateway;",
    "Nginx Gateway proxy",
    violations,
  );
  requireText(
    websocketBlock,
    "proxy_set_header Upgrade $http_upgrade;",
    "Nginx WebSocket upgrade",
    violations,
  );
  if (imagesBlock.includes("immutable")) {
    violations.push("Stable image URLs must not use the immutable cache directive");
  }

  const apiIndex = nginx.indexOf("location /api/");
  const websocketIndex = nginx.indexOf("location /ws/");
  const fallbackIndex = nginx.indexOf("location / {");
  if (
    apiIndex < 0
    || websocketIndex < 0
    || fallbackIndex < 0
    || apiIndex > fallbackIndex
    || websocketIndex > fallbackIndex
  ) {
    violations.push("Nginx API and WebSocket boundaries must precede SPA fallback");
  }

  requireText(
    dockerfile,
    "FROM node:24-alpine AS workspace",
    "Frontend build runtime",
    violations,
  );
  requireText(
    dockerfile,
    "FROM nginx:1.28-alpine AS runtime",
    "Frontend static runtime",
    violations,
  );
  requireText(
    dockerfile,
    "FROM runtime AS storefront",
    "Storefront image target",
    violations,
  );
  requireText(
    dockerfile,
    "FROM runtime AS admin",
    "Admin image target",
    violations,
  );
  requireText(
    dockerfile,
    "HEALTHCHECK",
    "Frontend container health check",
    violations,
  );
  for (const marker of [
    "org.opencontainers.image.created",
    "org.opencontainers.image.revision",
    "org.opencontainers.image.source",
    "org.opencontainers.image.version",
    'org.opencontainers.image.title="PlainJournal Storefront"',
    'org.opencontainers.image.title="PlainJournal Admin"',
  ]) {
    requireText(
      dockerfile,
      marker,
      "Frontend OCI image metadata",
      violations,
    );
  }

  requireText(
    compose,
    "name: plainjournal-frontend",
    "Compose project identity",
    violations,
  );
  requireText(
    compose,
    '"127.0.0.1:18300:8080"',
    "Storefront loopback port",
    violations,
  );
  requireText(
    compose,
    '"127.0.0.1:18301:8080"',
    "Admin loopback port",
    violations,
  );
  requireText(
    compose,
    "${PLAINJOURNAL_IMAGE_PREFIX:-plainjournal}/storefront-web:${PLAINJOURNAL_FRONTEND_TAG:-local}",
    "Storefront release image",
    violations,
  );
  requireText(
    compose,
    "${PLAINJOURNAL_IMAGE_PREFIX:-plainjournal}/admin-web:${PLAINJOURNAL_FRONTEND_TAG:-local}",
    "Admin release image",
    violations,
  );
  for (const marker of [
    "PLAINJOURNAL_OCI_CREATED:",
    "PLAINJOURNAL_OCI_REVISION:",
    "PLAINJOURNAL_OCI_SOURCE:",
    "PLAINJOURNAL_OCI_VERSION:",
    '${PLAINJOURNAL_GATEWAY_PORT:-18000}',
  ]) {
    requireText(
      compose,
      marker,
      "Compose release metadata",
      violations,
    );
  }
  for (const marker of [
    "docker compose up -d --no-build",
    "前端回退不回滚数据库迁移",
    "2026-08-03 已",
  ]) {
    requireText(
      deploymentReadme,
      marker,
      "Production deployment guide",
      violations,
    );
  }
  requireText(
    environmentExample,
    "PLAINJOURNAL_FRONTEND_TAG=local",
    "Production deployment environment example",
    violations,
  );
  requireText(
    environmentExample,
    "PLAINJOURNAL_OCI_SOURCE=https://github.com/NoctilumeDev/PlainJournal",
    "Production source repository",
    violations,
  );

  for (const marker of [
    "不是真实生产账号",
    "reader@example.com",
    "ReaderPass123",
    "admin@example.com",
    "AdminPass123",
    "错误密码返回 401",
  ]) {
    requireText(demo, marker, "Demo account guide", violations);
  }

  for (const script of [
    "demo:start",
    "demo:status",
    "demo:stop",
    "check:production-deploy",
    "e2e:production",
    "container:verify",
    "container:status",
    "container:stop",
    "release:screenshots",
  ]) {
    if (typeof packageJson.scripts?.[script] !== "string") {
      violations.push(`frontend/package.json is missing script ${script}`);
    }
  }
  for (const marker of [
    "docker compose up -d --no-build --pull never",
    "org.opencontainers.image.version",
    "Cache-Control",
    "missing asset",
    "State.Health.Status",
  ]) {
    requireText(
      containerFixture,
      marker,
      "Production container verification",
      violations,
    );
  }
  for (const marker of [
    "storefront-home.jpg",
    "storefront-product.jpg",
    "admin-governance.jpg",
    "http://127.0.0.1:18300",
    "http://127.0.0.1:18301",
  ]) {
    requireText(
      screenshotScript,
      marker,
      "Release screenshot capture",
      violations,
    );
  }

  return {
    violations,
    blocks: {
      indexBlock,
      assetsBlock,
      imagesBlock,
      apiBlock,
      websocketBlock,
      fallbackBlock,
    },
    paths: {
      nginxPath,
      dockerfilePath,
      composePath,
      deploymentReadmePath,
      environmentExamplePath,
      demoPath,
      containerFixturePath,
      screenshotScriptPath,
    },
  };
}
