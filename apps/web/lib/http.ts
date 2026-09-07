/**
 * The shape every API call goes through.
 *
 * Two transports implement it: one for the browser (relative URLs, cookies, CSRF header) and one
 * for server components (absolute internal URL, forwarded cookies). The endpoint list is written
 * once, against this interface, so the two can never drift apart.
 */
export type Transport = <T>(path: string, init?: RequestInit) => Promise<T>;

export class ApiRequestError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = "ApiRequestError";
  }

  get isUnauthenticated(): boolean {
    return this.status === 401;
  }

  get isForbidden(): boolean {
    return this.status === 403;
  }

  /** The API reports a resource you may not see as missing, so this covers both. */
  get isNotFound(): boolean {
    return this.status === 404;
  }
}

/** The API being down is an expected state for a local tool, not a crash. */
export class ApiUnreachableError extends Error {
  constructor(cause: unknown) {
    super("Não foi possível falar com a API do VibeCode.");
    this.name = "ApiUnreachableError";
    this.cause = cause;
  }
}

export async function readResponse<T>(response: Response, path: string): Promise<T> {
  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new ApiRequestError(
      response.status,
      body?.code ?? "UNKNOWN_ERROR",
      body?.message ?? `A requisição para ${path} falhou`,
    );
  }
  if (response.status === 204) {
    return undefined as T;
  }
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}
