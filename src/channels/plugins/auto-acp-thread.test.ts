import { describe, expect, it, beforeEach, afterEach } from "vitest";
import {
  resolveAutoAcpThreadConfig,
  isAutoAcpThreadEnabled,
  tryAcquireAutoAcpLock,
  releaseAutoAcpLock,
} from "./auto-acp-thread.js";

describe("auto-acp-thread", () => {
  describe("resolveAutoAcpThreadConfig", () => {
    it("returns null for undefined", () => {
      expect(resolveAutoAcpThreadConfig(undefined)).toBeNull();
    });

    it("returns null for false", () => {
      expect(resolveAutoAcpThreadConfig(false)).toBeNull();
    });

    it("returns defaults for true", () => {
      const result = resolveAutoAcpThreadConfig(true);
      expect(result).toEqual({
        enabled: true,
        backend: "acpx",
        mode: "persistent",
      });
    });

    it("returns null for { enabled: false }", () => {
      expect(resolveAutoAcpThreadConfig({ enabled: false })).toBeNull();
    });

    it("uses custom values", () => {
      const result = resolveAutoAcpThreadConfig({
        enabled: true,
        backend: "custom-backend",
        agentId: "codex",
        mode: "oneshot",
      });
      expect(result).toEqual({
        enabled: true,
        backend: "custom-backend",
        agentId: "codex",
        mode: "oneshot",
      });
    });

    it("fills defaults for partial config", () => {
      const result = resolveAutoAcpThreadConfig({ agentId: "claude" });
      expect(result).toEqual({
        enabled: true,
        backend: "acpx",
        agentId: "claude",
        mode: "persistent",
      });
    });
  });

  describe("isAutoAcpThreadEnabled", () => {
    it("returns null when no guilds config", () => {
      expect(isAutoAcpThreadEnabled({}, "guild1", "channel1")).toBeNull();
    });

    it("returns null when guild not found", () => {
      const config = { guilds: { guild2: { channels: {} } } };
      expect(isAutoAcpThreadEnabled(config, "guild1", "channel1")).toBeNull();
    });

    it("returns null when channel not found", () => {
      const config = { guilds: { guild1: { channels: { channel2: {} } } } };
      expect(isAutoAcpThreadEnabled(config, "guild1", "channel1")).toBeNull();
    });

    it("returns null when channel has no autoAcpThread", () => {
      const config = { guilds: { guild1: { channels: { channel1: {} } } } };
      expect(isAutoAcpThreadEnabled(config, "guild1", "channel1")).toBeNull();
    });

    it("returns config when autoAcpThread is true", () => {
      const config = {
        guilds: { guild1: { channels: { channel1: { autoAcpThread: true as const } } } },
      };
      const result = isAutoAcpThreadEnabled(config, "guild1", "channel1");
      expect(result).toEqual({
        enabled: true,
        backend: "acpx",
        mode: "persistent",
      });
    });

    it("returns config with custom values", () => {
      const config = {
        guilds: {
          guild1: {
            channels: {
              channel1: {
                autoAcpThread: { backend: "custom", agentId: "test-agent", mode: "oneshot" as const },
              },
            },
          },
        },
      };
      const result = isAutoAcpThreadEnabled(config, "guild1", "channel1");
      expect(result).toEqual({
        enabled: true,
        backend: "custom",
        agentId: "test-agent",
        mode: "oneshot",
      });
    });
  });

  describe("race condition locks", () => {
    const threadId = "test-thread-123";

    afterEach(() => {
      releaseAutoAcpLock(threadId);
    });

    it("first caller acquires lock", () => {
      expect(tryAcquireAutoAcpLock(threadId)).toBe(true);
    });

    it("second caller fails to acquire same lock", () => {
      tryAcquireAutoAcpLock(threadId);
      expect(tryAcquireAutoAcpLock(threadId)).toBe(false);
    });

    it("lock can be reacquired after release", () => {
      tryAcquireAutoAcpLock(threadId);
      releaseAutoAcpLock(threadId);
      expect(tryAcquireAutoAcpLock(threadId)).toBe(true);
    });

    it("different thread IDs don't interfere", () => {
      tryAcquireAutoAcpLock("thread-a");
      expect(tryAcquireAutoAcpLock("thread-b")).toBe(true);
      releaseAutoAcpLock("thread-a");
      releaseAutoAcpLock("thread-b");
    });
  });
});
