import type {
  LocationQuery,
  LocationQueryRaw,
  LocationQueryValue,
} from "vue-router";

const firstQueryValue = (
  value: LocationQueryValue | LocationQueryValue[] | undefined,
): LocationQueryValue | undefined => Array.isArray(value) ? value[0] ?? null : value;

export function pageFromQuery(
  value: LocationQueryValue | LocationQueryValue[] | undefined,
): number {
  const raw = firstQueryValue(value);
  if (typeof raw !== "string" || !/^\d+$/u.test(raw)) {
    return 1;
  }
  const parsed = Number(raw);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : 1;
}

export function pageCount(total: number, pageSize: number): number {
  if (!Number.isFinite(total) || total <= 0 || !Number.isFinite(pageSize) || pageSize <= 0) {
    return 1;
  }
  return Math.max(1, Math.ceil(total / pageSize));
}

export function queryWithPage(
  query: LocationQuery,
  page: number,
): LocationQueryRaw {
  const next: LocationQueryRaw = { ...query };
  if (page <= 1) {
    delete next.page;
  } else {
    next.page = String(page);
  }
  return next;
}
