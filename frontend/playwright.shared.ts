import fs from "node:fs";
import path from "node:path";

import type { LaunchOptions } from "@playwright/test";

export function chromiumLaunchOptions(): LaunchOptions {
  const configured = process.env.PLAYWRIGHT_CHROME_EXECUTABLE;
  if (configured) {
    return { executablePath: configured };
  }

  if (process.platform === "win32") {
    const localChrome = path.join(
      process.env.LOCALAPPDATA ?? "",
      "Google",
      "Chrome",
      "Application",
      "chrome.exe",
    );
    if (fs.existsSync(localChrome)) {
      return { executablePath: localChrome };
    }
  }

  return {};
}
