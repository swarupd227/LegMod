# Atlas Migrate — Envestnet Accelerator

Local-Docker accelerator for solving two legacy migration problems:

1. **SOAP Migration** — Apache Axis 1.x / Axis2 / CXF / Metro → JAX-WS / JAX-RS, with byte-level wire parity preserved by replaying captured corpus
2. **Framework Uplift** — Spring 5→6, javax→jakarta, IBM-proprietary→standard, JDK 8/11→17/21, WAS→Liberty/Tomcat

This repo follows the Atlas Migrate product specification, scoped down to a
single-host Docker deployment for the Envestnet engagement.

---

## Phase 0 — what's running today

```
┌──────────────────────────────────────────┐
│  Frontend (React + Vite, port 3000)      │
│   • Workspace Dashboard, New Project     │
└──────────────────┬───────────────────────┘
                   │
┌──────────────────▼───────────────────────┐
│  API Gateway (Spring Cloud Gateway 8080) │
│   stub auth /api/v1/me                   │
└──┬───────────────────────────────┬───────┘
   │                               │
┌──▼──────────────┐         ┌──────▼────────────┐
│ prj-service     │         │ llm-gateway       │
│ workspaces      │         │ Anthropic SDK     │
│ projects        │         │ /api/v1/llm       │
│ gates           │         │                   │
└──┬──────────────┘         └────────┬──────────┘
   │                                 │
┌──▼─────────┐ ┌────────┐ ┌──────────▼─┐ ┌────────┐ ┌──────────┐
│ Postgres   │ │ Neo4j  │ │ Redis      │ │ MinIO  │ │ Temporal │
└────────────┘ └────────┘ └────────────┘ └────────┘ └──────────┘
```

Plus two demo legacy projects under `samples/`:
- `samples/soap-axis13-demo/` — Broadridge MF Axis 1.3 service
- `samples/spring5-jakarta-demo/` — javax-era Spring 5 order-management app

---

## Quick start

### Prerequisites
- Docker Desktop (Windows/Mac/Linux). Compose v2.
- Anthropic API key

### Bring it up

```powershell
# from C:\Envestnet
copy .env.example .env
# edit .env and paste your ANTHROPIC_API_KEY

# either ...
.\up.ps1

# ... or, if you have GNU make:
make up
```

The first run takes ~5 minutes (Maven downloads dependencies for three
Spring services and Vite installs node modules). Subsequent boots are fast.

Once `docker compose ps` shows everything healthy:

| Service       | URL                              |
|---------------|----------------------------------|
| Frontend      | http://localhost:3000            |
| API Gateway   | http://localhost:8080/api/v1/me  |
| Temporal UI   | http://localhost:8088            |
| Neo4j Browser | http://localhost:28474           |
| MinIO Console | http://localhost:28001           |

> Atlas's backing-store host ports use the **28xxx range** so the stack can
> run alongside other local Docker projects (e.g. NHEP on 1xxxx). Override
> any of them in `.env` if you have a conflict.

The dashboard starts empty. Click **New Project** to create the SOAP demo
project (Broadridge MF, Axis 1.3 → JAX-WS RI 2.3.2). It will appear with
Stage A pending.

### Tear down

```powershell
.\down.ps1            # stops containers, keeps volumes
.\down.ps1 -Volumes   # also wipes Postgres/Neo4j/MinIO data
```

---

## Repo layout

```
C:\Envestnet\
├── docker-compose.yml         # entire stack
├── .env.example               # copy to .env
├── up.ps1, down.ps1           # Windows helpers
├── Makefile                   # equivalent for make users
│
├── infra/
│   ├── postgres/init.sql      # schemas: prj, prov, recon, gen, diff, cap
│   ├── neo4j/init.cypher      # graph constraints
│   ├── minio/bootstrap.sh     # creates corpus + artifact buckets
│   └── temporal/dynamicconfig
│
├── services/
│   ├── api-gateway/           # routes, stub auth
│   ├── prj-service/           # workspaces, projects, gates
│   └── llm-gateway/           # Anthropic SDK wiring (Opus/Sonnet routing)
│
├── frontend/                  # React 18 + Tailwind + TanStack Query
│   └── src/
│       ├── screens/           # WorkspaceDashboard + placeholders
│       ├── components/        # Layout (top bar, left nav, status bar)
│       └── api/client.ts
│
└── samples/
    ├── soap-axis13-demo/      # SOAP migration input fixture
    └── spring5-jakarta-demo/  # Framework uplift input fixture
```

---

## Roadmap

| Phase | Adds | Status |
|-------|------|--------|
| **0** Foundation | Compose stack, dashboard, demo projects | ✅ done |
| **1** SOAP Stages A & B | `arch-service`, `cap-service`, `ast-mcp`, `graph-mcp`, Code Archaeology + Runtime Capture screens | next |
| **2** SOAP Stages C & D | `recon-service`, `gen-service`, A-WSDL synthesis, Code Generation Studio | |
| **3** SOAP Stage E + Provenance | `diff-service`, Differential Test Lab, `prov-service` audit export | |
| **4** Framework Uplift slice | `uplift-service`, `rewrite-mcp`, Heatmap, Recipe Authoring | |
| **5** Polish | Reports & Deliverables Hub, closure-doc generator, end-to-end demo | |

---

## Troubleshooting

**Frontend can't reach the API.** Rebuild after `.env` changes:
`.\up.ps1 -Build` or `make build && make up`.

**Spring services exit immediately.** They wait on Postgres health — give it
~20s on first boot. `docker compose logs prj-service` will show the JDBC URL
and any error.

**Temporal won't start.** Confirm port 7233 isn't in use; the auto-setup
container creates the `temporal` and `temporal_visibility` databases on first
boot.

**LLM Gateway returns `"stub": true`.** That's expected when
`ANTHROPIC_API_KEY` is missing or blank in `.env`. Set it and restart the
`llm-gateway` container.
