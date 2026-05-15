.PHONY: up down restart logs ps seed clean nuke build front back

# Load .env explicitly. Docker Compose's documented precedence puts the
# shell environment ABOVE the .env file, so a CI runner or IDE that
# exports ANTHROPIC_API_KEY="" (empty) silently shadows the real value
# in .env, and every AI agent ends up stubbed. Sourcing .env here
# pre-populates the shell so compose sees the right value either way.
# `set -a` exports every assignment automatically.
up:
	@set -a; [ -f .env ] && . ./.env; set +a; docker compose up -d
	@echo "Frontend:    http://localhost:$${PORT_FRONTEND:-3000}"
	@echo "API Gateway: http://localhost:$${PORT_GATEWAY:-8080}"
	@echo "Temporal UI: http://localhost:$${PORT_TEMPORAL_UI:-8088}"
	@echo "Neo4j:       http://localhost:$${PORT_NEO4J_HTTP:-7474}"
	@echo "MinIO:       http://localhost:$${PORT_MINIO_CONSOLE:-9001}"

down:
	docker compose down

restart: down up

logs:
	docker compose logs -f --tail=200

ps:
	docker compose ps

build:
	docker compose build

seed:
	docker compose exec prj-service curl -fsSL -X POST http://localhost:8081/internal/seed || true

clean:
	docker compose down -v

nuke: clean
	docker system prune -f
