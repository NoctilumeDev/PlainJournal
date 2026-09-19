import { afterEach, describe, expect, it, vi } from "vitest";

import {
  createBrowserSessionRelay,
  withBrowserSessionLock,
} from "./session-coordination";

class FakeBroadcastChannel {
  static readonly channels = new Map<string, Set<FakeBroadcastChannel>>();

  readonly listeners = new Set<(event: MessageEvent<unknown>) => void>();

  constructor(readonly name: string) {
    const peers = FakeBroadcastChannel.channels.get(name) ?? new Set();
    peers.add(this);
    FakeBroadcastChannel.channels.set(name, peers);
  }

  addEventListener(
    type: string,
    listener: (event: MessageEvent<unknown>) => void,
  ) {
    if (type === "message") {
      this.listeners.add(listener);
    }
  }

  postMessage(message: unknown) {
    const cloned = structuredClone(message);
    for (const peer of FakeBroadcastChannel.channels.get(this.name) ?? []) {
      if (peer === this) {
        continue;
      }
      queueMicrotask(() => {
        for (const listener of peer.listeners) {
          listener({ data: cloned } as MessageEvent<unknown>);
        }
      });
    }
  }

  close() {
    FakeBroadcastChannel.channels.get(this.name)?.delete(this);
  }
}

describe("withBrowserSessionLock", () => {
  afterEach(() => {
    FakeBroadcastChannel.channels.clear();
    vi.unstubAllGlobals();
  });

  it("uses one named exclusive Web Lock when the browser provides it", async () => {
    const request = vi.fn(async (
      _name: string,
      _options: LockOptions,
      operation: () => Promise<string>,
    ) => operation());
    vi.stubGlobal("navigator", { locks: { request } });

    await expect(withBrowserSessionLock("customer-session", async () => "done"))
      .resolves.toBe("done");
    expect(request).toHaveBeenCalledWith(
      "customer-session",
      { mode: "exclusive" },
      expect.any(Function),
    );
  });

  it("serializes operations in the non-browser test fallback", async () => {
    const events: string[] = [];
    let release!: () => void;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });

    const first = withBrowserSessionLock("test-session", async () => {
      events.push("first-start");
      await gate;
      events.push("first-end");
    });
    const second = withBrowserSessionLock("test-session", async () => {
      events.push("second-start");
    });
    await Promise.resolve();
    await Promise.resolve();
    expect(events).toEqual(["first-start"]);

    release();
    await Promise.all([first, second]);
    expect(events).toEqual(["first-start", "first-end", "second-start"]);
  });
});

describe("createBrowserSessionRelay", () => {
  afterEach(() => {
    FakeBroadcastChannel.channels.clear();
    vi.unstubAllGlobals();
  });

  it("delivers and caches a verified rotation for a waiting peer", async () => {
    vi.stubGlobal("navigator", { userAgent: "Chrome" });
    vi.stubGlobal("BroadcastChannel", FakeBroadcastChannel);
    const sender = createBrowserSessionRelay<{ generation: string }>("session");
    const seen: string[] = [];
    const receiver = createBrowserSessionRelay<{ generation: string }>(
      "session",
      (message) => seen.push(message.generation),
    );

    sender.publish({ generation: "r2" });
    await Promise.resolve();

    await expect(receiver.waitFor((message) => message.generation === "r2", 10))
      .resolves.toEqual({ generation: "r2" });
    expect(seen).toEqual(["r2"]);
    sender.close();
    receiver.close();
  });

  it("does not release a waiter for a different generation", async () => {
    vi.stubGlobal("navigator", { userAgent: "Chrome" });
    vi.stubGlobal("BroadcastChannel", FakeBroadcastChannel);
    const sender = createBrowserSessionRelay<{ generation: string }>("session");
    const receiver = createBrowserSessionRelay<{ generation: string }>("session");
    const waiting = receiver.waitFor((message) => message.generation === "r3", 20);

    sender.publish({ generation: "r2" });

    await expect(waiting).resolves.toBeNull();
    sender.close();
    receiver.close();
  });

  it("does not let an uncloneable advisory message break local recovery", () => {
    vi.stubGlobal("navigator", { userAgent: "Chrome" });
    vi.stubGlobal("BroadcastChannel", FakeBroadcastChannel);
    const sender = createBrowserSessionRelay<object>("session");

    expect(() => sender.publish(new Proxy({}, {}))).not.toThrow();
    sender.close();
  });
});
