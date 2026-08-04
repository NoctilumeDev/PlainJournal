function uuidFromRandomValues(randomValues: Uint8Array): string {
  randomValues[6] = ((randomValues[6] ?? 0) & 0x0f) | 0x40;
  randomValues[8] = ((randomValues[8] ?? 0) & 0x3f) | 0x80;

  const hexadecimal = Array.from(
    randomValues,
    (value) => value.toString(16).padStart(2, "0"),
  ).join("");
  return [
    hexadecimal.slice(0, 8),
    hexadecimal.slice(8, 12),
    hexadecimal.slice(12, 16),
    hexadecimal.slice(16, 20),
    hexadecimal.slice(20),
  ].join("-");
}

/**
 * Returns a cryptographically secure UUID v4 for client-visible command keys.
 * Command and idempotency identities must never fall back to Math.random().
 */
export function secureRandomUUID(): string {
  const webCrypto = globalThis.crypto;
  if (typeof webCrypto?.randomUUID === "function") {
    return webCrypto.randomUUID();
  }
  if (typeof webCrypto?.getRandomValues === "function") {
    return uuidFromRandomValues(webCrypto.getRandomValues(new Uint8Array(16)));
  }
  throw new Error(
    "当前环境不支持 Web Crypto，无法安全生成命令幂等键。",
  );
}
