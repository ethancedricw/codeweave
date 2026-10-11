# CodeWeave Backend

Java Spring Boot control plane for the CodeWeave enterprise AI collaboration workbench.

Stack: Java 17 locally (upgrade target Java 21), Spring Boot, MyBatis-Plus, Redis, Redis Stream, JWT and Sa-Token.

Configuration is split into `application-local.yml` and `application-prod.yml`. Values are written directly in YAML; environment-variable placeholders are intentionally not used.

## Run locally

```text
docker compose up -d
mvn spring-boot:run
```

The local profile starts on `http://localhost:8080`. The compose file starts PostgreSQL and Redis with the values already written in `application-local.yml`; Redis is used for Sa-Token sessions, session events, shares, and task queues.

In the local profile, the backend starts and stops the Agent runtime with itself. It uses Node 22 or newer from the configured path, launches the DeepSeek Harness checkout, and reads the printed authentication URL automatically. Keep the `working-directory` and `node-command` values in `application-local.yml` aligned with your machine:

```text
export PATH="$HOME/.nvm/versions/node/v22.19.0/bin:$PATH"
cd /Users/alex/codeweave-backend
mvn spring-boot:run
```

Set `auto-start: false` if you prefer to manage DSH as a separate process.

## Current API surface

- `GET /api/health` — service health.
- `POST /api/auth/login` — demo enterprise login, returns a JWT.
- `POST /api/auth/logout` and `GET /api/auth/me` — session lifecycle.
- `GET /api/sessions` — collaboration sessions.
- `POST /api/sessions/{id}/events` — append a session event to Redis Stream `cw:session:events`.
- `GET /api/workspace/summary` — dashboard metrics and Git/non-Git projects.
- `POST /api/workspace/tasks` — enqueue a collaboration task.
- `POST /api/workspace/shares` — create a 24-hour read-only share link.

The next persistence slice adds MyBatis-Plus entities and mappers for organizations, members, projects, workspaces, tasks, shares, approvals, conflicts, and audit entries. Harness events remain append-only and are projected into those business tables.
