import { diagnosticLogger as diag } from "../logging/diagnostic.js";
import { resolveGlobalSingleton } from "../shared/global-singleton.js";

/**
 * Error thrown when a queued message exceeds the configured wait timeout.
 */
export class MessageQueueTimeoutError extends Error {
  constructor(timeoutMs: number) {
    super(`Message queued for >${timeoutMs}ms — timed out waiting for a concurrency slot`);
    this.name = "MessageQueueTimeoutError";
  }
}

type WaitingEntry = {
  resolve: () => void;
  reject: (err: Error) => void;
  timer: ReturnType<typeof setTimeout>;
  enqueuedAt: number;
};

type ConcurrencyState = {
  maxConcurrent: number;
  queueTimeoutMs: number;
  active: number;
  waiting: WaitingEntry[];
};

const STATE_KEY = Symbol.for("openclaw.messageConcurrency");

const DEFAULT_MAX_CONCURRENT = 8;
const DEFAULT_QUEUE_TIMEOUT_MS = 120_000; // 2 minutes

function getState(): ConcurrencyState {
  return resolveGlobalSingleton(STATE_KEY, () => ({
    maxConcurrent: DEFAULT_MAX_CONCURRENT,
    queueTimeoutMs: DEFAULT_QUEUE_TIMEOUT_MS,
    active: 0,
    waiting: [],
  }));
}

/**
 * Update the concurrency limit and queue timeout. Safe to call multiple times
 * (e.g. on config reload). Does not interrupt in-flight work.
 */
export function configureMessageConcurrency(opts: {
  maxConcurrent?: number;
  queueTimeoutMs?: number;
}): void {
  const state = getState();
  if (opts.maxConcurrent != null) {
    state.maxConcurrent = Math.max(1, Math.floor(opts.maxConcurrent));
  }
  if (opts.queueTimeoutMs != null) {
    state.queueTimeoutMs = Math.max(0, Math.floor(opts.queueTimeoutMs));
  }
  // If the limit was raised, drain waiting entries that can now proceed.
  drainWaiting(state);
}

function drainWaiting(state: ConcurrencyState): void {
  while (state.active < state.maxConcurrent && state.waiting.length > 0) {
    const entry = state.waiting.shift()!;
    clearTimeout(entry.timer);
    state.active++;
    diag.debug(
      `message-concurrency: slot granted after ${Date.now() - entry.enqueuedAt}ms wait, active=${state.active}/${state.maxConcurrent} queued=${state.waiting.length}`,
    );
    entry.resolve();
  }
}

/**
 * Acquire a concurrency slot. Resolves immediately if a slot is available,
 * otherwise queues until a slot opens or the timeout fires.
 */
export function acquireMessageSlot(): Promise<void> {
  const state = getState();
  if (state.active < state.maxConcurrent) {
    state.active++;
    diag.debug(
      `message-concurrency: slot acquired immediately, active=${state.active}/${state.maxConcurrent}`,
    );
    return Promise.resolve();
  }

  return new Promise<void>((resolve, reject) => {
    const enqueuedAt = Date.now();
    const timer = setTimeout(() => {
      const idx = state.waiting.indexOf(entry);
      if (idx !== -1) {
        state.waiting.splice(idx, 1);
      }
      diag.warn(
        `message-concurrency: queue timeout after ${state.queueTimeoutMs}ms, active=${state.active}/${state.maxConcurrent} queued=${state.waiting.length}`,
      );
      reject(new MessageQueueTimeoutError(state.queueTimeoutMs));
    }, state.queueTimeoutMs);

    const entry: WaitingEntry = { resolve, reject, timer, enqueuedAt };
    state.waiting.push(entry);
    diag.debug(
      `message-concurrency: queued, active=${state.active}/${state.maxConcurrent} queued=${state.waiting.length}`,
    );
  });
}

/**
 * Release a concurrency slot and drain the next waiting entry if any.
 */
export function releaseMessageSlot(): void {
  const state = getState();
  state.active = Math.max(0, state.active - 1);
  drainWaiting(state);
}

/** Return current active and queued counts (diagnostic / status). */
export function getMessageConcurrencyStats(): {
  active: number;
  queued: number;
  maxConcurrent: number;
} {
  const state = getState();
  return {
    active: state.active,
    queued: state.waiting.length,
    maxConcurrent: state.maxConcurrent,
  };
}

/** Test-only: reset all state to defaults. */
export function resetMessageConcurrencyForTest(): void {
  const state = getState();
  for (const entry of state.waiting) {
    clearTimeout(entry.timer);
  }
  state.waiting.length = 0;
  state.active = 0;
  state.maxConcurrent = DEFAULT_MAX_CONCURRENT;
  state.queueTimeoutMs = DEFAULT_QUEUE_TIMEOUT_MS;
}
