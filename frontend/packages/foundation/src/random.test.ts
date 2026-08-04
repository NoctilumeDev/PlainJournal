import { afterEach, describe, expect, it, vi } from "vitest";

import { secureRandomUUID } from "./random";

describe("secureRandomUUID", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("uses randomUUID when the browser exposes it", () => {
    vi.stubGlobal("crypto", {
      randomUUID: () => "00000000-0000-4000-8000-000000000001",
    });

    expect(secureRandomUUID()).toBe("00000000-0000-4000-8000-000000000001");
  });

  it("uses getRandomValues to build an RFC 4122 v4 UUID", () => {
    vi.stubGlobal("crypto", {
      getRandomValues: (bytes: Uint8Array) => {
        bytes.fill(0xab);
        return bytes;
      },
    });

    expect(secureRandomUUID()).toBe("abababab-abab-4bab-abab-abababababab");
  });

  it("refuses to create idempotency keys without Web Crypto", () => {
    vi.stubGlobal("crypto", undefined);

    expect(() => secureRandomUUID()).toThrow("Web Crypto");
  });
});
