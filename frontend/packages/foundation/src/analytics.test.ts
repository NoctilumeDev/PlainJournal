import { describe, expect, it, vi } from "vitest";

import type { ApiClient } from "./api";
import { createAnalyticsApi } from "./analytics";

describe("analytics api", () => {
  it("encodes bounded overview and reconciliation ranges", async () => {
    const request = vi.fn().mockResolvedValue({});
    const api = createAnalyticsApi({ request } as ApiClient);

    await api.overview("2026-07-01", "2026-07-24", 12);
    await api.reconciliation("2026-07-01", "2026-07-24");

    expect(request).toHaveBeenNthCalledWith(
      1,
      "/api/v1/analytics/overview?from=2026-07-01&to=2026-07-24&productLimit=12",
    );
    expect(request).toHaveBeenNthCalledWith(
      2,
      "/api/v1/analytics/admin/reconciliation?from=2026-07-01&to=2026-07-24",
    );
  });

  it("sends an audited rebuild command without changing its identity", async () => {
    const request = vi.fn().mockResolvedValue({});
    const api = createAnalyticsApi({ request } as ApiClient);
    const input = {
      commandId: "analytics-rebuild-20260724",
      reason: "修复已核实的运营投影偏差",
      from: "2026-07-01",
      to: "2026-07-24",
    };

    await api.rebuild(input);

    expect(request).toHaveBeenCalledWith(
      "/api/v1/analytics/admin/rebuild",
      {
        method: "POST",
        body: JSON.stringify(input),
      },
    );
  });
});
