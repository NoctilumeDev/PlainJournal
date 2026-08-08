import assert from "node:assert/strict";
import test from "node:test";

import {
  parentVersionFromPom,
  projectVersionFromPom,
  validateFrontendVersionStatement,
  validateReleaseStatus,
  versionFromReleaseTag,
} from "./check-release-version.mjs";

test("accepts only stable vMAJOR.MINOR.PATCH release tags", () => {
  assert.equal(versionFromReleaseTag("v1.0.3"), "1.0.3");
  assert.throws(() => versionFromReleaseTag("v1.0.3-rc.1"), /vMAJOR\.MINOR\.PATCH/u);
  assert.throws(() => versionFromReleaseTag("v1.0.3-extra"), /vMAJOR\.MINOR\.PATCH/u);
});

test("requires a released verification baseline when validating a tag", () => {
  assert.deepEqual(validateReleaseStatus("release-candidate"), []);
  assert.deepEqual(validateReleaseStatus("released", "v1.0.7"), []);
  assert.match(
    validateReleaseStatus("release-candidate", "v1.0.7")[0],
    /requires status released/u,
  );
});

test("keeps candidate and released README statements aligned with the baseline", () => {
  assert.deepEqual(
    validateFrontendVersionStatement("`v1.0.7` 验收候选", "v1.0.7", "release-candidate"),
    [],
  );
  assert.deepEqual(
    validateFrontendVersionStatement("`v1.0.7` 正式发布", "v1.0.7", "released"),
    [],
  );
  assert.match(
    validateFrontendVersionStatement("`v1.0.7` 正式发布", "v1.0.7", "release-candidate")[0],
    /candidate version/u,
  );
});

test("reads the root project version outside the Spring Boot parent", () => {
  const pom = `
    <project>
      <parent><version>3.5.16</version></parent>
      <artifactId>plainjournal-backend</artifactId>
      <version>1.0.3</version>
    </project>
  `;
  assert.equal(projectVersionFromPom(pom, "pom.xml"), "1.0.3");
});

test("reads only the PlainJournal parent version from child modules", () => {
  const pom = `
    <project>
      <parent>
        <groupId>com.ecommerce.platform</groupId>
        <artifactId>plainjournal-backend</artifactId>
        <version>1.0.3</version>
      </parent>
    </project>
  `;
  assert.equal(parentVersionFromPom(pom, "catalog/pom.xml"), "1.0.3");
});
