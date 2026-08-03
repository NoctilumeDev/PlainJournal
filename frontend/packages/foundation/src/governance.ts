import type { ApiClient } from "./api";

export type ReconciliationDomain = "trade" | "payment" | "inventory" | "fulfillment";
export type ReconciliationStatus = "OPEN" | "RESOLVED";

export interface ReconciliationIssue {
  domain: string;
  referenceNo: string;
  issueType: string;
  status: ReconciliationStatus;
  occurrences: number;
  firstDetectedAt: string;
  lastDetectedAt: string;
  resolvedAt: string | null;
}

export interface GovernanceApi {
  reconciliationIssues(
    domain: ReconciliationDomain,
    status?: ReconciliationStatus,
    limit?: number,
  ): Promise<ReconciliationIssue[]>;
}

export function createGovernanceApi(client: ApiClient): GovernanceApi {
  return {
    reconciliationIssues(domain, status = "OPEN", limit = 50) {
      const query = new URLSearchParams({
        status,
        limit: String(limit),
      });
      return client.request<ReconciliationIssue[]>(
        `/api/v1/${domain}/admin/reconciliation/issues?${query.toString()}`,
      );
    },
  };
}
