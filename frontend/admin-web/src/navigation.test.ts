import { describe, expect, it } from "vitest";

import { resolveStaffRedirect } from "./navigation";

describe("staff workspace redirect", () => {
  it("preserves an internal workspace path", () => {
    expect(resolveStaffRedirect("/chat/2080811896575827969?source=queue"))
      .toBe("/chat/2080811896575827969?source=queue");
  });

  it.each([
    undefined,
    null,
    "",
    "chat",
    "//malicious.example/chat",
    "https://malicious.example/chat",
    "/login",
    "/login?redirect=/chat",
  ])("falls back to the workspace home for %s", (value) => {
    expect(resolveStaffRedirect(value)).toBe("/");
  });
});
