import fs from "node:fs/promises";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

function listMarkdownFiles(repositoryRoot) {
  const result = spawnSync(
    "git",
    [
      "-c",
      "core.quotepath=false",
      "ls-files",
      "-z",
      "--cached",
      "--others",
      "--exclude-standard",
      "--",
      "*.md",
    ],
    {
      cwd: repositoryRoot,
      encoding: "utf8",
      windowsHide: true,
    },
  );
  if (result.status !== 0) {
    throw new Error(result.stderr || "git ls-files failed");
  }
  return result.stdout.split("\0").filter(Boolean).sort();
}

function normalizeTarget(rawTarget) {
  let target = rawTarget.trim();
  if (target.startsWith("<") && target.endsWith(">")) {
    target = target.slice(1, -1);
  }
  target = target.split("#", 1)[0].split("?", 1)[0];
  try {
    return decodeURIComponent(target);
  } catch {
    return target;
  }
}

export async function inspectMarkdownLinks(repositoryRoot) {
  const violations = [];
  const markdownFiles = listMarkdownFiles(repositoryRoot);
  let checkedLinks = 0;

  for (const markdownFile of markdownFiles) {
    const absoluteFile = path.join(repositoryRoot, markdownFile);
    const source = await fs.readFile(absoluteFile, "utf8");
    const matches = source.matchAll(
      /!?\[[^\]]*\]\((<[^>]+>|[^)\s]+)(?:\s+["'][^"']*["'])?\)/gu,
    );

    for (const match of matches) {
      const rawTarget = match[1];
      if (
        rawTarget.startsWith("#")
        || /^(?:https?:|mailto:|data:)/iu.test(rawTarget)
      ) {
        continue;
      }

      const target = normalizeTarget(rawTarget);
      if (!target) {
        continue;
      }
      checkedLinks += 1;
      const resolved = path.resolve(path.dirname(absoluteFile), target);
      try {
        await fs.access(resolved);
      } catch {
        violations.push(`${markdownFile} -> ${rawTarget}`);
      }
    }
  }

  return { violations, markdownFiles: markdownFiles.length, checkedLinks };
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : "";
if (invokedPath === fileURLToPath(import.meta.url)) {
  const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
  const result = await inspectMarkdownLinks(repositoryRoot);
  if (result.violations.length > 0) {
    console.error("Broken relative Markdown links:");
    for (const violation of result.violations) {
      console.error(`- ${violation}`);
    }
    process.exitCode = 1;
  } else {
    console.log(
      `Markdown links passed: ${result.checkedLinks} relative links across `
      + `${result.markdownFiles} files.`,
    );
  }
}
