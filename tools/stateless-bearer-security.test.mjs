import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
async function listJavaSources(root) {
  const sources = [];
  for (const entry of await fs.readdir(root, { withFileTypes: true })) {
    const entryPath = path.join(root, entry.name);
    if (entry.isDirectory()) {
      sources.push(...await listJavaSources(entryPath));
    } else if (entry.name.endsWith(".java")) {
      sources.push(entryPath);
    }
  }
  return sources;
}

test("CSRF is disabled only for stateless Bearer JWT resource servers", async () => {
  const backendRoot = path.join(repositoryRoot, "backend");
  const javaSources = await listJavaSources(backendRoot);
  const securityConfigurations = [];
  for (const sourcePath of javaSources) {
    const source = await fs.readFile(sourcePath, "utf8");
    if (/\.csrf\([^;]*disable/u.test(source)) {
      securityConfigurations.push({
        relativePath: path.relative(repositoryRoot, sourcePath),
        source,
      });
    }
  }
  assert.equal(
    securityConfigurations.length,
    11,
    "Expected Gateway and ten business services to declare the CSRF boundary",
  );

  for (const { relativePath, source } of securityConfigurations) {
    assert.match(
      source,
      /\.csrf\([^;]*disable/u,
      `${relativePath} must explicitly declare its CSRF decision`,
    );
    assert.match(
      source,
      /\.oauth2ResourceServer\(/u,
      `${relativePath} must authenticate requests as an OAuth2 resource server`,
    );
    assert.match(
      source,
      /\.jwt\(/u,
      `${relativePath} must authenticate requests with Bearer JWTs`,
    );
    assert.ok(
      source.includes("SessionCreationPolicy.STATELESS")
        || source.includes("NoOpServerSecurityContextRepository"),
      `${relativePath} disables CSRF without declaring stateless security context handling`,
    );
    assert.doesNotMatch(
      source,
      /\.(?:formLogin|httpBasic|rememberMe)\(/u,
      `${relativePath} must not enable browser credential flows while CSRF is disabled`,
    );
  }
});
