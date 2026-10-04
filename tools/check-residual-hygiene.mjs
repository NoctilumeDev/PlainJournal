import { spawnSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HISTORICAL_WORKLOG_ROOT = "history/worklogs/";
const HISTORICAL_MEDIA_EXTENSIONS = new Set([
  ".avif", ".gif", ".jpeg", ".jpg", ".mp4", ".pdf", ".png", ".svg", ".webm", ".webp",
]);
const TEMPORARY_SUFFIXES = [".bak", ".orig", ".rej", ".tmp", "~"];

function normalizePath(value) {
  return value.replaceAll("\\", "/").replace(/^\.\//u, "");
}

function uniqueSorted(values) {
  return [...new Set(values.map(normalizePath))].sort();
}

export function inspectResidualHygiene({ trackedPaths, ignoredTrackedPaths = [] }) {
  const tracked = uniqueSorted(trackedPaths);
  const ignored = new Set(uniqueSorted(ignoredTrackedPaths));
  const violations = [];

  for (const file of tracked) {
    if (ignored.has(file)) {
      violations.push({
        code: "TRACKED_IGNORED_ARTIFACT",
        path: file,
        message: "tracked file matches the repository ignore policy",
      });
    }
    if (
      file.startsWith(HISTORICAL_WORKLOG_ROOT)
      && HISTORICAL_MEDIA_EXTENSIONS.has(path.posix.extname(file).toLowerCase())
    ) {
      violations.push({
        code: "HISTORICAL_CONSTRUCTION_ASSET",
        path: file,
        message: "historical construction media belongs in Git history, not the current checkout",
      });
    }
    if (TEMPORARY_SUFFIXES.some((suffix) => file.endsWith(suffix))) {
      violations.push({
        code: "TRACKED_TEMPORARY_FILE",
        path: file,
        message: "temporary or conflict-backup file is tracked",
      });
    }
  }

  return {
    trackedFiles: tracked.length,
    violations,
  };
}

function gitPaths(repositoryRoot, ...arguments_) {
  const result = spawnSync("git", ["-C", repositoryRoot, ...arguments_], {
    encoding: "buffer",
    windowsHide: true,
  });
  if (result.error) {
    throw result.error;
  }
  if (result.status !== 0) {
    throw new Error(
      `git ${arguments_.join(" ")} failed: ${result.stderr.toString("utf8").trim()}`,
    );
  }
  return result.stdout
    .toString("utf8")
    .split("\0")
    .filter(Boolean);
}

export function inspectRepository(repositoryRoot) {
  return inspectResidualHygiene({
    trackedPaths: gitPaths(repositoryRoot, "ls-files", "-z"),
    ignoredTrackedPaths: gitPaths(
      repositoryRoot,
      "ls-files",
      "-ci",
      "--exclude-standard",
      "-z",
    ),
  });
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : "";
if (invokedPath === fileURLToPath(import.meta.url)) {
  const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
  const result = inspectRepository(repositoryRoot);
  if (result.violations.length > 0) {
    console.error("Residual Hygiene: FAIL");
    for (const violation of result.violations) {
      console.error(`- ${violation.code}: ${violation.path} (${violation.message})`);
    }
    process.exitCode = 1;
  } else {
    console.log("Residual Hygiene: PASS");
    console.log(`tracked_files=${result.trackedFiles}`);
    console.log("tracked_ignored_artifacts=0");
    console.log("historical_construction_assets=0");
    console.log("tracked_temporary_files=0");
  }
}
