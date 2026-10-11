# CodeWeave Frontend

CodeWeave / 织码协作台 is the React workbench for enterprise AI collaboration.

## Run locally

```text
npm install
npm run dev
```

The Vite server runs on `http://localhost:5173` and proxies `/api` to the Java backend at `http://localhost:8080`.

## Product surfaces

- Login and enterprise SSO entry.
- Overview dashboard for agents, approvals, usage, projects, and team activity.
- Three-pane Agent workbench with files, Harness conversation, execution nodes, collaborators, and share actions.
- Git, snapshot, and non-Git project workspaces.
- Conflict center with three-way review and handoff controls.
- Audit and activity view for enterprise traceability.

The visual system follows the Stitch direction: restrained dark blue-gray surfaces, cyan action color, emerald operational state, amber approval state, rose risk state, and mono typography for runtime details.
