/**
 * Auto ACP Thread Binding
 *
 * 當指定的 Discord channel 中有人建立新 thread 時，
 * 自動建立 ACP session 並 bind 到該 thread。
 *
 * Config:
 *   channels.discord.guilds.<guildId>.channels.<channelId>.autoAcpThread: true | AutoAcpThreadConfig
 *
 * AutoAcpThreadConfig:
 *   enabled: boolean (default: true)
 *   backend: string (default: "acpx")
 *   agentId: string (default: 從 config 推算)
 *   mode: "persistent" | "oneshot" (default: "persistent")
 */

export type AutoAcpThreadConfig = {
  /** Enable auto ACP thread binding for this channel. */
  enabled?: boolean;
  /** ACP backend to use (default: "acpx"). */
  backend?: string;
  /** Agent ID to use for the ACP session. If omitted, uses the default agent. */
  agentId?: string;
  /** ACP session mode (default: "persistent"). */
  mode?: "persistent" | "oneshot";
};

export type ResolvedAutoAcpThreadConfig = {
  enabled: boolean;
  backend: string;
  agentId?: string;
  mode: "persistent" | "oneshot";
};

/**
 * Normalize the autoAcpThread config value.
 * Accepts boolean (true = enabled with defaults) or full config object.
 */
export function resolveAutoAcpThreadConfig(
  raw?: boolean | AutoAcpThreadConfig,
): ResolvedAutoAcpThreadConfig | null {
  if (raw === undefined || raw === null || raw === false) {
    return null;
  }
  if (raw === true) {
    return {
      enabled: true,
      backend: "acpx",
      mode: "persistent",
    };
  }
  if (raw.enabled === false) {
    return null;
  }
  return {
    enabled: true,
    backend: raw.backend ?? "acpx",
    agentId: raw.agentId,
    mode: raw.mode ?? "persistent",
  };
}

/**
 * Check if a channel has auto ACP thread binding enabled.
 * Looks up the guild + channel config path.
 */
export function isAutoAcpThreadEnabled(
  discordConfig: {
    guilds?: Record<
      string,
      {
        channels?: Record<string, { autoAcpThread?: boolean | AutoAcpThreadConfig }>;
      }
    >;
  },
  guildId: string,
  channelId: string,
): ResolvedAutoAcpThreadConfig | null {
  const guild = discordConfig?.guilds?.[guildId];
  if (!guild?.channels) {
    return null;
  }
  const channelConfig = guild.channels[channelId];
  if (!channelConfig) {
    return null;
  }
  return resolveAutoAcpThreadConfig(
    (channelConfig as { autoAcpThread?: boolean | AutoAcpThreadConfig }).autoAcpThread,
  );
}

// In-flight thread creation tracking to prevent race conditions
const pendingThreadBindings = new Set<string>();

/**
 * Attempt to acquire a lock for auto-binding a thread.
 * Returns true if this caller won the race, false if another caller is already handling it.
 */
export function tryAcquireAutoAcpLock(threadId: string): boolean {
  if (pendingThreadBindings.has(threadId)) {
    return false;
  }
  pendingThreadBindings.add(threadId);
  return true;
}

/**
 * Release the lock after binding is complete (success or failure).
 */
export function releaseAutoAcpLock(threadId: string): void {
  pendingThreadBindings.delete(threadId);
}
