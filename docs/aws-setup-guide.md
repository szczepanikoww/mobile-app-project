# AWS setup guide (university showcase)

Status: draft v0.1 · 2026-10-29

A click-by-click walkthrough for creating the showcase environment described in [infrastructure-aws.md](infrastructure-aws.md). It uses the AWS console, so nothing has to be installed except the AWS CLI and Docker for pushing the image (step 9).

- **Region:** always `eu-central-1` (Europe, Frankfurt). Check the region selector in the top-right corner before every step; resources created in another region will not see each other.
- **Names** below use a `trainer-` prefix. They are placeholders; keep them consistent, because later steps refer to them.
- **Console labels** change from time to time. If a label differs slightly, look for the closest equivalent.
- Fill in the [values table](#values-to-write-down) as you go.

| # | Step | Time |
|---|---|---|
| 0 | Account, MFA and team users | 15 min |
| 1 | Budget alert and the extra credits | 30 min |
| 2 | Check the default VPC | 2 min |
| 3 | Security groups | 10 min |
| 4 | S3 bucket | 5 min |
| 5 | Parameters (DB password, JWT secret) | 5 min |
| 6 | RDS PostgreSQL | 10 min + ~10 min waiting |
| 7 | ECR repository | 3 min |
| 8 | IAM roles | 10 min |
| 9 | Push the backend image | 10 min |
| 10 | CloudWatch log group | 2 min |
| 11 | Target group and load balancer | 10 min |
| 12 | ECS cluster, task definitions, database roles and service | 20 min |
| 13 | Verify | 5 min |

---

## 0. Account, MFA and team users

1. Sign up at aws.amazon.com and choose the **Free plan**.
2. Signed in as root: account menu (top right) → **Security credentials** → **Assign MFA device**.
3. **IAM** → **User groups** → **Create group** `trainer-admins`, attach the policy `AdministratorAccess`.
4. **IAM** → **Users** → **Create user** for each teammate (and yourself): tick **Provide user access to the AWS Management Console**, add the user to `trainer-admins`. Each person signs in and adds MFA to their own user.
5. From now on, use your IAM user, not root.

Do not use AWS Organizations or IAM Identity Center: they are not available on the Free plan, and joining Organizations moves the account to the Paid plan.

## 1. Budget alert and the extra credits

1. **Billing and Cost Management** → **Budgets** → **Create budget** → template **Monthly cost budget**, amount e.g. `$40`, your team's email addresses. This alone earns $20 of credits.
2. Collect the other onboarding credits ($20 each), from the "Explore AWS" checklist on the console home page:
   - **EC2:** launch any free-tier instance, then **terminate** it.
   - **Lambda:** create a function with a **function URL**, open the URL once, then delete the function.
   - **Bedrock:** submit one prompt in the text playground.
   - **RDS:** done in step 6.
3. **Billing and Cost Management** → **Credits** shows the balance.

## 2. Check the default VPC

**VPC** → **Your VPCs**: there should be one VPC with **Default VPC: Yes**, and under **Subnets** one default subnet per Availability Zone (`eu-central-1a`, `b`, `c`). If it is missing: **Actions** → **Create default VPC**.

Every resource below goes into this VPC.

## 3. Security groups

**EC2** → **Security Groups** → **Create security group**, three times. VPC: the default VPC. Leave the outbound rules as they are (all traffic).

| Name | Inbound rule | Source |
|---|---|---|
| `trainer-alb-sg` | HTTP, port 80 | Anywhere-IPv4 `0.0.0.0/0` |
| `trainer-app-sg` | Custom TCP, port 8080 | Custom → `trainer-alb-sg` |
| `trainer-db-sg` | PostgreSQL, port 5432 | Custom → `trainer-app-sg` |

Create them in this order, because each one refers to the previous one. Add HTTPS (443) to `trainer-alb-sg` later only if the team gets a domain.

## 4. S3 bucket

1. **S3** → **Create bucket** → **General purpose**.
   - **Name:** globally unique, e.g. `trainer-showcase-media-<random>`.
   - **Object Ownership:** ACLs disabled.
   - **Block all public access:** on.
   - **Versioning:** disabled. **Default encryption:** SSE-S3.
2. Open the bucket → **Management** → **Create lifecycle rule**:
   - name `cleanup-uploads-tmp`, scope **Limit the scope … using filters**, prefix `uploads-tmp/`;
   - actions: **Expire current versions of objects** after `1` day, and **Delete expired object delete markers or incomplete multipart uploads** → incomplete multipart uploads after `1` day.

No CORS configuration is needed: CORS applies to browsers, not the Android app.

## 5. Parameters (passwords and secrets)

**Systems Manager** → **Parameter Store** → **Create parameter**, once per row. Tier **Standard** (free), type **SecureString**, KMS key `alias/aws/ssm`.

| Name | Generate with | Used for |
|---|---|---|
| `/trainer/db-password` | `openssl rand -hex 24` | RDS master user `trainer_admin` (steps 6 and 12) |
| `/trainer/db-owner-password` | `openssl rand -hex 24` | `trainer_owner`, the schema owner Flyway runs as |
| `/trainer/db-app-password` | `openssl rand -hex 24` | `trainer_app`, the DML-only role the API connects as |
| `/trainer/jwt-secret` | `openssl rand -hex 24` | signs the trainer API's demo tokens |
| `/trainer/data-key` | `openssl rand -base64 32` | AES-256 key for encrypted client information (must decode to exactly 32 bytes) |
| `/trainer/media-signing-key` | `openssl rand -hex 24` | signs media upload and playback links |
| `/trainer/fake-webhook-secret` | `openssl rand -hex 24` | the fake payment provider's webhook signature |

Use hex for the passwords: RDS rejects `/ @ "` and spaces, and the role script in step 12 puts them inside SQL quotes. You need the master password again in step 6. Losing `/trainer/data-key` makes the encrypted client information unreadable.

Why not the RDS-managed secret: RDS rotates that password every 7 days by default, and the running API would lose its database connection after a rotation unless the backend handles it. A parameter we set ourselves stays fixed, and Standard parameters are free.

## 6. RDS PostgreSQL

**RDS** → **Databases** → **Create database** → **Standard create**.

| Setting | Value |
|---|---|
| Engine | PostgreSQL, the newest version offered (16 or later) |
| Template | **Free tier** |
| DB instance identifier | `trainer-db` |
| Master username | `trainer_admin` |
| Credentials management | **Self managed**, paste the value of `/trainer/db-password` |
| Instance class | `db.t4g.micro` |
| Storage | gp3, `20` GiB, **storage autoscaling off** |
| Compute resource | Don't connect to an EC2 compute resource |
| VPC | default VPC, default DB subnet group |
| Public access | **No** |
| VPC security group | **Choose existing** → `trainer-db-sg`; remove `default` |
| Monitoring | leave Performance Insights / Enhanced Monitoring off |
| Additional configuration → **Initial database name** | `trainer` (otherwise only the `postgres` database exists) |
| Backup retention | the default the template allows |
| Encryption | on (default) |

**Create database**, then wait until the status is **Available** (about 10 minutes). Open it and copy the **Endpoint** (e.g. `trainer-db.xxxx.eu-central-1.rds.amazonaws.com`).

The API uses two database roles, as [database-design.md](database-design.md) §4 requires: Flyway migrates as `trainer_owner`, the API reads and writes as `trainer_app`. They are created in step 12, once ECS can run a task in the VPC. `V1` creates the `btree_gist`, `citext` and `pg_trgm` extensions; they are trusted extensions, so `trainer_owner` can create them as the database owner.

## 7. ECR repository

1. **ECR** → **Private registry** → **Repositories** → **Create repository**, name `trainer-backend`, tag mutability **Mutable**.
2. Open it → **Lifecycle policy** → **Create rule**: image status **Any**, **Image count more than** `5` → expire. Old images are then deleted automatically.
3. Copy the repository **URI**: `<account-id>.dkr.ecr.eu-central-1.amazonaws.com/trainer-backend`.

## 8. IAM roles

ECS uses two roles. **IAM** → **Roles** → **Create role** → trusted entity **AWS service** → service **Elastic Container Service** → use case **Elastic Container Service Task**, for both.

**Task execution role** (what ECS itself needs to start the container: pull the image, write logs, read the parameters)

1. Attach the managed policy `AmazonECSTaskExecutionRolePolicy`, name the role `trainerTaskExecutionRole`, create it.
2. Open it → **Add permissions** → **Create inline policy** → **JSON**, replace `<account-id>`, name it `read-trainer-parameters`:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "ssm:GetParameters",
      "Resource": "arn:aws:ssm:eu-central-1:<account-id>:parameter/trainer/*"
    }
  ]
}
```

**Task role** (what the Ktor code may do while running: use the media bucket)

1. Attach no managed policy, name the role `trainerTaskRole`, create it.
2. Add an inline policy `media-bucket-access`, replacing the bucket name:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"],
      "Resource": "arn:aws:s3:::<bucket-name>/*"
    },
    {
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::<bucket-name>"
    }
  ]
}
```

