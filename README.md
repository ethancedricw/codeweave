# CodeWeave

> An AI coding collaboration workspace that brings agents, code workspaces, approvals, and knowledge bases together.

[中文说明](README.zh-CN.md)

CodeWeave is an open-source workspace for team-oriented AI software development. It connects AI agents with real project workspaces, task execution, approvals, collaboration, audit trails, and knowledge bases—so AI can participate in the engineering workflow instead of only answering questions in a chat window.

## Product screenshots

| Login | Overview |
|---|---|
| ![CodeWeave login](docs/screenshots/login.png) | ![CodeWeave overview](docs/screenshots/overview.png) |

| Agent workbench | Projects and workspaces |
|---|---|
| ![Agent workbench](docs/screenshots/agent-workbench.png) | ![Projects and workspaces](docs/screenshots/projects.png) |

| Workspace settings |
|---|
| ![Workspace settings](docs/screenshots/settings.png) |

## Highlights

- AI agent sessions with project and workspace context
- Git repositories, snapshot projects, and shared workspaces
- Task execution, approval flows, handoffs, and conflict review
- Team members, roles, project access control, and audit activity
- OpenAI-compatible model configuration
- RAGFlow knowledge-base integration
- DeepSeek Harness-based agent runtime integration
- React frontend and Spring Boot backend

## Project layout

```text
codeweave/
├── codeweave-frontend/   # React + Vite frontend
├── codeweave-backend/    # Spring Boot backend
└── deepseek-harness/     # Customized DeepSeek Harness integration
```

## Requirements

- Java 17+
- Maven 3.9+
- Node.js 20+ and npm
- Docker Desktop
- Git
- PostgreSQL, Redis, and MinIO (started by Docker Compose)
- Optional: RAGFlow and a configured OpenAI-compatible model provider

## Quick start

Start the services in this order: infrastructure, DeepSeek Harness, backend, then frontend.

### 1. Start infrastructure services

```powershell
cd codeweave-backend
docker compose up -d
```

This starts PostgreSQL, Redis, and MinIO.

### 2. Install and start DeepSeek Harness

The customized Harness source is included in this repository. It requires Node.js 22.19+ and pnpm 11.7+.

```powershell
cd ..\deepseek-harness
corepack enable
corepack prepare pnpm@11.7.0 --activate
pnpm install
pnpm run build
pnpm dsh web --no-open
```

Keep this terminal running. The default Harness address is `http://127.0.0.1:3080`.

### 3. Start the backend

```powershell
cd ..\codeweave-backend
mvn spring-boot:run
```

The backend runs at `http://localhost:8080` by default.

### 4. Start the frontend

```powershell
cd codeweave-frontend
npm ci
npm run dev
```

The frontend runs at `http://localhost:5173` and proxies `/api` to the backend.

## Configuration

The repository contains local-development defaults so that contributors can get started quickly. Before exposing the system to a network or using it in production, change the database, Redis, MinIO, encryption-key, account, and model-provider settings.

Do not commit real API keys, tokens, private keys, or production credentials.

## Development

```powershell
# backend tests
cd codeweave-backend
mvn test

# frontend build
cd ..\codeweave-frontend
npm run build
```

## License

CodeWeave is released under the [MIT License](LICENSE).

The `deepseek-harness` directory contains a customized version based on the original DeepSeek Harness project. The original MIT license and attribution are preserved in [`deepseek-harness/LICENSE`](deepseek-harness/LICENSE).

## Project status

CodeWeave is under active development. Issues, feature ideas, documentation improvements, and pull requests are welcome.

