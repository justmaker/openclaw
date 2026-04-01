import type { ModelProviderAuthMode } from "../config/types.models.js";

// ---------------------------------------------------------------------------
// Auth Plugin Hook – extensible request-level auth for provider plugins.
//
// This layer sits on top of the existing provider auth flow. It does NOT
// replace `ProviderAuthMethod` or `ProviderCatalogContext.resolveProviderAuth`;
// it adds a composable hook that can intercept outgoing API requests and
// mutate headers/tokens before they reach the provider.
// ---------------------------------------------------------------------------

/** Credential snapshot surfaced to auth plugins at request time. */
export type AuthPluginCredential = {
  /** Resolved API key (may be undefined when OAuth/token mode is active). */
  apiKey?: string;
  /** Auth mode that was resolved for this provider. */
  mode: ModelProviderAuthMode | "none";
  /** OAuth access token when the provider uses OAuth. */
  accessToken?: string;
  /** OAuth refresh token (present when the provider stores one). */
  refreshToken?: string;
  /** Unix-ms expiry of the current access token, if known. */
  expiresAt?: number;
};

/** Mutable bag of headers that the auth plugin can read and modify. */
export type AuthPluginHeaders = Record<string, string>;

/** Context passed to every auth plugin hook invocation. */
export type AuthPluginRequestContext = {
  /** Normalized provider id (e.g. "anthropic", "openai"). */
  providerId: string;
  /** Base URL of the outgoing request. */
  baseUrl: string;
  /** Current credential snapshot for this provider. */
  credential: AuthPluginCredential;
  /** Mutable request headers – plugins can add/override entries. */
  headers: AuthPluginHeaders;
};

/** Result of a token refresh attempt. */
export type AuthPluginRefreshResult = {
  accessToken: string;
  /** Updated refresh token, if the provider rotated it. */
  refreshToken?: string;
  /** New Unix-ms expiry for the refreshed access token. */
  expiresAt?: number;
};

/**
 * Auth Plugin contract.
 *
 * Implementations are registered via `AuthPluginRegistry.register()` and are
 * invoked in priority order before every outgoing provider API request.
 */
export type AuthPlugin = {
  /** Unique plugin identifier (e.g. "anthropic-oauth"). */
  id: string;
  /** Human-readable label for logs and diagnostics. */
  label: string;
  /**
   * Provider ids this plugin handles.
   * Use `["*"]` to match all providers (use sparingly).
   */
  providers: string[];
  /**
   * Priority order – lower numbers run first.
   * Default: 100.
   */
  priority?: number;
  /**
   * Return `true` if this plugin should handle the current request.
   *
   * Called after provider-id filtering. Use this for finer-grained matching
   * (e.g. only OAuth mode, specific base URLs).
   */
  match?: (ctx: AuthPluginRequestContext) => boolean;
  /**
   * Intercept the outgoing request and mutate headers.
   *
   * This is the main hook. Typical uses:
   * - Set `Authorization: Bearer <token>` from an OAuth credential.
   * - Add vendor-specific headers (e.g. `anthropic-version`).
   * - Replace an expired token inline (or call `refreshToken` first).
   */
  interceptRequest: (ctx: AuthPluginRequestContext) => void | Promise<void>;
  /**
   * Optional: refresh an expired token.
   *
   * Called by the registry when `credential.expiresAt` is in the past (or
   * within a configurable grace window). The returned tokens replace the
   * stale credential for the remainder of the request pipeline.
   */
  refreshToken?: (credential: AuthPluginCredential) => Promise<AuthPluginRefreshResult>;
};

// ---------------------------------------------------------------------------
// Auth Plugin Registry
// ---------------------------------------------------------------------------

const DEFAULT_PRIORITY = 100;
/** Grace window (ms) before actual expiry to trigger a proactive refresh. */
const REFRESH_GRACE_MS = 30_000;

export class AuthPluginRegistry {
  private plugins: AuthPlugin[] = [];

  /** Register an auth plugin. Duplicate ids are silently replaced. */
  register(plugin: AuthPlugin): void {
    this.plugins = this.plugins.filter((p) => p.id !== plugin.id);
    this.plugins.push(plugin);
    this.plugins.sort(
      (a, b) => (a.priority ?? DEFAULT_PRIORITY) - (b.priority ?? DEFAULT_PRIORITY),
    );
  }

  /** Remove a previously registered plugin by id. */
  unregister(id: string): boolean {
    const before = this.plugins.length;
    this.plugins = this.plugins.filter((p) => p.id !== id);
    return this.plugins.length < before;
  }

  /** Return a snapshot of all registered plugins (sorted by priority). */
  list(): readonly AuthPlugin[] {
    return [...this.plugins];
  }

  /**
   * Run the auth plugin pipeline for an outgoing request.
   *
   * Matching plugins execute sequentially in priority order. Each plugin may
   * mutate `ctx.headers` and, when a token refresh is needed, the registry
   * calls `refreshToken` before `interceptRequest`.
   *
   * Returns the (possibly mutated) headers for the caller to apply.
   */
  async intercept(ctx: AuthPluginRequestContext): Promise<AuthPluginHeaders> {
    const matched = this.resolve(ctx);
    for (const plugin of matched) {
      // Token refresh when credential looks expired.
      if (plugin.refreshToken && isTokenExpired(ctx.credential)) {
        const refreshed = await plugin.refreshToken(ctx.credential);
        ctx.credential = {
          ...ctx.credential,
          accessToken: refreshed.accessToken,
          refreshToken: refreshed.refreshToken ?? ctx.credential.refreshToken,
          expiresAt: refreshed.expiresAt,
        };
      }
      await plugin.interceptRequest(ctx);
    }
    return ctx.headers;
  }

  /** Resolve which plugins match the given request context. */
  private resolve(ctx: AuthPluginRequestContext): AuthPlugin[] {
    return this.plugins.filter((plugin) => {
      const providerMatch =
        plugin.providers.includes("*") || plugin.providers.includes(ctx.providerId);
      if (!providerMatch) {
        return false;
      }
      if (plugin.match && !plugin.match(ctx)) {
        return false;
      }
      return true;
    });
  }
}

function isTokenExpired(credential: AuthPluginCredential): boolean {
  if (credential.expiresAt == null) {
    return false;
  }
  return Date.now() >= credential.expiresAt - REFRESH_GRACE_MS;
}
