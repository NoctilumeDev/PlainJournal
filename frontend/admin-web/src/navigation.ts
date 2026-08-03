export function resolveStaffRedirect(value: unknown): string {
  if (
    typeof value !== "string"
    || !value.startsWith("/")
    || value.startsWith("//")
    || value.startsWith("/login")
  ) {
    return "/";
  }
  return value;
}
