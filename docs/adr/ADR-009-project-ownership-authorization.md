# ADR-009: Project ownership authorization

## Status

Accepted.

## Decision

Every newly created project has an `owner_user_id`. `ProjectAccessPolicy` grants read, write, and manage only to the owner. `ADMIN` receives no implicit cross-user access. Services for Brain, Roadmap, Tasks, Evidence, State, Guide, Prompts, and memory proposals authorize through the project application boundary.

## Consequences

Requests for another user's project return 404, preventing existence disclosure. Pre-identity projects remain preserved with a null owner and are inaccessible until an explicit administrative migration assigns a truthful owner.
