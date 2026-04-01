import { describe, expect, it, vi } from "vitest";
import { createAnthropicOAuthPlugin } from "./anthropic-oauth-plugin.js";
import {
  AuthPluginRegistry,
  type AuthPlugin,
  type AuthPluginCredential,
  type AuthPluginRequestContext,
} from "./auth-plugin.js";

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function makeCtx(overrides?: Partial<AuthPluginRequestContext>): AuthPluginRequestContext {
  return {
    providerId: "anthropic",
    baseUrl: "https://api.anthropic.com",
    credential: {
      mode: "oauth",
      accessToken: "test-access-token",
      refreshToken: "test-refresh-token",
      expiresAt: Date.now() + 3_600_000, // 1 hour from now
    },
    headers: {},
    ...overrides,
  };
}

function makePlugin(overrides?: Partial<AuthPlugin>): AuthPlugin {
  return {
    id: "test-plugin",
    label: "Test Plugin",
    providers: ["anthropic"],
    interceptRequest: vi.fn(),
    ...overrides,
  };
}

// ---------------------------------------------------------------------------
// AuthPluginRegistry
// ---------------------------------------------------------------------------

describe("AuthPluginRegistry", () => {
  describe("register / unregister / list", () => {
    it("registers and lists plugins", () => {
      const registry = new AuthPluginRegistry();
      const plugin = makePlugin();
      registry.register(plugin);

      expect(registry.list()).toHaveLength(1);
      expect(registry.list()[0]?.id).toBe("test-plugin");
    });

    it("replaces duplicate plugin ids", () => {
      const registry = new AuthPluginRegistry();
      const v1 = makePlugin({ label: "v1" });
      const v2 = makePlugin({ label: "v2" });

      registry.register(v1);
      registry.register(v2);

      expect(registry.list()).toHaveLength(1);
      expect(registry.list()[0]?.label).toBe("v2");
    });

    it("sorts by priority (lower first)", () => {
      const registry = new AuthPluginRegistry();
      registry.register(makePlugin({ id: "high", priority: 200 }));
      registry.register(makePlugin({ id: "low", priority: 10 }));
      registry.register(makePlugin({ id: "default" })); // 100

      const ids = registry.list().map((p) => p.id);
      expect(ids).toEqual(["low", "default", "high"]);
    });

    it("unregister removes plugin and returns true", () => {
      const registry = new AuthPluginRegistry();
      registry.register(makePlugin());
      expect(registry.unregister("test-plugin")).toBe(true);
      expect(registry.list()).toHaveLength(0);
    });

    it("unregister returns false for unknown id", () => {
      const registry = new AuthPluginRegistry();
      expect(registry.unregister("nonexistent")).toBe(false);
    });
  });

  describe("intercept", () => {
    it("calls interceptRequest on matching plugins", async () => {
      const registry = new AuthPluginRegistry();
      const intercept = vi.fn();
      registry.register(makePlugin({ interceptRequest: intercept }));

      const ctx = makeCtx();
      await registry.intercept(ctx);

      expect(intercept).toHaveBeenCalledOnce();
      expect(intercept).toHaveBeenCalledWith(ctx);
    });

    it("skips plugins that do not match provider", async () => {
      const registry = new AuthPluginRegistry();
      const intercept = vi.fn();
      registry.register(makePlugin({ providers: ["openai"], interceptRequest: intercept }));

      await registry.intercept(makeCtx({ providerId: "anthropic" }));
      expect(intercept).not.toHaveBeenCalled();
    });

    it("wildcard provider matches everything", async () => {
      const registry = new AuthPluginRegistry();
      const intercept = vi.fn();
      registry.register(makePlugin({ providers: ["*"], interceptRequest: intercept }));

      await registry.intercept(makeCtx({ providerId: "some-random-provider" }));
      expect(intercept).toHaveBeenCalledOnce();
    });

    it("respects custom match function", async () => {
      const registry = new AuthPluginRegistry();
      const intercept = vi.fn();
      registry.register(
        makePlugin({
          match: (ctx) => ctx.credential.mode === "api-key",
          interceptRequest: intercept,
        }),
      );

      // OAuth mode → should not match
      await registry.intercept(makeCtx());
      expect(intercept).not.toHaveBeenCalled();

      // API key mode → should match
      await registry.intercept(makeCtx({ credential: { mode: "api-key", apiKey: "sk-test" } }));
      expect(intercept).toHaveBeenCalledOnce();
    });

    it("runs plugins sequentially in priority order", async () => {
      const registry = new AuthPluginRegistry();
      const order: string[] = [];

      registry.register(
        makePlugin({
          id: "second",
          priority: 200,
          interceptRequest: () => {
            order.push("second");
          },
        }),
      );
      registry.register(
        makePlugin({
          id: "first",
          priority: 10,
          interceptRequest: () => {
            order.push("first");
          },
        }),
      );

      await registry.intercept(makeCtx());
      expect(order).toEqual(["first", "second"]);
    });

    it("allows plugins to mutate headers", async () => {
      const registry = new AuthPluginRegistry();
      registry.register(
        makePlugin({
          interceptRequest: (ctx) => {
            ctx.headers["x-custom"] = "value";
          },
        }),
      );

      const ctx = makeCtx();
      const headers = await registry.intercept(ctx);

      expect(headers["x-custom"]).toBe("value");
      expect(ctx.headers["x-custom"]).toBe("value");
    });

    it("refreshes expired token before interceptRequest", async () => {
      const registry = new AuthPluginRegistry();
      const refreshToken = vi.fn().mockResolvedValue({
        accessToken: "new-token",
        expiresAt: Date.now() + 3_600_000,
      });
      const interceptRequest = vi.fn();

      registry.register(makePlugin({ refreshToken, interceptRequest }));

      const ctx = makeCtx({
        credential: {
          mode: "oauth",
          accessToken: "old-token",
          refreshToken: "refresh-token",
          expiresAt: Date.now() - 60_000, // already expired
        },
      });

      await registry.intercept(ctx);

      expect(refreshToken).toHaveBeenCalledOnce();
      expect(ctx.credential.accessToken).toBe("new-token");
      expect(interceptRequest).toHaveBeenCalledOnce();
    });

    it("does not refresh when token is still valid", async () => {
      const registry = new AuthPluginRegistry();
      const refreshToken = vi.fn();
      registry.register(makePlugin({ refreshToken, interceptRequest: vi.fn() }));

      await registry.intercept(makeCtx()); // expiresAt is 1h from now
      expect(refreshToken).not.toHaveBeenCalled();
    });

    it("refreshes within grace window (30s before expiry)", async () => {
      const registry = new AuthPluginRegistry();
      const refreshToken = vi.fn().mockResolvedValue({
        accessToken: "refreshed",
      });
      registry.register(makePlugin({ refreshToken, interceptRequest: vi.fn() }));

      // 20 seconds before expiry → within 30s grace window
      const ctx = makeCtx({
        credential: {
          mode: "oauth",
          accessToken: "old",
          refreshToken: "rt",
          expiresAt: Date.now() + 20_000,
        },
      });
      await registry.intercept(ctx);
      expect(refreshToken).toHaveBeenCalledOnce();
    });

    it("returns empty headers when no plugins match", async () => {
      const registry = new AuthPluginRegistry();
      const headers = await registry.intercept(makeCtx());
      expect(headers).toEqual({});
    });
  });
});

