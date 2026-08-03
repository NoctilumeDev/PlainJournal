export function safeReturnTo(value: unknown, fallback = "/account"): string {
  if (
    typeof value !== "string"
    || !value.startsWith("/")
    || value.startsWith("//")
    || value.includes("\\")
  ) {
    return fallback;
  }
  return value;
}
