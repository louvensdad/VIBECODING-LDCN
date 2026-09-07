import { cookies } from "next/headers";
import { createApi } from "./endpoints";
import { ApiRequestError, ApiUnreachableError, readResponse } from "./http";

/**
 * The API as a server component sees it.
 *
 * Server-side fetch does not inherit the browser's cookies, so the incoming session cookie is
 * forwarded explicitly. Without this a signed-in user's page would render as anonymous — and,
 * worse, would look like a bug rather than a security decision.
 */
const API_INTERNAL_URL = process.env.API_INTERNAL_URL ?? "http://localhost:8080";

async function serverRequest<T>(path: string, init?: RequestInit): Promise<T> {
  const cookieHeader = (await cookies()).toString();

  let response: Response;
  try {
    response = await fetch(`${API_INTERNAL_URL}${path}`, {
      ...init,
      headers: {
        "Content-Type": "application/json",
        ...(cookieHeader ? { cookie: cookieHeader } : {}),
        ...init?.headers,
      },
      cache: "no-store",
    });
  } catch (cause) {
    throw new ApiUnreachableError(cause);
  }

  return readResponse<T>(response, path);
}

export const serverApi = createApi(serverRequest);

/**
 * Runs a read and reports failure instead of throwing, so a page can render a real state — signed
 * out, not found, API down — rather than an error boundary.
 */
export async function tryLoad<T>(
  load: () => Promise<T>,
): Promise<
  | { ok: true; data: T }
  | { ok: false; reason: "unreachable" | "unauthenticated" | "denied" | "error"; message: string }
> {
  try {
    return { ok: true, data: await load() };
  } catch (error) {
    if (error instanceof ApiUnreachableError) {
      return { ok: false, reason: "unreachable", message: error.message };
    }
    if (error instanceof ApiRequestError) {
      if (error.isUnauthenticated) {
        return { ok: false, reason: "unauthenticated", message: error.message };
      }
      // 404 covers both "does not exist" and "not yours" — the API does not distinguish, and
      // neither should the page.
      return {
        ok: false,
        reason: error.isNotFound || error.isForbidden ? "denied" : "error",
        message: error.message,
      };
    }
    throw error;
  }
}
