# Backend: running and hosting

The backend is a single Ktor service (`backend/`) with a PostgreSQL database. It holds no secrets in
code: everything is configured through environment variables. It is self-hosted on purpose, so an
agency can run it on an Iranian provider or on its own machine instead of depending on foreign cloud
services that may be blocked or sanctioned for Iranian users.

## Configuration

All variables are listed with explanations in [`backend/.env.example`](../backend/.env.example).

| Variable | Required | Default | Meaning |
|---|---|---|---|
| `DATABASE_URL` | yes | | JDBC URL, e.g. `jdbc:postgresql://db:5432/melkcrm` |
| `DATABASE_USER`, `DATABASE_PASSWORD` | usually | | Database credentials |
| `DATABASE_POOL_SIZE` | no | 10 | Maximum open database connections |
| `JWT_SECRET` | yes | | At least 32 random characters (`openssl rand -base64 48`); changing it signs everyone out |
| `JWT_ISSUER`, `JWT_AUDIENCE` | no | `melkcrm`, `melkcrm-app` | Token claims |
| `ACCESS_TOKEN_MINUTES` | no | 15 | Access-token lifetime |
| `REFRESH_TOKEN_DAYS` | no | 30 | Refresh-token lifetime (rotated on every use) |
| `PORT` | no | 8080 | HTTP port |
| `IMAGE_DIR` | no | `./data/images` | Where property photos are stored |
| `BOOTSTRAP_AGENCY_NAME`, `BOOTSTRAP_ADMIN_LOGIN`, `BOOTSTRAP_ADMIN_PASSWORD` | first start only | | Create the first agency and its owner/admin when the database has no agency yet. Password ≥ 12 characters. Remove after the first start. |

The server refuses to start when a required variable is missing or `JWT_SECRET` is too short.

## Local development

Without Docker (PostgreSQL installed locally):

```bash
createuser -P melkcrm && createdb -O melkcrm melkcrm
cd backend && cp .env.example .env          # edit the values
set -a && . ./.env && set +a
../gradlew -p .. :backend:run                # or :backend:installDist and build/install/backend/bin/backend
curl http://localhost:8080/health            # {"status":"ok"}
```

With Docker:

```bash
cd backend && cp .env.example .env           # edit the values
../gradlew -p .. :backend:installDist
docker compose up -d --build
docker compose logs -f backend
```

Flyway creates and migrates the schema on every start. Then, in the app: Settings → Account → server
`http://10.0.2.2:8080` (Android emulator) and the bootstrap login. Plain `http` is accepted by the app
only for `10.0.2.2` and `localhost`; real servers must use HTTPS.

Tests: `./gradlew :backend:test` uses an in-memory H2 database in PostgreSQL mode. To run the same tests
against PostgreSQL, set `MELK_TEST_POSTGRES_URL` to a JDBC URL whose user may create databases (each
test gets its own database):

```bash
MELK_TEST_POSTGRES_URL='jdbc:postgresql://localhost:5432/postgres?user=postgres&password=...' ./gradlew :backend:test
```

## Production deployment

1. **A Linux server** reachable by the agency's phones: a VPS at an Iranian provider (for example
   ArvanCloud, ParsPack, Asiatech, or a PaaS such as Liara that runs Docker images), or a machine in the
   office with a fixed address. Hosting inside Iran keeps the service reachable when international
   connectivity is disrupted. None of these providers has been tested with this project; any host that
   runs a JVM or Docker and PostgreSQL works.
2. **Images.** Docker Hub may not be reachable from Iranian servers. Either use the registry mirror
   your provider documents, or build and export the images elsewhere and load them on the server:
   ```bash
   docker build -t melkcrm-backend backend && docker pull postgres:16
   docker save melkcrm-backend postgres:16 | gzip > melkcrm-images.tar.gz
   # copy to the server, then: gunzip -c melkcrm-images.tar.gz | docker load
   ```
   Without Docker: install JDK 17+ and PostgreSQL 14+, copy `backend/build/install/backend/`, and run
   `bin/backend` under systemd with the variables in an `EnvironmentFile` readable only by that user.
