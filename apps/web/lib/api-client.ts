"use client";

import { createApi } from "./endpoints";
import { ApiRequestError, ApiUnreachableError, readResponse } from "./http";

/**
 * The API as the browser sees it.
 *
 * Relative URLs, because the app proxies /api through its own origin. Cookies travel with every
 * request, and every unsafe request carries the CSRF token — read from the cookie the server set,
 * echoed back in a header. A cross-site page can cause the cookie to be sent but cannot read it, so
 * it cannot produce the header; that gap is the protection.
 */
const CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";
const UNSAFE_METHODS = new Set(["POST", "PUT", "PATCH", "DELETE"]);

function readCsrfCookie(): string | null {
  const match = document.cookie.match(new RegExp(`(?:^|; )${CSRF_COOKIE}=([^;]*)`));
  return match ? decodeURIComponent(match[1]) : null;
}

/** Asks the server for a token when the page loaded without one. */
async function ensureCsrfToken(): Promise<string | null> {
  const existing = readCsrfCookie();
  if (existing) {
    return existing;
  }
  try {
    await fetch("/api/auth/csrf", { credentials: "same-origin", cache: "no-store" });
  } catch {
    return null;
  }
  return readCsrfCookie();
}

/** Called when the API says the session is gone, so the app can react in one place. */
type SessionExpiredHandler = () => void;
let onSessionExpired: SessionExpiredHandler = () => {};

export function setSessionExpiredHandler(handler: SessionExpiredHandler) {
  onSessionExpired = handler;
}

async function clientRequest<T>(path: string, init?: RequestInit): Promise<T> {
  const method = (init?.method ?? "GET").toUpperCase();
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...((init?.headers as Record<string, string>) ?? {}),
  };

  if (UNSAFE_METHODS.has(method)) {
    const token = await ensureCsrfToken();
    if (token) {
      headers[CSRF_HEADER] = token;
    }
  }

  let response: Response;
  try {
    response = await fetch(path, {
      ...init,
      method,
      headers,
      credentials: "same-origin",
      cache: "no-store",
    });
  } catch (cause) {
    throw new ApiUnreachableError(cause);
  }

  try {
    return await readResponse<T>(response, path);
  } catch (error) {
    // One place decides what an expired session means. Signing in is itself a 401 on bad
    // credentials, so that path is excluded or the login form would bounce the user to itself.
    if (
      error instanceof ApiRequestError &&
      error.isUnauthenticated &&
      !path.startsWith("/api/auth/login") &&
      !path.startsWith("/api/auth/register")
    ) {
      onSessionExpired();
    }
    throw error;
  }
}

export const api = createApi(clientRequest);
export { ApiRequestError, ApiUnreachableError };
