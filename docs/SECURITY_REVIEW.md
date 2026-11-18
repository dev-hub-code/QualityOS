# Security review

> Later review: [17 November 2026 audit](AUDIT_2026-11-17.md) covers the code merged since this one.

Code review and local testing of the `dev` branch on 1 November 2026. This is not an independent penetration test and
does not claim any certification.

## Checked, no change needed

| Area | What was checked |
|---|---|
| Tokens | RS256 only, issuer and audience validated. Membership and permissions are read on every request, not trusted from the token |
| Account lockout | Counted in the database, so it holds on every instance. Ten wrong passwords or second-factor codes from one address lock the account for 15 minutes from that address only. Fifty from all addresses together lock it for 15 minutes everywhere except addresses it signed in from in the last 90 days. The owner gets one email per lock period, and a password reset unlocks everything. `LoginLockoutIT` |
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
| The sign-in rate limit was counted in each instance's memory (18 November 2026 review) | A restart reset it, and each extra instance added its own allowance | Counted in PostgreSQL (`auth_attempts`, V68), shared by every instance and kept across restarts. If the database cannot be reached it counts in memory, per instance, rather than letting attempts through. `AuthRateLimitIT` |
| Ten wrong passwords from anywhere locked the account everywhere (follow-up review, 18 November 2026) | Anyone who knew an email address could keep its owner locked out | Counted per address, with a higher account-wide limit that spares addresses the owner signs in from. See Account lockout above |
| With `JWT_KEY_ID` set, the previous key was still looked up by its thumbprint (follow-up review) | Tokens from before a rotation were refused, so a rotation briefly forced every session to refresh | `JWT_PREVIOUS_KEY_ID` names the old key as its tokens do. Startup refuses equal ids. `JwtKeyRotationTest` |
| Every refused sign-in attempt still wrote to the database (follow-up review) | A flood of attempts loaded the connection pool before the limit applied | Once an address is over the limit, each instance refuses it for the rest of the minute without asking the database. `AuthRateLimitFilterTest` |
| JWT keys had no rotation process (18 November 2026 review) | Replacing the key ended every session at once, and an old key could not be retired on a set date | Tokens name their key in `kid`, a previous public key is trusted until a set date and refused after it. `JwtKeyRotationTest`. See [Rotating the signing key](#rotating-the-signing-key) |

## Open

| Item | Why it matters |
|---|---|
| The web app must sit behind a proxy that appends `X-Forwarded-For`, and the backend must not be reachable directly | Without that, a client can still choose the address seen by the backend. The UAT Terraform does both (see [DEPLOYMENT.md](DEPLOYMENT.md)); it holds once deployed |
| Facility limits are enforced in services, not RLS | A mistake in a new module could reopen a gap, so new modules need access tests |
| A CAPTCHA, or a growing delay, before sign-in from an address with several failures | The per-address lockout stops one address, but a large botnet trying one password per address is only limited by the account-wide lock. Worth adding before a public launch |

## Rotating the signing key

Access tokens are the only thing signed with the key, and live at most 30 minutes (15 for a normal session, up to 30
for a support session). Refresh tokens are random values stored hashed, not signed, so a rotation never signs anyone
out.

1. Generate a new RSA key pair (2048 bits or more), PKCS#8 private and X.509 public PEM.
   With more than one instance, every instance must trust the new key before any signs with it, or an instance not
   yet updated refuses the new tokens. So first deploy with the old pair still signing and the new public key as
   `JWT_PREVIOUS_PUBLIC_KEY`, trusted until well after step 2, then continue.
2. Deploy with the new pair as `JWT_PRIVATE_KEY` and `JWT_PUBLIC_KEY`, the old public key as `JWT_PREVIOUS_PUBLIC_KEY`,
   and `JWT_PREVIOUS_KEY_TRUSTED_UNTIL` at least one hour after every instance runs the new key, as an ISO instant
   such as `2026-12-01T10:00:00Z`. Startup refuses a previous key without that date, or one equal to the new key.
   If the old key had a `JWT_KEY_ID`, also set `JWT_PREVIOUS_KEY_ID` to that same value: its tokens carry it, and
   without it they are refused at once. Give the new key a different id, or none.
3. New tokens name the new key in their `kid` header: its RFC 7638 thumbprint, or `JWT_KEY_ID` if set. Tokens signed
   with the old key keep working until the date, and are refused from then on.
4. After the date, remove `JWT_PREVIOUS_PUBLIC_KEY`, `JWT_PREVIOUS_KEY_ID` and `JWT_PREVIOUS_KEY_TRUSTED_UNTIL`, and destroy the old private
   key.

If a private key may have leaked, skip the overlap: deploy the new pair with no previous key. Every access token then
stops working at once, and people are signed in again from their refresh tokens without noticing.

Not tested yet against a deployed environment: the steps above are covered by `JwtKeyRotationTest` only.