// ---------------------------------------------------------------------------
// Anthropic OAuth Plugin (example)
// ---------------------------------------------------------------------------

describe("createAnthropicOAuthPlugin", () => {
  it("creates a plugin with correct metadata", () => {
    const plugin = createAnthropicOAuthPlugin();
    expect(plugin.id).toBe("anthropic-oauth");
    expect(plugin.providers).toEqual(["anthropic"]);
    expect(plugin.priority).toBe(50);
  });

  it("matches only OAuth mode with accessToken", () => {
    const plugin = createAnthropicOAuthPlugin();
    const ctx = makeCtx();
    expect(plugin.match?.(ctx)).toBe(true);
  });

  it("does not match api-key mode", () => {
    const plugin = createAnthropicOAuthPlugin();
    const ctx = makeCtx({ credential: { mode: "api-key", apiKey: "sk-test" } });
    expect(plugin.match?.(ctx)).toBe(false);
  });

  it("does not match when accessToken is missing", () => {
    const plugin = createAnthropicOAuthPlugin();
    const ctx = makeCtx({ credential: { mode: "oauth" } });
    expect(plugin.match?.(ctx)).toBe(false);
  });

  it("sets Authorization header", async () => {
    const plugin = createAnthropicOAuthPlugin();
    const ctx = makeCtx();
    await plugin.interceptRequest(ctx);
    expect(ctx.headers["authorization"]).toBe("Bearer test-access-token");
  });

  it("refreshToken calls the token endpoint", async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        access_token: "new-access",
        refresh_token: "new-refresh",
        expires_in: 3600,
      }),
    });
    vi.stubGlobal("fetch", mockFetch);

    const plugin = createAnthropicOAuthPlugin({ clientId: "test-client" });
    const credential: AuthPluginCredential = {
      mode: "oauth",
      accessToken: "old",
      refreshToken: "old-refresh",
      expiresAt: Date.now() - 1000,
    };

    const result = await plugin.refreshToken!(credential);

    expect(result.accessToken).toBe("new-access");
    expect(result.refreshToken).toBe("new-refresh");
    expect(result.expiresAt).toBeGreaterThan(Date.now());

    expect(mockFetch).toHaveBeenCalledOnce();
    const [url, init] = mockFetch.mock.calls[0];
    expect(url).toBe("https://auth.anthropic.com/oauth/token");
    expect(init.method).toBe("POST");
    expect(init.body).toContain("grant_type=refresh_token");
    expect(init.body).toContain("client_id=test-client");

    vi.unstubAllGlobals();
  });

  it("refreshToken throws when no refresh token", async () => {
    const plugin = createAnthropicOAuthPlugin();
    const credential: AuthPluginCredential = {
      mode: "oauth",
      accessToken: "old",
    };

    await expect(plugin.refreshToken!(credential)).rejects.toThrow("no refresh token available");
  });

  it("refreshToken throws on HTTP error", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: false,
        status: 401,
        text: async () => "Unauthorized",
      }),
    );

    const plugin = createAnthropicOAuthPlugin();
    const credential: AuthPluginCredential = {
      mode: "oauth",
      accessToken: "old",
      refreshToken: "rt",
    };

    await expect(plugin.refreshToken!(credential)).rejects.toThrow("token refresh failed (401)");

    vi.unstubAllGlobals();
  });

  it("integrates with AuthPluginRegistry end-to-end", async () => {
    const registry = new AuthPluginRegistry();
    registry.register(createAnthropicOAuthPlugin());

    const ctx = makeCtx();
    const headers = await registry.intercept(ctx);

    expect(headers["authorization"]).toBe("Bearer test-access-token");
  });
});
