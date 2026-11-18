# Deployment (Phase 11)

UAT runs in AWS, in Mumbai (`ap-south-1`), from the Terraform in `infra/terraform`. Production will use the same
module with its own sizes once UAT has passed. Nothing has been applied yet: see [Status](#status).

## Design: the scaling layers we need now, and no more

The rule from our scaling notes: start simple, and add each layer only when its bottleneck appears, because every
layer is also something new that can fail. UAT is built as production will start.

| Layer | Now | Why |
|---|---|---|
| Separate database | **Yes**: RDS PostgreSQL 18, Multi-AZ standby, encrypted, 14 days point-in-time backups | Application and database sized separately; the standby takes over if a zone fails |
| Load balancer and several app servers | **Yes**: ALB, two instances each of the web app and backend on ECS Fargate across two zones | One instance can fail or be replaced while the other serves |
| Monitoring and alerts | **Yes**: CloudWatch alarms emailed through SNS | Errors, slowness and outages reach us before users |
| Cache (Redis) | No | Dashboard figures are cached in each instance for 60 seconds; nothing yet hits the database repeatedly enough |
| CDN | No | Next.js serves its static files with long cache headers; users are mostly in one country |
| Read replicas | No | Add when database CPU alarms under read load, after a cache |
| Message queue | No | Email and jobs already run in the background, from tables in the database, with retries |
| Sharding | No | Last, if ever |

The alarms on database CPU, memory and connections, and on response time, are the signal to look at the next layer.

### Statelessness

Any instance can serve any request:

- Sessions are signed tokens in cookies, checked by every instance with the same key from Secrets Manager.
- Files are in S3 (`STORAGE_PROVIDER=s3`), never on an instance's disk.
- Sign-in rate limits and lockouts are counted in the database, so all instances share them.
- Scheduled jobs take a lease in `scheduled_job_locks`, so each run happens on one instance only.

Two things are still per instance, and both catch up by themselves. A page connected for live notifications to one
instance hears changes made on another at its next poll (within 5 minutes). Dashboard figures cached on one instance
can trail a change made on another by up to 60 seconds.

### Traffic

```
internet ──443──▶ ALB (public subnets) ──3000──▶ web app (private) ──8080──▶ backend (private) ──5432──▶ RDS (isolated)
                                                                              └──▶ S3 (gateway endpoint), SendGrid, Razorpay (NAT)
```

Each security group accepts only the tier in front of it. The backend has no route from the load balancer; the web
app reaches it at `backend.uat.qualityos.internal` (Cloud Map DNS). The Razorpay webhook enters through the web app.
The ALB appends the client address to `X-Forwarded-For`, and the web app passes on that last entry, so audit and rate
limits see the real address.

### Failure, layer by layer

| Fails | What happens |
|---|---|
| One instance | The load balancer and DNS stop sending to it; ECS starts a replacement |
| A deploy that does not become healthy | The deployment circuit breaker rolls back to the last version |
| One zone | The other zone's instances serve; RDS fails over to its standby in a couple of minutes. With one NAT gateway, outbound email and payment calls stop until the zone returns (production: one per zone) |
| The database | Sign-in and pages fail fast (3 second pool timeout), readiness fails, alarms fire. Restore: below |
| SendGrid | Email stays queued and is retried |
| S3 | Uploads and downloads return an error; everything else works |

## Costs

Roughly US$300 a month for UAT as configured, estimated from on-demand prices in Mumbai: the Multi-AZ database about
$120, four Fargate instances about $115, NAT gateway about $45, load balancer about $20, the rest a few dollars. Data
transfer is extra. Setting `db_multi_az = false` saves about $55; keep it on if UAT is to prove failover.

## First deployment

Needs: AWS credentials for the account, Terraform 1.10 or later, Docker, `jq`, and a Route 53 hosted zone for the
domain. Every `terraform apply` shows its plan and asks before creating anything billable.

1. **Shared stack** (state bucket and image registries), once per account:
   ```bash
   cd infra/terraform/shared && terraform init && terraform apply
   ```
   Its state is local. Keep `terraform.tfstate` safe; it holds no secrets.
2. **Configure UAT**: in `infra/terraform/environments/uat`, copy `backend.hcl.example` to `backend.hcl` with the
   `state_bucket` output, and `terraform.tfvars.example` to `terraform.tfvars`. Both are git-ignored. Then
   `terraform init -backend-config=backend.hcl`.
3. **SendGrid**: verify the sender in `mail_from`, and create an API key with Mail Send permission.
4. **Deploy**: `infra/scripts/deploy.sh uat --no-wait` builds both images from the current commit, pushes them, and
   applies. The first apply takes 15 to 25 minutes, mostly the database. The backend cannot start until steps 5 and 6
   are done, so its first deployment fails, and that is expected.
5. **Secrets**: `SENDGRID_API_KEY=... infra/scripts/seed-secrets.sh uat` generates the passwords and keys and stores the
   provider keys. Add the `RAZORPAY_*` variables to take payments.
6. **Database roles**: `infra/scripts/db-setup.sh uat`.
7. **Start the backend**: `aws ecs update-service --cluster qualityos-uat --service backend --force-new-deployment`,
   then `aws ecs wait services-stable --cluster qualityos-uat --services backend frontend`. The first instance runs
   the migrations.
8. **Confirm**: AWS emails `alert_email` to confirm the alarm subscription. Open the `url` output and register.

## Deploying a change

Merge into `uat`, then from that commit: `infra/scripts/deploy.sh uat`. Images are built from `git archive HEAD`, so
uncommitted changes never reach an environment, and each image tag is the commit it was built from. New instances
start beside the old ones, and the old ones stop only when the new ones are healthy. To roll back, deploy the earlier
commit.

## Secrets

Everything secret is in one Secrets Manager secret, `qualityos-uat/app`. Terraform creates it with empty values and
never reads it back, so no secret is in Terraform state or git. The database admin password is RDS's own secret, read
only by the setup task. Instances read secrets when they start, so after a change, force a new deployment:
`aws ecs update-service --cluster qualityos-uat --service backend --force-new-deployment`.

- **Database passwords**: clear the value, run `seed-secrets.sh`, then `db-setup.sh`, then force a new deployment.
  Between the second and third steps, running instances cannot open new database connections.
- **Signing key**: rotate with `JWT_PREVIOUS_PUBLIC_KEY` as described in the application settings, or everyone is
  signed out.
- **`SECOND_FACTOR_KEY`**: never change it; losing it ends every operator's authenticator enrolment.

## Backups and restore

RDS keeps automated backups for 14 days, restorable to any second, plus a final snapshot if the database is ever
deleted. Deletion protection is on for the database and the load balancer. S3 keeps replaced and deleted files for 30
days through versioning.

To restore, create a new instance from a point in time (`aws rds restore-db-instance-to-point-in-time`), check it,
then point `DB_URL` at it. A timed restore test is still to do (see Status).

## Diagnosing

- Logs: CloudWatch groups `/qualityos/uat/backend`, `/frontend` and `/db-admin`, kept 30 days. The backend logs JSON.
- Shell in a running instance: `aws ecs execute-command --cluster qualityos-uat --task <id> --container backend --interactive --command sh`.
- Slow queries (over one second) are in the RDS PostgreSQL log, and in Performance Insights.

## Status

| Part | State |
|---|---|
| Environment profiles (dev, SIT, UAT) | Done (#151) |
| S3 file storage | Built and tested against an S3-compatible server (#153) |
| Terraform for UAT, scripts, this runbook | Written; `terraform validate` passes; database setup script tested on PostgreSQL 18 as a non-superuser admin; secret seeding tested against a stand-in for AWS. **Not yet applied** |
| First UAT deployment | Waiting for the owner's go-ahead, since it creates billable resources |
| Deploy pipeline in GitHub Actions | To do. Actions is disabled on the account, so deploys run from `deploy.sh` until then |
| Timed restore test | To do, after the first deployment |
| Production | After UAT passes: same module, one NAT gateway per zone, larger database |
