import assert from "node:assert/strict";
import test from "node:test";

import {
  parentVersionFromPom,
  projectVersionFromPom,
  versionFromReleaseTag,
} from "./check-release-version.mjs";

test("accepts only stable vMAJOR.MINOR.PATCH release tags", () => {
  assert.equal(versionFromReleaseTag("v1.0.3"), "1.0.3");
  assert.throws(() => versionFromReleaseTag("v1.0.3-rc.1"), /vMAJOR\.MINOR\.PATCH/u);
  assert.throws(() => versionFromReleaseTag("v1.0.3-extra"), /vMAJOR\.MINOR\.PATCH/u);
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
