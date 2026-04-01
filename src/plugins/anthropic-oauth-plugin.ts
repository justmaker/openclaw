import type { AuthPlugin, AuthPluginCredential, AuthPluginRefreshResult } from "./auth-plugin.js";

/**
 * Example auth plugin: Anthropic OAuth Bearer token.
 *
 * Demonstrates how to use the auth plugin hook to:
 * 1. Match only Anthropic provider in OAuth mode.
 * 2. Inject `Authorization: Bearer <accessToken>` header.
 * 3. Refresh expired tokens via Anthropic's token endpoint.
 */

const ANTHROPIC_TOKEN_ENDPOINT = "https://auth.anthropic.com/oauth/token";

export function createAnthropicOAuthPlugin(params?: {
  /** Override the token endpoint (useful for testing). */
  tokenEndpoint?: string;
  /** OAuth client id for the refresh grant. */
  clientId?: string;
}): AuthPlugin {
  const tokenEndpoint = params?.tokenEndpoint ?? ANTHROPIC_TOKEN_ENDPOINT;

  return {
    id: "anthropic-oauth",
    label: "Anthropic OAuth Bearer",
    providers: ["anthropic"],
    priority: 50,

    match: (ctx) => ctx.credential.mode === "oauth" && ctx.credential.accessToken != null,

    interceptRequest: (ctx) => {
      const token = ctx.credential.accessToken;
      if (token) {
        ctx.headers["authorization"] = `Bearer ${token}`;
      }
    },

    refreshToken: async (credential: AuthPluginCredential): Promise<AuthPluginRefreshResult> => {
      if (!credential.refreshToken) {
        throw new Error("Cannot refresh Anthropic OAuth token: no refresh token available");
      }

      const body: Record<string, string> = {
        grant_type: "refresh_token",
        refresh_token: credential.refreshToken,
      };
      if (params?.clientId) {
        body["client_id"] = params.clientId;
      }

      const response = await fetch(tokenEndpoint, {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams(body).toString(),
      });

      if (!response.ok) {
        const text = await response.text().catch(() => "");
        throw new Error(
          `Anthropic OAuth token refresh failed (${response.status}): ${text}`.trim(),
        );
      }

      const data = (await response.json()) as {
        access_token: string;
        refresh_token?: string;
        expires_in?: number;
      };
      return {
        accessToken: data.access_token,
        refreshToken: data.refresh_token,
        expiresAt: data.expires_in ? Date.now() + data.expires_in * 1000 : undefined,
      };
    },
  };
}
