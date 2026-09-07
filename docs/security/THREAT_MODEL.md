# Initial Threat Model

| Threat | Impact | Current mitigation | Remaining gap |
|---|---|---|---|
| Credential theft | Account and project takeover | Passwords use delegated adaptive hashing; raw passwords are never stored or returned | MFA and compromised-password screening are future work |
| Password leakage | Cross-user exposure and account reuse | Hash-only schema, safe DTOs, no password logging, bounded passphrase policy | Formal log redaction tests and secret scanning remain |
| Session hijacking | Unauthorized project access | `HttpOnly` session cookie, production `Secure`, `SameSite=Lax`, fixation protection, invalidating logout | Distributed revocation and device/session management remain |
| CSRF | Unauthorized mutation through the user's browser | Spring Security CSRF with cookie/header pairing on all unsafe methods, including logout | Deployment must preserve same-origin and TLS |
| IDOR | Cross-user reads or writes | Central `ProjectAccessPolicy`; project children authorize transitively; foreign resources return 404 | Future memberships need equally explicit policy tests |
| Privilege escalation | User gains platform or project privileges | Registration always assigns `USER`; `ADMIN` has no cross-project shortcut | Administrative role assignment is intentionally absent |
| Account enumeration | Discovery of registered users | Login failures share the same status, code, and message | Registration conflict still reveals an existing account by product necessity; rate limiting is pending |
| Sensitive logging | Credentials or tokens enter logs | Structured events omit passwords, hashes, CSRF tokens, and session IDs | Production retention, PII minimization, and centralized audit storage remain |
| Cross-user data exposure | Project Brain, roadmap, tasks, or evidence leak | Owner-scoped queries plus service-layer authorization and Alice/Bob IDOR tests | Organizations and sharing are outside scope |
| Future LLM secret exposure | Provider secrets enter prompts or model output | No provider credentials exist; prompt context excludes environment and secret material | Future `ProviderAccount` secrets require authenticated ownership and encryption at rest |

## Explicit future rules

Provider accounts will belong to an authenticated user or organization. API keys, OAuth tokens, and refresh tokens must never be stored as plaintext. Login and registration need rate limiting in the hardening phase; no rate limiter is claimed in the current implementation.
