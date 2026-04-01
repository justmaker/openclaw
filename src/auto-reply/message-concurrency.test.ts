import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  acquireMessageSlot,
  configureMessageConcurrency,
  getMessageConcurrencyStats,
  MessageQueueTimeoutError,
  releaseMessageSlot,
  resetMessageConcurrencyForTest,
} from "./message-concurrency.js";

beforeEach(() => {
  resetMessageConcurrencyForTest();
});

afterEach(() => {
  resetMessageConcurrencyForTest();
});

describe("message-concurrency", () => {
  it("allows up to maxConcurrent slots immediately", async () => {
    configureMessageConcurrency({ maxConcurrent: 2 });

    await acquireMessageSlot();
    await acquireMessageSlot();

    const stats = getMessageConcurrencyStats();
    expect(stats.active).toBe(2);
    expect(stats.queued).toBe(0);
    expect(stats.maxConcurrent).toBe(2);
  });

  it("queues when all slots are taken", async () => {
    configureMessageConcurrency({ maxConcurrent: 1 });
    await acquireMessageSlot();

    let secondResolved = false;
    const second = acquireMessageSlot().then(() => {
      secondResolved = true;
    });

    // Give microtasks a chance to run.
    await Promise.resolve();
    expect(secondResolved).toBe(false);
    expect(getMessageConcurrencyStats().queued).toBe(1);

    releaseMessageSlot();
    await second;
    expect(secondResolved).toBe(true);
    expect(getMessageConcurrencyStats().active).toBe(1);
  });

  it("drains multiple waiting entries in FIFO order", async () => {
    configureMessageConcurrency({ maxConcurrent: 1 });
    await acquireMessageSlot();

    const order: number[] = [];
    const p1 = acquireMessageSlot().then(() => order.push(1));
    const p2 = acquireMessageSlot().then(() => order.push(2));
    const p3 = acquireMessageSlot().then(() => order.push(3));

    releaseMessageSlot();
    await p1;
    releaseMessageSlot();
    await p2;
    releaseMessageSlot();
    await p3;

    expect(order).toEqual([1, 2, 3]);
  });

  it("rejects with MessageQueueTimeoutError when timeout expires", async () => {
    vi.useFakeTimers();
    try {
      configureMessageConcurrency({ maxConcurrent: 1, queueTimeoutMs: 500 });
      await acquireMessageSlot();

      const pending = acquireMessageSlot();
      vi.advanceTimersByTime(501);

      await expect(pending).rejects.toThrow(MessageQueueTimeoutError);
      expect(getMessageConcurrencyStats().queued).toBe(0);
    } finally {
      vi.useRealTimers();
    }
  });

  it("release after timeout does not go negative", async () => {
    vi.useFakeTimers();
    try {
      configureMessageConcurrency({ maxConcurrent: 1, queueTimeoutMs: 100 });
      await acquireMessageSlot();

      const pending = acquireMessageSlot().catch(() => {});
      vi.advanceTimersByTime(101);
      await pending;

      // Release the original slot.
      releaseMessageSlot();
      expect(getMessageConcurrencyStats().active).toBe(0);

      // Extra release should not go below 0.
      releaseMessageSlot();
      expect(getMessageConcurrencyStats().active).toBe(0);
    } finally {
      vi.useRealTimers();
    }
  });

  it("raising maxConcurrent immediately drains queued entries", async () => {
    configureMessageConcurrency({ maxConcurrent: 1 });
    await acquireMessageSlot();

    let secondResolved = false;
    const second = acquireMessageSlot().then(() => {
      secondResolved = true;
    });

    await Promise.resolve();
    expect(secondResolved).toBe(false);

    // Raise the limit — the queued entry should be granted immediately.
    configureMessageConcurrency({ maxConcurrent: 3 });
    await second;
    expect(secondResolved).toBe(true);
    expect(getMessageConcurrencyStats().active).toBe(2);
  });

  it("defaults to 8 concurrent slots", () => {
    expect(getMessageConcurrencyStats().maxConcurrent).toBe(8);
  });

  it("clamps maxConcurrent to at least 1", () => {
    configureMessageConcurrency({ maxConcurrent: 0 });
    expect(getMessageConcurrencyStats().maxConcurrent).toBe(1);

    configureMessageConcurrency({ maxConcurrent: -5 });
    expect(getMessageConcurrencyStats().maxConcurrent).toBe(1);
  });

  it("handles concurrent acquire and release correctly", async () => {
    configureMessageConcurrency({ maxConcurrent: 2 });

    const results: string[] = [];

    async function worker(id: string, delayMs: number): Promise<void> {
      await acquireMessageSlot();
      results.push(`${id}:start`);
      await new Promise((r) => setTimeout(r, delayMs));
      results.push(`${id}:end`);
      releaseMessageSlot();
    }

    await Promise.all([worker("a", 10), worker("b", 10), worker("c", 10)]);

    // All three should complete.
    expect(results.filter((r) => r.endsWith(":end"))).toHaveLength(3);
    expect(getMessageConcurrencyStats().active).toBe(0);
  });
});
