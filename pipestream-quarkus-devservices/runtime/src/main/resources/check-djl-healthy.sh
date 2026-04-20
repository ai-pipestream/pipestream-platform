#!/usr/bin/env bash
# check-djl-healthy.sh — exits 0 when the local djl-serving container
# reports Docker health status "healthy", non-zero otherwise. Used as the
# readiness probe for the dev-djl-serving process in process-compose.yaml.
#
# Kept as a dedicated script (rather than an inline exec.command in the
# yaml) because process-compose's interpolation collides with Docker's
# Go-template syntax {{.State.Health.Status}}.

set -u

CONTAINER="${DJL_SERVING_CONTAINER_NAME:-pipeline-djl-serving}"

status=$(docker inspect --format '{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null || true)

if [ "$status" = "healthy" ]; then
  exit 0
fi

echo "djl-serving: $CONTAINER status=${status:-missing}" >&2
exit 1