Presigned URLs the API creates are signed with this role, so they can do only what this policy allows.

## 9. Push the backend image

The image is [server/Dockerfile](../server/Dockerfile). It only copies the distribution Gradle builds on your machine (JDK 17 or 21; the Kotlin Gradle plugin rejects JDK 25), so it builds for ARM64 on an x86 machine without emulation.

1. Install the AWS CLI v2 and Docker.
2. In IAM, open your own user → **Security credentials** → **Create access key** → **Command Line Interface**. Run `aws configure` with that key and region `eu-central-1`. The key stays in `~/.aws` on your machine; never commit it.
3. Log Docker in to ECR:

```bash
aws ecr get-login-password --region eu-central-1 | docker login --username AWS --password-stdin <account-id>.dkr.ecr.eu-central-1.amazonaws.com
```

4. Build the distribution, then build and push the ARM64 image (the architecture must match step 12):

```bash
./gradlew -Ptrainer.ui=false :server:installDist
```

```bash
docker buildx build --platform linux/arm64 -t <repo-uri>:latest --push server
```

## 10. CloudWatch log group

**CloudWatch** → **Log groups** → **Create log group**: name `/ecs/trainer-api`, retention **2 weeks**.

Creating it yourself sets the retention (the default is "never expire") and avoids a startup error, because the execution role may write to a log group but not create one.

