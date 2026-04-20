#!/usr/bin/env bash
# register-dev-djl-models.sh — register the embedder e2e test models against
# the local djl-serving container.
#
# Idempotent. Skips models that are already loaded. Blocks on the target
# djl-serving being reachable so it can be chained as a process-compose
# dependency after dev-djl-serving.
#
# Override the set of models with DJL_E2E_MODELS — space-separated list of
# entries in the form  <serving-name>:<huggingface-identifier>.
#
# Defaults match the two models consumed by module-embedder's e2e fixture
# dump: minilm and paraphrase_MiniLM_L3_v2.

set -eu

HOST_PORT="${DJL_SERVING_HOST_PORT:-8090}"
DJL_URL="${DJL_SERVING_URL:-http://localhost:${HOST_PORT}}"

DEFAULT_MODELS=(
  "minilm:sentence-transformers/all-MiniLM-L6-v2"
  "paraphrase_MiniLM_L3_v2:sentence-transformers/paraphrase-MiniLM-L3-v2"
)

if [ -n "${DJL_E2E_MODELS:-}" ]; then
  read -r -a MODELS <<< "$DJL_E2E_MODELS"
else
  MODELS=("${DEFAULT_MODELS[@]}")
fi

wait_for_djl() {
  local deadline
  deadline=$(( $(date +%s) + 300 ))   # 5 min max — first-time image pull + JVM boot
  while [ "$(date +%s)" -lt "$deadline" ]; do
    if curl -fsS "$DJL_URL/ping" >/dev/null 2>&1; then return 0; fi
    sleep 2
  done
  echo "djl-models: gave up waiting for $DJL_URL/ping after 5 minutes" >&2
  return 1
}

echo "djl-models: waiting for $DJL_URL/ping ..."
wait_for_djl
echo "djl-models: djl-serving reachable"

registered_count=0
skipped_count=0

for entry in "${MODELS[@]}"; do
  serving_name="${entry%%:*}"
  hf_id="${entry#*:}"

  if curl -fsS "$DJL_URL/models/$serving_name" >/dev/null 2>&1; then
    echo "djl-models: $serving_name already registered — skipping"
    skipped_count=$((skipped_count + 1))
    continue
  fi

  djl_url="djl://ai.djl.huggingface.pytorch/$hf_id"
  encoded=$(python3 -c "from urllib.parse import quote; import sys; print(quote(sys.argv[1], safe=''))" "$djl_url")

  echo "djl-models: registering $serving_name ← $djl_url ..."
  if ! curl -fsS -X POST "$DJL_URL/models?url=$encoded&model_name=$serving_name&engine=PyTorch&synchronous=true"; then
    echo "djl-models: registration of $serving_name FAILED — aborting" >&2
    exit 1
  fi
  echo
  registered_count=$((registered_count + 1))
done

echo "djl-models: done. registered=$registered_count skipped=$skipped_count total=${#MODELS[@]}"
