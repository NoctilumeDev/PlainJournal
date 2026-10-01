import type { RouteLocationRaw, Router } from "vue-router";

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

export async function returnToPreviousOr(
  router: Router,
  fallback: RouteLocationRaw,
  allowPrevious: (path: string) => boolean = () => true,
): Promise<"history" | "fallback"> {
  const back = router.options.history.state.back;
  if (
    typeof back === "string"
    && back.startsWith("/")
    && !back.startsWith("//")
    && allowPrevious(back)
  ) {
    router.back();
    return "history";
  }
  await router.replace(fallback);
  return "fallback";
}
