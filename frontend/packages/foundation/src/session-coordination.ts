const localQueues = new Map<string, Promise<void>>();

export interface BrowserSessionRelay<T> {
  readonly available: boolean;
  publish(message: T): void;
  waitFor(
    predicate: (message: T) => boolean,
    timeoutMs?: number,
  ): Promise<T | null>;
  close(): void;
}

export class SessionCoordinationUnavailableError extends Error {
  constructor() {
    super("当前浏览器无法安全协调多个页面的会话刷新，请重新登录。");
    this.name = "SessionCoordinationUnavailableError";
  }
}

function isTestBrowser(): boolean {
  return typeof navigator !== "undefined"
    && navigator.userAgent.toLowerCase().includes("jsdom");
}

async function withLocalQueue<T>(
  name: string,
  operation: () => Promise<T>,
): Promise<T> {
  const previous = localQueues.get(name) ?? Promise.resolve();
  let release!: () => void;
  const current = new Promise<void>((resolve) => {
    release = resolve;
  });
  const queued = previous.then(() => current);
  localQueues.set(name, queued);
  await previous;
  try {
    return await operation();
  } finally {
    release();
    if (localQueues.get(name) === queued) {
      localQueues.delete(name);
    }
  }
}

export function withBrowserSessionLock<T>(
  name: string,
  operation: () => Promise<T>,
): Promise<T> {
  if (typeof navigator !== "undefined" && navigator.locks) {
    return navigator.locks.request(name, { mode: "exclusive" }, operation);
  }
  if (typeof window !== "undefined" && !isTestBrowser()) {
    return Promise.reject(new SessionCoordinationUnavailableError());
  }
  return withLocalQueue(name, operation);
}

export function createBrowserSessionRelay<T>(
  name: string,
  onMessage?: (message: T) => void,
): BrowserSessionRelay<T> {
  if (isTestBrowser() || typeof BroadcastChannel === "undefined") {
    return {
      available: false,
      publish: () => undefined,
      waitFor: () => Promise.resolve(null),
      close: () => undefined,
    };
  }

  const channel = new BroadcastChannel(name);
  const recent: T[] = [];
  const waiters = new Set<{
    predicate: (message: T) => boolean;
    resolve: (message: T) => void;
  }>();

  channel.addEventListener("message", (event: MessageEvent<T>) => {
    recent.push(event.data);
    if (recent.length > 4) {
      recent.shift();
    }
    onMessage?.(event.data);
    for (const waiter of [...waiters]) {
      if (!waiter.predicate(event.data)) {
        continue;
      }
      waiters.delete(waiter);
      waiter.resolve(event.data);
    }
  });

  return {
    available: true,
    publish(message) {
      try {
        channel.postMessage(message);
      } catch {
        // Cross-tab propagation must never invalidate an already verified local rotation.
      }
    },
    waitFor(predicate, timeoutMs = 500) {
      const cached = recent.findLast(predicate);
      if (cached) {
        return Promise.resolve(cached);
      }
      return new Promise<T | null>((resolve) => {
        let timeout: ReturnType<typeof setTimeout> | undefined;
        const waiter = {
          predicate,
          resolve: (message: T) => {
            if (timeout !== undefined) {
              clearTimeout(timeout);
            }
            resolve(message);
          },
        };
        waiters.add(waiter);
        timeout = setTimeout(() => {
          waiters.delete(waiter);
          resolve(null);
        }, timeoutMs);
      });
    },
    close() {
      waiters.clear();
      channel.close();
    },
  };
}
