import fsSync from "node:fs";
import fs from "node:fs/promises";
import net from "node:net";
import path from "node:path";
import { spawn, spawnSync } from "node:child_process";
import { once } from "node:events";
import { fileURLToPath } from "node:url";

export const frontendRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const runRoot = path.join(frontendRoot, ".run");
const isWindows = process.platform === "win32";

function pnpmCommand() {
  return isWindows ? "pnpm.cmd" : "pnpm";
}

function processExists(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

function runForeground(command, args, options = {}) {
  let executable = command;
  let executableArguments = args;
  if (isWindows && command.endsWith(".cmd")) {
    executable = process.env.ComSpec ?? "cmd.exe";
    executableArguments = ["/d", "/s", "/c", [command, ...args].join(" ")];
  }

  const result = spawnSync(executable, executableArguments, {
    cwd: frontendRoot,
    env: { ...process.env, ...options.env },
    stdio: "inherit",
    windowsHide: true,
  });
  if (result.error) {
    throw result.error;
  }
  if (result.status !== 0) {
    throw new Error(`${command} ${args.join(" ")} failed with exit code ${result.status}`);
  }
}

async function portAvailable(port) {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.unref();
    server.once("error", (error) => {
      if (error.code === "EADDRINUSE") {
        resolve(false);
      } else {
        reject(error);
      }
    });
    server.listen({ host: "127.0.0.1", port }, () => {
      server.close(() => resolve(true));
    });
  });
}

async function assertPortsAvailable(ports) {
  const occupied = [];
  for (const port of ports) {
    if (!await portAvailable(port)) {
      occupied.push(port);
    }
  }
  if (occupied.length > 0) {
    throw new Error(
      `Fixture ports are already occupied: ${occupied.join(", ")}. `
      + "No existing listener was stopped.",
    );
  }
}

async function waitForUrl(url, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  let lastError = "no response";
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url, {
        signal: AbortSignal.timeout(2_000),
      });
      if (response.status >= 200 && response.status < 500) {
        return;
      }
      lastError = `HTTP ${response.status}`;
    } catch (error) {
      lastError = error.message;
    }
    await new Promise((resolve) => setTimeout(resolve, 300));
  }
  throw new Error(`Timed out waiting for ${url}: ${lastError}`);
}

async function writeState(statePath, state) {
  await fs.mkdir(path.dirname(statePath), { recursive: true });
  await fs.writeFile(statePath, `${JSON.stringify(state, null, 2)}\n`, "utf8");
}

export async function readFixtureState(statePath) {
  try {
    return JSON.parse(await fs.readFile(statePath, "utf8"));
  } catch (error) {
    if (error?.code === "ENOENT") {
      return null;
    }
    throw error;
  }
}

async function startProcess(
  name,
  command,
  args,
  env,
  logPrefix,
  workingDirectory = frontendRoot,
) {
  fsSync.mkdirSync(runRoot, { recursive: true });
  const stdoutPath = path.join(runRoot, `${logPrefix}-${name}.log`);
  const stderrPath = path.join(runRoot, `${logPrefix}-${name}.err.log`);
  const stdout = fsSync.openSync(stdoutPath, "w");
  const stderr = fsSync.openSync(stderrPath, "w");
  const child = spawn(command, args, {
    cwd: workingDirectory,
    env: { ...process.env, ...env },
    detached: true,
    stdio: ["ignore", stdout, stderr],
    windowsHide: true,
  });
  fsSync.closeSync(stdout);
  fsSync.closeSync(stderr);
  await Promise.race([
    once(child, "spawn"),
    once(child, "error").then(([error]) => Promise.reject(error)),
  ]);
  child.unref();
  return {
    name,
    pid: child.pid,
    markers: [name, ...args.filter((arg) => !arg.startsWith("--"))],
    stdoutPath,
    stderrPath,
  };
}

function readProcessCommand(pid) {
  if (!processExists(pid)) {
    return "";
  }
  if (isWindows) {
    const script = [
      `$process = Get-CimInstance Win32_Process -Filter "ProcessId = ${pid}" `,
      "-ErrorAction SilentlyContinue;",
      "if ($process) { $process.CommandLine }",
    ].join("");
    const result = spawnSync(
      "powershell.exe",
      ["-NoProfile", "-NonInteractive", "-Command", script],
      { encoding: "utf8", windowsHide: true },
    );
    return result.stdout?.trim() ?? "";
  }

  try {
    return fsSync.readFileSync(`/proc/${pid}/cmdline`, "utf8").replaceAll("\0", " ");
  } catch {
    const result = spawnSync("ps", ["-p", String(pid), "-o", "command="], {
      encoding: "utf8",
    });
    return result.stdout?.trim() ?? "";
  }
}

function commandMatches(processRecord, commandLine) {
  if (!commandLine) {
    return false;
  }
  const normalized = commandLine.toLowerCase().replaceAll("\\", "/");
  return processRecord.markers.some((marker) =>
    marker.length >= 4 && normalized.includes(marker.toLowerCase().replaceAll("\\", "/")));
}

async function waitForExit(pid, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (!processExists(pid)) {
      return true;
    }
    await new Promise((resolve) => setTimeout(resolve, 150));
  }
  return !processExists(pid);
}

