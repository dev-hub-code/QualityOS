# Security review

Code review and local testing of the `dev` branch on 1 November 2026. This is not an independent penetration test and
does not claim any certification.

## Checked, no change needed

| Area | What was checked |
|---|---|
| Tokens | RS256 only, issuer and audience validated. Membership and permissions are read on every request, not trusted from the token |
| Tenant context | Comes only from the verified token. Settings are local to each transaction, so they cannot reach the next user of a pooled connection |
| Switching organisation | Membership is checked again before a new session is issued |
| Row-level security | Every table with `tenant_id` is enabled and forced, except `refresh_tokens`, which is read before a tenant is known. The application role is not a superuser and cannot bypass RLS. Policies that allow every row belong to the migrator role only |
| Refresh tokens | Stored hashed, rotated on use. Reuse revokes the whole family |
| Passwords and reset | Argon2. Reset tokens are single use, and a reset or password change ends every session. Login answers the same way for an unknown email |
| Attachments | Access follows the parent record, types come from file content, downloads are `attachment` with `nosniff`, and storage keys are server generated |
| Browser session | Tokens stay in `httpOnly` cookies, and writes from another origin are refused |
| Permissions | `EndpointAuthorizationIT` fails if an endpoint has no permission rule and is not on its reviewed list |

## Found and fixed

| Finding | Effect | Fix |
|---|---|---|
| The web app forwarded `X-Forwarded-For` as the client sent it | A client could choose its own address, which escaped the login rate limit and put a false address in the audit log | Only the last entry, added by the proxy, is forwarded |

## Open

| Item | Why it matters |
|---|---|
| The web app must sit behind a proxy that appends `X-Forwarded-For`, and the backend must not be reachable directly | Without that, a client can still choose the address seen by the backend. Handle with production work |
| Facility limits are enforced in services, not RLS | A mistake in a new module could reopen a gap, so new modules need access tests |
| JWT keys have no rotation process | Handle with production work |
| The repository is public with no licence | The owner should decide how the code may be used |
