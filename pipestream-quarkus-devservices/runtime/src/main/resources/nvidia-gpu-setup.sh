#!/usr/bin/env bash
# nvidia-gpu-setup.sh — one-time host setup so Docker can pass NVIDIA GPUs
# into containers via `--gpus all`.
#
# Installs the NVIDIA Container Toolkit, generates the CDI spec, and restarts
# the Docker daemon. Idempotent: re-running on a properly-configured host is
# a no-op after the package check.
#
# Scope: Ubuntu / Debian (apt-based). Fedora / Arch / macOS are out of scope
# here — add alternate branches or a separate script if you need them.
#
# Usage:
#   sudo ./nvidia-gpu-setup.sh

set -eu

# ---- 0. Root check ----------------------------------------------------------
if [ "${EUID:-$(id -u)}" -ne 0 ]; then
  echo "nvidia-gpu-setup: must run as root (use sudo)" >&2
  exit 1
fi

# ---- 1. Sanity: is this even an NVIDIA host? --------------------------------
if ! command -v nvidia-smi >/dev/null 2>&1; then
  echo "nvidia-gpu-setup: nvidia-smi not found on host." >&2
  echo "nvidia-gpu-setup: install NVIDIA drivers first — this script only configures the container toolkit." >&2
  exit 1
fi

if ! nvidia-smi >/dev/null 2>&1; then
  echo "nvidia-gpu-setup: nvidia-smi is present but failed to run. Check driver install." >&2
  exit 1
fi

# ---- 2. Distro check --------------------------------------------------------
if ! command -v apt-get >/dev/null 2>&1; then
  echo "nvidia-gpu-setup: only apt-based distros (Ubuntu / Debian) are supported here." >&2
  echo "nvidia-gpu-setup: see https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/install-guide.html for RHEL/Fedora/Arch." >&2
  exit 1
fi

# ---- 3. Add NVIDIA container-toolkit repo -----------------------------------
KEYRING=/usr/share/keyrings/nvidia-container-toolkit-keyring.gpg
LIST=/etc/apt/sources.list.d/nvidia-container-toolkit.list

if [ ! -f "$KEYRING" ]; then
  echo "nvidia-gpu-setup: installing NVIDIA repo keyring ..."
  curl -fsSL https://nvidia.github.io/libnvidia-container/gpgkey \
    | gpg --dearmor -o "$KEYRING"
fi

if [ ! -f "$LIST" ]; then
  echo "nvidia-gpu-setup: writing APT source list ..."
  # libnvidia-container publishes a generic 'stable' list file that works
  # across supported Ubuntu/Debian releases.
  curl -fsSL https://nvidia.github.io/libnvidia-container/stable/deb/nvidia-container-toolkit.list \
    | sed "s#deb https://#deb [signed-by=${KEYRING}] https://#g" \
    > "$LIST"
fi

# ---- 4. Install the toolkit -------------------------------------------------
echo "nvidia-gpu-setup: apt-get update + install nvidia-container-toolkit ..."
apt-get update -qq
apt-get install -y nvidia-container-toolkit

# ---- 5. Generate CDI spec ---------------------------------------------------
# Docker 25+ looks up GPU devices via the Container Device Interface (CDI);
# the toolkit generates a spec that enumerates each GPU + its device files.
CDI_OUT=/etc/cdi/nvidia.yaml
mkdir -p /etc/cdi
echo "nvidia-gpu-setup: generating CDI spec at $CDI_OUT ..."
nvidia-ctk cdi generate --output="$CDI_OUT"

# ---- 6. Restart Docker ------------------------------------------------------
echo "nvidia-gpu-setup: restarting docker ..."
systemctl restart docker

# ---- 7. Smoke test ----------------------------------------------------------
echo "nvidia-gpu-setup: verifying GPU access from a throwaway container ..."
if docker run --rm --gpus all nvidia/cuda:12.6.0-base-ubuntu22.04 nvidia-smi -L >/dev/null 2>&1; then
  echo "nvidia-gpu-setup: OK — containers can now see the GPU."
else
  echo "nvidia-gpu-setup: smoke test FAILED. Investigate:" >&2
  echo "  docker run --rm --gpus all nvidia/cuda:12.6.0-base-ubuntu22.04 nvidia-smi" >&2
  exit 1
fi
