#!/usr/bin/env bash
# start-dev-djl.sh — bring up a local djl-serving container for module-embedder.
#
# Detects the host environment and picks the right DJL Serving image variant.
# Idempotent: if pipeline-djl-serving is already running healthy, no-op.
# Joins the shared pipeline bridge so other containers can reach djl-serving
# by hostname; host-side apps reach it via localhost:${DJL_SERVING_HOST_PORT}.

set -u

CONTAINER_NAME="${DJL_SERVING_CONTAINER_NAME:-pipeline-djl-serving}"
HOST_PORT="${DJL_SERVING_HOST_PORT:-8090}"
NETWORK="${PIPELINE_NETWORK:-pipeline-shared-devservices_pipeline-test-network}"

already_healthy() {
  [ "$(docker inspect --format '{{.State.Health.Status}}' "$CONTAINER_NAME" 2>/dev/null)" = "healthy" ]
}

if already_healthy; then
  echo "djl-serving: $CONTAINER_NAME is already healthy on localhost:$HOST_PORT — nothing to do"
  exit 0
fi

# Clear out any stopped / unhealthy container of the same name so docker run
# below doesn't fail with a name conflict.
docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true

# ---- Environment detection ----
OS="$(uname -s)"
ARCH="$(uname -m)"

IMAGE=""
EXTRA_ARGS=()

case "$OS" in
  Linux)
    if command -v nvidia-smi >/dev/null 2>&1 && nvidia-smi >/dev/null 2>&1; then
      IMAGE="deepjavalibrary/djl-serving:latest-pytorch-gpu"
      EXTRA_ARGS+=(--gpus all)
      echo "djl-serving: detected Linux + NVIDIA GPU → $IMAGE"
    else
      # Intel integrated graphics → OpenVINO (deferred; see roadmap).
      # For now, fall through to CPU.
      IMAGE="deepjavalibrary/djl-serving:latest"
      echo "djl-serving: detected Linux, no NVIDIA GPU → CPU $IMAGE"
      echo "djl-serving: TODO: OpenVINO variant for Intel integrated graphics is not implemented yet"
    fi
    ;;
  Darwin)
    # Not validated on Apple Silicon yet. Exit with a clear message so users
    # hit a loud failure rather than a mystery-crashing container.
    echo "djl-serving: macOS ($ARCH) path is not implemented yet. See TODO in start-dev-djl.sh" >&2
    echo "djl-serving: planned — test latest CPU image on arm64, fall back to python mode if needed" >&2
    exit 2
    ;;
  *)
    echo "djl-serving: unsupported OS '$OS'" >&2
    exit 2
    ;;
esac

echo "djl-serving: pulling $IMAGE (first run may take a few minutes) ..."
docker pull "$IMAGE" >/dev/null

echo "djl-serving: starting $CONTAINER_NAME on localhost:$HOST_PORT"
docker run -d \
  --name "$CONTAINER_NAME" \
  --hostname djl-serving \
  --network "$NETWORK" \
  -p "${HOST_PORT}:8080" \
  --health-cmd='curl -f http://localhost:8080/ping || exit 1' \
  --health-interval=10s \
  --health-timeout=5s \
  --health-retries=5 \
  --health-start-period=90s \
  "${EXTRA_ARGS[@]}" \
  "$IMAGE" >/dev/null

echo "djl-serving: started. Register models via POST /models — see module-embedder/ADDING-MODELS.md"
echo "djl-serving: host URL → http://localhost:$HOST_PORT"
echo "djl-serving: bridge URL (for containers on $NETWORK) → http://djl-serving:8080"
