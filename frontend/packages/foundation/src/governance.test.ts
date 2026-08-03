import { afterEach, describe, expect, it, vi } from "vitest";

import { createApiClient } from "./api";
import { createGovernanceApi } from "./governance";

function success(data: unknown): Response {
  return new Response(JSON.stringify({
    code: "OK",
    message: "success",
    data,
    timestamp: "2026-07-21T00:00:00Z",
  }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

describe("governance api", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("keeps reconciliation reads inside the selected owner domain", async () => {
    const urls: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      urls.push(String(input));
      return success([]);
    }));

    const api = createGovernanceApi(createApiClient());
    await api.reconciliationIssues("trade", "OPEN", 50);
    await api.reconciliationIssues("fulfillment", "RESOLVED", 10);

    expect(urls[0]).toContain("/trade/admin/reconciliation/issues?status=OPEN&limit=50");
    expect(urls[1]).toContain(
      "/fulfillment/admin/reconciliation/issues?status=RESOLVED&limit=10",
    );
  });
});
