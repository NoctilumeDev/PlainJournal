export function formatMoney(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === "") {
    return "—";
  }
  const amount = typeof value === "number" ? value : Number(value);
  if (!Number.isFinite(amount)) {
    return "—";
  }
  return new Intl.NumberFormat("zh-CN", {
    style: "currency",
    currency: "CNY",
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(amount);
}

export function multiplyMoney(value: string | number, quantity: number): string {
  if (!Number.isSafeInteger(quantity) || quantity < 0) {
    throw new RangeError("quantity must be a non-negative safe integer");
  }
  return centsToMoney(moneyToCents(value) * BigInt(quantity));
}

export function sumMoney(values: Array<string | number>): string {
  return centsToMoney(values.reduce(
    (total, value) => total + moneyToCents(value),
    0n,
  ));
}

function moneyToCents(value: string | number): bigint {
  const normalized = String(value).trim();
  const match = /^(\d+)(?:\.(\d{1,2}))?$/.exec(normalized);
  if (!match?.[1]) {
    throw new RangeError("money must be a non-negative decimal with at most two fraction digits");
  }
  const fraction = (match[2] ?? "").padEnd(2, "0");
  return BigInt(match[1]) * 100n + BigInt(fraction || "0");
}

function centsToMoney(cents: bigint): string {
  const whole = cents / 100n;
  const fraction = (cents % 100n).toString().padStart(2, "0");
  return `${whole}.${fraction}`;
}

export interface SpecificationEntry {
  label: string;
  value: string;
}

export function parseSpecification(specJson: string): SpecificationEntry[] {
  try {
    const parsed: unknown = JSON.parse(specJson);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return [];
    }
    return Object.entries(parsed)
      .filter(([, value]) => ["string", "number", "boolean"].includes(typeof value))
      .map(([label, value]) => ({ label, value: String(value) }));
  } catch {
    return [];
  }
}