## 11. Target group and load balancer

**Target group:** **EC2** → **Target groups** → **Create target group**.

| Setting | Value |
|---|---|
| Target type | **IP addresses** (required for Fargate) |
| Name | `trainer-api-tg` |
| Protocol / port | HTTP / `8080` |
| VPC | default |
| Health check path | `/health` |
| Advanced health check | healthy threshold `2`, interval `15` s |

**Next**, register **no** targets (ECS adds them), **Create**. Then open it → **Attributes** → **Edit** → **Deregistration delay** `30` seconds (faster deploys).

**Load balancer:** **EC2** → **Load balancers** → **Create load balancer** → **Application Load Balancer**.

| Setting | Value |
|---|---|
| Name | `trainer-alb` |
| Scheme | Internet-facing, IPv4 |
| VPC | default; tick **all** Availability Zones (at least two) |
| Security groups | `trainer-alb-sg` only; remove `default` |
| Listener | HTTP : 80 → forward to `trainer-api-tg` |

**Create**, then open it → **Attributes** → **Edit** → **Connection idle timeout** `300` seconds (keeps chat WebSockets open). Copy its **DNS name** (`trainer-alb-….eu-central-1.elb.amazonaws.com`).

## 12. ECS cluster, task definition and service

**Cluster:** **ECS** → **Clusters** → **Create cluster**: name `trainer-cluster`, infrastructure **AWS Fargate (serverless)** only. Leave Container Insights off (it costs extra).

**Task definitions:** both are in [server/aws/](../server/aws/) as JSON. For each: **ECS** → **Task definitions** → **Create new task definition** → **Create new task definition with JSON**, replace the whole editor content with the file, replace `<account-id>`, `<rds-endpoint>` and `<alb-dns-name>`, **Create**.

- `task-db-init.json` (`trainer-db-init`): a one-off `psql` container that creates the two database roles (below).
- `task-api.json` (`trainer-api`): the API. ARM64, 0.5 vCPU / 1 GB, the roles from step 8, the log group from step 10, and every setting the server reads (see [server/README.md](../server/README.md)):

| Variable | Value |
|---|---|
| `TRAINER_PROFILE` / `AUTH_MODE` | `local` / `dev`: demo tokens (`Bearer dev.<subject>`), Flyway on start, synthetic demo seed. Outside `local` the server accepts only Firebase tokens, which the showcase does not use. |
| `DB_URL` | `jdbc:postgresql://<rds-endpoint>:5432/trainer` |
| `DB_OWNER_USER` / `DB_OWNER_PASSWORD` | `trainer_owner` / `/trainer/db-owner-password` |
| `DB_APP_USER` / `DB_APP_PASSWORD` | `trainer_app` / `/trainer/db-app-password` |
| `JWT_SECRET`, `DATA_KEY`, `MEDIA_SIGNING_KEY`, `FAKE_PAYMENT_WEBHOOK_SECRET` | from Parameter Store (step 5) |
| `DATA_KEY_ID` | `aws-1` |
| `PUBLIC_BASE_URL` | `http://<alb-dns-name>` (media links point here) |

The demo tokens are not authentication: anyone who knows the ALB address can act as a demo user. That is acceptable only because every record is synthetic.

Media is stored on the task's local disk (`/tmp`, set in the Dockerfile) because the server has no S3 storage yet: uploads disappear on every redeploy. The bucket (step 4) and the task role's S3 policy (step 8) are ready for when it does.

**Database roles** (once, before the service starts):

