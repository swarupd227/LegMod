param([switch]$Volumes)
if ($Volumes) {
    docker compose down -v
} else {
    docker compose down
}
