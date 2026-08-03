import path from "node:path";

import {
  fixtureStatus,
  frontendRoot,
  startFixture,
  stopFixture,
} from "./fixture-runtime.mjs";

const action = process.argv[2] ?? "status";
const statePath = path.join(frontendRoot, ".run", "production-fixture.json");

if (!["start", "status", "stop"].includes(action)) {
  throw new Error("Usage: node tools/production-fixture.mjs start|status|stop [--skip-build]");
}

if (action === "start") {
  const state = await startFixture({
    mode: "production",
    statePath,
    skipBuild: process.argv.includes("--skip-build"),
    logPrefix: "production",
  });
  console.log("PlainJournal production fixture started.");
  for (const [name, url] of Object.entries(state.urls)) {
    console.log(`- ${name}: ${url}`);
  }
} else if (action === "stop") {
  const result = await stopFixture(statePath);
  console.log(
    result.missing
      ? "PlainJournal production fixture was not running."
      : `Stopped: ${result.stopped.join(", ")}`,
  );
} else {
  const status = await fixtureStatus(statePath);
  if (!status.active) {
    console.log("PlainJournal production fixture is not running.");
  } else {
    console.log(`PlainJournal ${status.mode} fixture is running.`);
    for (const processRecord of status.processes) {
      console.log(
        `- ${processRecord.name}: PID ${processRecord.pid} `
        + `(${processRecord.active ? "active" : "exited"})`,
      );
    }
  }
}