**ECS** → `trainer-cluster` → **Tasks** → **Run new task**: launch type **FARGATE**, task definition `trainer-db-init`, default VPC and subnets, security group `trainer-app-sg` only, **Public IP on**. **Create**. After about a minute the task stops; **Logs** shows `roles-created`. If it fails with `role "trainer_owner" already exists`, the roles were created by an earlier run.

**Service:** open `trainer-cluster` → **Services** → **Create**.

| Setting | Value |
|---|---|
| Compute options | **Launch type**, FARGATE, platform version LATEST |
| Application type | Service |
| Task definition | `trainer-api`, latest revision |
| Service name | `trainer-api` |
| Desired tasks | `1` |
| Deployment failure detection | **Use the deployment circuit breaker** + **Rollback on failures** |
| Networking → VPC / subnets | default VPC, all default subnets |
| Security group | `trainer-app-sg` only; remove `default` |
| Public IP | **On** (needed to pull from ECR without a NAT gateway) |
| Load balancing | Application Load Balancer → **Use an existing load balancer** `trainer-alb`; container `api 8080:8080`; **Use an existing listener** `80:HTTP`; **Use an existing target group** `trainer-api-tg` |
| Health check grace period | `120` seconds (time for the JVM and migrations to start) |

**Create.** The circuit breaker stops a broken deploy and rolls back to the last working revision instead of restarting forever.

## 13. Verify

1. **ECS** → `trainer-cluster` → `trainer-api` → **Tasks**: one task, status **Running**. The **Events** tab shows what ECS is doing.
2. **EC2** → **Target groups** → `trainer-api-tg` → **Targets**: one target, **healthy**.
3. In a browser: `http://<alb-dns-name>/health` returns `200`.
4. Point the Android **debug** build's base URL at `http://<alb-dns-name>`. Android blocks plain HTTP by default, so allow cleartext for that host only in the debug build's `network_security_config`.

### If something fails

| Symptom (task stopped reason or Events tab) | Likely cause |
|---|---|
| `CannotPullContainerError` | Wrong image URI or tag, or **Public IP** is off |
| `ResourceInitializationError … ssm` | Parameter ARN typo, or the inline policy on `trainerTaskExecutionRole` is missing |
| `ResourceInitializationError … log group` | The log group name in the task definition does not match step 10 |
| `exec format error` in the logs | Image architecture does not match the task definition (ARM64 vs X86_64) |
| Task runs, target **unhealthy**, task replaced in a loop | App not listening on `0.0.0.0:8080`, `/health` not returning 200, or `trainer-app-sg` does not allow 8080 from `trainer-alb-sg` |
| Database connection timeout in the logs | Wrong `DB_HOST`, or `trainer-db-sg` does not allow 5432 from `trainer-app-sg` |
| Database authentication failed | `trainer-db-init`: `/trainer/db-password` differs from the RDS master password. `trainer-api`: step 12 did not run, or the role passwords changed after it |
| `DATA_KEY must be 32 bytes` | `/trainer/data-key` is not `openssl rand -base64 32` output |
| `502` / `503` from the ALB | No healthy target yet; check the two rows above |

Application logs: **CloudWatch** → **Log groups** → `/ecs/trainer-api`.

---

## Everyday operations

**Deploy a new version:** push the image again (step 9), then start a new deployment:

```bash
aws ecs update-service --cluster trainer-cluster --service trainer-api --force-new-deployment
```

ECS starts a new task, waits until the ALB reports it healthy, then stops the old one. A change to environment variables needs a new task definition revision: **Task definitions** → `trainer-api` → **Create new revision**, then **Update service** with that revision.

**Pause between demos:**

```bash
aws ecs update-service --cluster trainer-cluster --service trainer-api --desired-count 0
```

```bash
aws rds stop-db-instance --db-instance-identifier trainer-db
```

**Resume:** start the database first and wait until it is **Available**, then set the desired count back to 1.

```bash
aws rds start-db-instance --db-instance-identifier trainer-db
```

```bash
aws ecs update-service --cluster trainer-cluster --service trainer-api --desired-count 1
```

RDS starts a stopped instance automatically after 7 days. The ALB and public IPv4 addresses cost about $1 a day even while paused; for a break of several weeks, delete the ALB and recreate it (step 11) later.

**Before the Free plan ends** (6 months after sign-up): take a `pg_dump` of the database and copy anything needed from the bucket, or upgrade to the Paid plan.

## Values to write down

| Value | Where it comes from | Used in |
|---|---|---|
| Account ID | account menu, top right | steps 8, 9, 12 |
| Bucket name | step 4 | steps 8, 12 |
| RDS endpoint | step 6 | step 12 |
| ECR repository URI | step 7 | steps 9, 12 |
| ALB DNS name | step 11 | step 13, Android debug config |

Do not write the DB password or JWT secret into this table or the repository; they live only in Parameter Store.
