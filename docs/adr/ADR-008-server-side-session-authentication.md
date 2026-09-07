# ADR-008: Server-side session authentication

## Status

Accepted.

## Decision

The browser authenticates with Spring Security and a server-side HTTP session. The session identifier is kept only in an `HttpOnly` cookie; production enables `Secure`, `SameSite=Lax` limits cross-site delivery, login rotates the session identifier, and logout invalidates the session. No JWT or browser storage is used.

## Consequences

The backend remains the source of authentication truth. Other client types may receive a separate authentication mechanism later, without changing the internal `CurrentUser` contract.
