# ADR-011: Deny-by-default resource access

## Status

Accepted.

## Decision

Only registration, login, CSRF bootstrap, and minimal health are public. Every other request requires authentication, and resource services additionally verify project ownership. Platform administrators have no implicit access to user projects.

## Consequences

Adding an endpoint without an explicit public rule makes it authenticated automatically. Adding a project child resource still requires using the centralized project access boundary.
