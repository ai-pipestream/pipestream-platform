#!/usr/bin/env bash
# Exit 0 when every required infra container reports Docker health status
# "healthy". Used as the readiness probe for the `dev-services` process in
# process-compose.yaml.
#
# Override the container list with REQUIRED_CONTAINERS (space-separated) if
# a particular service needs more or fewer dependencies.

set -u

DEFAULT_CONTAINERS="pipeline-consul pipeline-postgres pipeline-kafka pipeline-apicurio-registry"
CONTAINERS="${REQUIRED_CONTAINERS:-$DEFAULT_CONTAINERS}"

failed=0
for c in $CONTAINERS; do
  status=$(docker inspect --format '{{.State.Health.Status}}' "$c" 2>/dev/null || true)
  if [ "$status" != "healthy" ]; then
    echo "not-ready: $c=${status:-missing}" >&2
    failed=1
  fi
done

exit "$failed"
