# ADR-010: CSRF protection for the web client

## Status

Accepted.

## Decision

Spring Security CSRF protection remains enabled. The API issues `XSRF-TOKEN`; the centralized browser client reads it and echoes it as `X-XSRF-TOKEN` on unsafe requests. Login, registration, mutations, and logout require a valid token.

## Consequences

The frontend and API operate as one logical origin through the Next.js `/api/*` rewrite. No wildcard credentialed CORS policy is configured.