async function stopProcess(processRecord) {
  if (!processExists(processRecord.pid)) {
    return;
  }
  const commandLine = readProcessCommand(processRecord.pid);
  if (!commandMatches(processRecord, commandLine)) {
    throw new Error(
      `Refusing to stop PID ${processRecord.pid}; it no longer matches owned process `
      + `${processRecord.name}.`,
    );
  }

  if (isWindows) {
    const result = spawnSync(
      "taskkill",
      ["/PID", String(processRecord.pid), "/T", "/F"],
      { encoding: "utf8", windowsHide: true },
    );
    if (result.status !== 0 && processExists(processRecord.pid)) {
      throw new Error(result.stderr || result.stdout || `taskkill failed for ${processRecord.pid}`);
    }
    return;
  }

  try {
    process.kill(-processRecord.pid, "SIGTERM");
  } catch (error) {
    if (error.code !== "ESRCH") {
      throw error;
    }
  }
  if (!await waitForExit(processRecord.pid, 3_000)) {
    process.kill(-processRecord.pid, "SIGKILL");
  }
}

export async function stopFixture(statePath) {
  const state = await readFixtureState(statePath);
  if (!state) {
    return { stopped: [], missing: true };
  }

  const stopped = [];
  const failures = [];
  for (const processRecord of [...state.processes].reverse()) {
    try {
      await stopProcess(processRecord);
      stopped.push(processRecord.name);
    } catch (error) {
      failures.push(error.message);
    }
  }

  if (failures.length > 0) {
    throw new Error(failures.join("\n"));
  }
  await fs.rm(statePath, { force: true });
  return { stopped, missing: false };
}

export async function fixtureStatus(statePath) {
  const state = await readFixtureState(statePath);
  if (!state) {
    return { active: false, processes: [] };
  }
  const processes = state.processes.map((processRecord) => ({
    ...processRecord,
    active: processExists(processRecord.pid),
  }));
  return {
    active: processes.some((processRecord) => processRecord.active),
    mode: state.mode,
    urls: state.urls,
    processes,
  };
}

export async function startFixture({
  mode,
  statePath,
  storefront = true,
  admin = true,
  skipBuild = false,
  logPrefix = mode,
}) {
  const existing = await fixtureStatus(statePath);
  if (existing.active) {
    throw new Error(`Fixture state is already active: ${statePath}`);
  }
  if (existing.processes.length > 0) {
    await fs.rm(statePath, { force: true });
  }

  const production = mode === "production";
  const ports = [18090];
  if (storefront) {
    ports.push(production ? 18300 : 18200);
  }
  if (admin) {
    ports.push(production ? 18301 : 18201);
  }
  await assertPortsAvailable(ports);

  if (production && !skipBuild) {
    runForeground(pnpmCommand(), ["build"]);
  }

  const state = {
    schemaVersion: 1,
    mode,
    startedAt: new Date().toISOString(),
    frontendRoot,
    urls: {},
    processes: [],
  };
  await writeState(statePath, state);

  try {
    state.processes.push(await startProcess(
      "mock-api",
      process.execPath,
      ["e2e/mock-api.mjs"],
      { PLAIN_JOURNAL_MOCK_API_PORT: "18090" },
      logPrefix,
    ));
    await writeState(statePath, state);
    await waitForUrl("http://127.0.0.1:18090/api/v1/identity/me", 20_000);

    const proxyEnvironment = {
      VITE_API_PROXY_TARGET: "http://127.0.0.1:18090",
    };
    if (storefront) {
      const port = production ? 18300 : 18200;
      const viteCli = path.join(
        frontendRoot,
        "storefront-web",
        "node_modules",
        "vite",
        "bin",
        "vite.js",
      );
      state.processes.push(await startProcess(
        "storefront",
        process.execPath,
        production
          ? [viteCli, "preview", "--host", "127.0.0.1", "--port", String(port), "--strictPort"]
          : [viteCli, "--host", "127.0.0.1", "--port", String(port), "--strictPort"],
        proxyEnvironment,
        logPrefix,
        path.join(frontendRoot, "storefront-web"),
      ));
      state.urls.storefront = `http://127.0.0.1:${port}`;
      await writeState(statePath, state);
      await waitForUrl(
        production
          ? `${state.urls.storefront}/products/2079000000000000001`
          : `${state.urls.storefront}/login`,
        30_000,
      );
    }

    if (admin) {
      const port = production ? 18301 : 18201;
      const viteCli = path.join(
        frontendRoot,
        "admin-web",
        "node_modules",
        "vite",
        "bin",
        "vite.js",
      );
      state.processes.push(await startProcess(
        "admin",
        process.execPath,
        production
          ? [viteCli, "preview", "--host", "127.0.0.1", "--port", String(port), "--strictPort"]
          : [viteCli, "--host", "127.0.0.1", "--port", String(port), "--strictPort"],
        proxyEnvironment,
        logPrefix,
        path.join(frontendRoot, "admin-web"),
      ));
      state.urls.admin = `http://127.0.0.1:${port}`;
      await writeState(statePath, state);
      await waitForUrl(
        production ? `${state.urls.admin}/governance` : `${state.urls.admin}/login`,
        30_000,
      );
    }

    return state;
  } catch (error) {
    await stopFixture(statePath).catch(() => {});
    throw error;
  }
}

export function runPnpm(args, env = {}) {
  runForeground(pnpmCommand(), args, { env });
}