3. **TLS.** Put a reverse proxy in front and expose only port 443. `docker-compose.yml` binds the
   backend to `127.0.0.1:8080` for this reason. Minimal nginx site:
   ```nginx
   server {
       listen 443 ssl http2;
       server_name crm.example-agency.ir;
       ssl_certificate     /etc/letsencrypt/live/crm.example-agency.ir/fullchain.pem;
       ssl_certificate_key /etc/letsencrypt/live/crm.example-agency.ir/privkey.pem;
       client_max_body_size 6m;          # photos are limited to 5 MB by the server
       location / {
           proxy_pass http://127.0.0.1:8080;
           proxy_set_header Host $host;
           proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
           proxy_set_header X-Forwarded-Proto https;
       }
   }
   ```
   Let's Encrypt works if the domain resolves publicly; otherwise use a certificate from an Iranian CA
   or your provider. The app needs a certificate that Android trusts.
4. **First start.** Set the three `BOOTSTRAP_*` variables, start once, sign in from the app as that admin,
   change the password (Settings → Account), then remove the variables. Create agent and manager
   accounts in Settings → Agents.
5. **Firewall.** Allow 443 (and 22 for administration) only. PostgreSQL must not be reachable from the
   internet; in the compose file it has no published port.

## Backups

Two things hold data: the PostgreSQL database and the `IMAGE_DIR` folder (the `images` volume in
Docker). Back up both, keep copies off the server, and test a restore.

Run from the `backend/` folder (both commands were tried against the compose setup):

```bash
# daily database dump (cron), kept for 30 days
docker compose exec -T db pg_dump -U melkcrm -Fc melkcrm > /backup/melkcrm-$(date +%F).dump
find /backup -name 'melkcrm-*.dump' -mtime +30 -delete
# photos
docker compose exec -T backend tar czf - -C /data/images . > /backup/images-$(date +%F).tgz
```

Restore into an empty database with `pg_restore -U melkcrm -d melkcrm --clean --if-exists <file>`.
Dumps contain personal data and confidential prices: encrypt them (for example `gpg --symmetric`) before
copying them anywhere, and restrict who can read the backup location.

## Upgrades

Stop the service, back up, deploy the new build, start. Flyway applies new migrations automatically and
refuses to start if the database is in an unexpected state. Never edit an applied migration; add a new
`V2__...sql` instead.

## API overview

All endpoints except `/health` and `/api/v1/auth/*` need `Authorization: Bearer <access token>`.
Errors are JSON `{"error": "<code>", "detail": ...}`; the app translates the codes.

| Method and path | Purpose | Permission |
|---|---|---|
| `GET /health` | Liveness | none |
| `POST /api/v1/auth/login` | Login → access + refresh token | rate-limited; account locks after 5 failures for 15 minutes |
| `POST /api/v1/auth/refresh` | Rotate the refresh token | reuse of an old token revokes the family |
| `POST /api/v1/auth/logout` | Revoke the refresh token family | |
| `GET /api/v1/me`, `POST /api/v1/me/password` | Current user, change password (signs out other devices) | |
| `POST /api/v1/sync/push` | Up to 500 changes with base versions | per record: agency, role, assignment, confidential fields |
| `GET /api/v1/sync/pull?cursor=&limit=` | Changes visible to this user since the cursor | |
| `GET /api/v1/records/{type}/{id}` | One record as this user may see it | |
| `GET`/`PUT /api/v1/agency/config` | Agency policy and matching configuration | write: owner/admin |
| `GET`/`POST /api/v1/users`, `PATCH /api/v1/users/{id}` | Accounts | write: owner/admin |
| `GET /api/v1/audit?entityId=` | Audit log | owner/admin or manager |
| `POST /api/v1/properties/{id}/images`, `GET /api/v1/images/{id}` | Photos (JPEG/PNG/WebP, ≤ 5 MB) | write / read access to the property |
| `GET /api/v1/export/customers.csv`, `/properties.csv` | CSV export (UTF-8 with BOM, formula injection neutralized) | export permission; audited |

## Security behaviour

- Passwords: bcrypt (cost 12). Minimum 10 characters and not only digits for accounts created later.
- Every query is scoped by the caller's agency; requests for another agency's records behave as not found
  or are rejected and logged as a permission refusal.
- Roles: owner/admin (everything), manager (own team's records and reports, reassign, merge), agent
  (own and unassigned records; agency inventory read-only and other agents' customers masked, if the
  agency allows it). Confidential fields: owner/admin, assigned agent, and the agent's team manager.
- Logs contain method, path and status only, never request bodies, so personal data does not reach logs.
- Responses carry `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`
  and `Cache-Control: no-store`.
