# CodeWeave Backend

Java Spring Boot control plane for the CodeWeave enterprise AI collaboration workbench.

Stack: Java 17 locally (upgrade target Java 21), Spring Boot, MyBatis-Plus, Redis, Redis Stream, JWT and Sa-Token.

Configuration is split into `application-local-win.yml`, `application-local.yml`, and `application-prod.yml`. Windows uses `local-win` by default; macOS/Linux can start with `-Dspring.profiles.active=local`.

## Run locally

```text
docker compose up -d
mvn spring-boot:run
```

The local profile starts on `http://localhost:8080`. The compose file starts PostgreSQL and Redis with the values already written in `application-local.yml`; Redis is used for Sa-Token sessions, session events, shares, and task queues.

DSH is an independent process. Install the web authentication plugin once inside the DeepSeek Harness checkout:

```text
dsh plugin --profile web add dsh-web-startup-auth@latest
```

Start DSH separately with the Natapp host trusted by its web security:

```text
dsh web --trusted-host xaecb942.natappfree.cc
```

Then start the backend. Keep `codeweave.agent.dsh.auto-start` set to `false`; restarting the backend will not stop or restart DSH.

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
