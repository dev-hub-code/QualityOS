# Environments

Each environment is a Spring profile, chosen with `SPRING_PROFILES_ACTIVE`, and has a branch that it is deployed from.
Work goes into `dev` through pull requests, then moves up by merging one branch into the next.

| Environment | Profile | Branch | Built on | Email | Subjects |
|---|---|---|---|---|---|
| Your machine | `local` (default) | any | | Mailpit, nothing leaves | unchanged |
| Development | `dev` | `dev` | `local` | Mailpit, nothing leaves | `[DEV] …` |
| System integration testing | `sit` | `sit` | `local` | Mailpit, nothing leaves | `[SIT] …` |
| User acceptance testing | `uat` | `uat` | `prod` | Real, through SendGrid | `[UAT] …` |
| Production | `prod` | not yet | | Set when production work starts | unchanged |

`dev` and `sit` keep the local profile's conveniences: health details, OpenAPI, and throwaway sign-in keys when none
are set, so sessions end when the backend restarts. `uat` brings the `prod` profile with it, so it is set up as
production will be: no OpenAPI, JSON logs, and it does not start without real JWT keys (`JWT_PRIVATE_KEY`,
`JWT_PUBLIC_KEY`), `SECOND_FACTOR_KEY`, verified email addresses before sign-in, and a SendGrid key.

Promoting a change: merge `dev` into `sit` once it passes on dev, and `sit` into `uat` once integration testing passes.

## Email

Mailpit catches every email in local, dev and SIT; read them on its web page (port `MAILPIT_PORT`, 8025 by default).
Subjects carry the environment's name everywhere but local and production, so a test email is never taken for a real
one.

UAT sends real email through SendGrid's v3 Mail Send API:

1. In SendGrid, verify the sending address (Sender Authentication), and create an API key with Mail Send permission.
2. Set `SENDGRID_API_KEY`, and `MAIL_FROM` to the verified address, such as `ProductionOS <no-reply@example.com>`.
3. To check the key without delivering anything, set `SENDGRID_SANDBOX=true`; SendGrid then accepts and validates
   each message but sends none.

Click and open tracking are turned off on every message, because SendGrid would otherwise send the one-time sign-in,
invitation and password reset links through its own address. A message SendGrid refuses or does not answer is kept
and sent again, as with SMTP. Setting `MAIL_PROVIDER=smtp` in UAT goes back to SMTP if ever needed.
