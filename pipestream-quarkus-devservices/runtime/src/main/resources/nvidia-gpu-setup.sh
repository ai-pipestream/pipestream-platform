#!/usr/bin/env bash
# nvidia-gpu-setup.sh — ensure Docker can pass NVIDIA GPUs into containers.
#
# Runs diagnostics first. If the host already passes the GPU smoke test,
# exits 0 without touching anything (and without needing root). Only when
# there's actual work to do — installing nvidia-container-toolkit,
# generating the CDI spec, restarting docker — does it escalate and
# require root.
#
# Scope: Ubuntu / Debian (apt-based). Fedora / Arch / macOS not supported
# here; see NVIDIA's docs for those distros.
#
# Usage:
#   ./nvidia-gpu-setup.sh          # diagnose only
#   sudo ./nvidia-gpu-setup.sh     # diagnose + install if needed

set -eu

log()  { echo "nvidia-gpu-setup: $*"; }
err()  { echo "nvidia-gpu-setup: $*" >&2; }
need_root() { [ "${EUID:-$(id -u)}" -eq 0 ]; }

# ---- 1. Sanity: is this an NVIDIA host at all? ------------------------------
if ! command -v nvidia-smi >/dev/null 2>&1; then
  err "nvidia-smi not found on host."
  err "Install NVIDIA drivers first — this script only configures the container toolkit."
  exit 1
fi

if ! nvidia-smi >/dev/null 2>&1; then
  err "nvidia-smi present but failed to run. Check driver install."
  exit 1
fi

# ---- 2. Fast path: does docker already pass the GPU through? ----------------
SMOKE_IMAGE="nvidia/cuda:12.6.0-base-ubuntu22.04"

smoke_test() {
  # Quiet pass/fail. Pulls the tiny base image on first run (~80MB).
  docker run --rm --gpus all "$SMOKE_IMAGE" nvidia-smi -L >/dev/null 2>&1
}

log "checking whether the toolkit is already working ..."
if smoke_test; then
  log "GPU is already reachable from containers — nothing to do."
  exit 0
fi
log "smoke test failed — investigating what needs fixing."

# ---- 3. Figure out what's missing and whether we can fix it -----------------
missing=()
command -v nvidia-ctk >/dev/null 2>&1 || missing+=("nvidia-container-toolkit")
[ -f /etc/cdi/nvidia.yaml ]          || missing+=("/etc/cdi/nvidia.yaml")

if [ ${#missing[@]} -eq 0 ]; then
  err "toolkit + CDI spec look present, but the GPU smoke test failed."
  err "Try manually:  docker run --rm --gpus all $SMOKE_IMAGE nvidia-smi"
  err "If that also fails, the docker daemon may need a restart:  sudo systemctl restart docker"
  exit 1
fi

log "missing: ${missing[*]}"

if ! need_root; then
  err "fix requires root (installing packages / writing /etc/cdi / restarting docker)."
  err "Re-run with sudo:"
  err "  sudo $0"
  exit 1
fi

# ---- 4. Distro check (only reached as root) ---------------------------------
if ! command -v apt-get >/dev/null 2>&1; then
  err "only apt-based distros (Ubuntu / Debian) are supported here."
  err "See https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/install-guide.html"
  exit 1
fi

# ---- 5. Add NVIDIA container-toolkit repo -----------------------------------
KEYRING=/usr/share/keyrings/nvidia-container-toolkit-keyring.gpg
LIST=/etc/apt/sources.list.d/nvidia-container-toolkit.list

if [ ! -f "$KEYRING" ]; then
  log "installing NVIDIA repo keyring ..."
  curl -fsSL https://nvidia.github.io/libnvidia-container/gpgkey \
    | gpg --dearmor -o "$KEYRING"
fi

if [ ! -f "$LIST" ]; then
  log "writing APT source list ..."
  curl -fsSL https://nvidia.github.io/libnvidia-container/stable/deb/nvidia-container-toolkit.list \
    | sed "s#deb https://#deb [signed-by=${KEYRING}] https://#g" \
    > "$LIST"
fi

# ---- 6. Install the toolkit (if missing) ------------------------------------
if ! command -v nvidia-ctk >/dev/null 2>&1; then
  log "apt-get update + install nvidia-container-toolkit ..."
  apt-get update -qq
  apt-get install -y nvidia-container-toolkit
fi

# ---- 7. Generate CDI spec (if missing) --------------------------------------
if [ ! -f /etc/cdi/nvidia.yaml ]; then
  log "generating CDI spec at /etc/cdi/nvidia.yaml ..."
  mkdir -p /etc/cdi
  nvidia-ctk cdi generate --output=/etc/cdi/nvidia.yaml
fi

# ---- 8. Restart Docker so it reloads runtimes + CDI -------------------------
log "restarting docker ..."
systemctl restart docker

# ---- 9. Smoke test again ----------------------------------------------------
log "verifying GPU access ..."
if smoke_test; then
  log "OK — containers can now see the GPU."
else
  err "smoke test STILL failing. Investigate:"
  err "  docker run --rm --gpus all $SMOKE_IMAGE nvidia-smi"
  exit 1
fi
