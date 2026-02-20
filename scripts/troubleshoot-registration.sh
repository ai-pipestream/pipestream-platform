#!/usr/bin/env bash
#
# PipeStream Service Registration Troubleshooting Script
# Run this on any machine to capture the full registration state.
# Output goes to stdout — redirect to a file to compare across machines.
#
# Usage: ./troubleshoot-registration.sh [consul_host:port]
#   Default consul: localhost:8500
#

set -euo pipefail

CONSUL="${1:-localhost:8500}"
DIVIDER="================================================================================"
SUBDIV="--------------------------------------------------------------------------------"

# Known service ports
CORE_SERVICES=(
    "platform-registration:38101"
    "engine:38100"
    "repository:38102"
    "opensearch-manager:38103"
    "s3-connector:38104"
    "account-manager:38105"
    "design-mode:38106"
    "connector-admin:38107"
    "connector-intake:38108"
    "s3-connector-alt:38120"
)
MODULES=(
    "echo:39000"
    "parser:39001"
    "chunker:39002"
    "embedder:39003"
    "opensearch-sink:39004"
)

header() {
    echo ""
    echo "$DIVIDER"
    echo "  $1"
    echo "$DIVIDER"
}

subheader() {
    echo ""
    echo "$SUBDIV"
    echo "  $1"
    echo "$SUBDIV"
}

# ─── ENVIRONMENT ───────────────────────────────────────────────────────────────

header "1. ENVIRONMENT"

echo "Date:       $(date -u '+%Y-%m-%d %H:%M:%S UTC')"
echo "Hostname:   $(hostname)"
echo "OS:         $(uname -srm)"

if [[ -f /etc/os-release ]]; then
    echo "Distro:     $(grep PRETTY_NAME /etc/os-release 2>/dev/null | cut -d= -f2 | tr -d '"')"
elif [[ "$(uname)" == "Darwin" ]]; then
    echo "macOS:      $(sw_vers -productVersion 2>/dev/null || echo 'unknown')"
fi

echo "User:       $(whoami)"
echo "Shell:      $SHELL"

subheader "Java"
if command -v java &>/dev/null; then
    java -version 2>&1
else
    echo "java: NOT FOUND"
fi

subheader "Environment Variables (registration-related)"
for var in HOSTNAME SERVICE_REGISTRATION_ADVERTISED_HOST SERVICE_REGISTRATION_INTERNAL_HOST \
           PIPELINE_ENV QUARKUS_PROFILE SERVICE_HOST CONSUL_HOST CONSUL_PORT \
           QUARKUS_LAUNCH_DEVMODE JAVA_HOME; do
    val="${!var:-<unset>}"
    echo "  $var=$val"
done

subheader "Network Interfaces"
if command -v ip &>/dev/null; then
    ip -4 addr show | grep -E "inet " | awk '{print "  " $NF ": " $2}'
elif command -v ifconfig &>/dev/null; then
    ifconfig | grep "inet " | awk '{print "  " $2}'
fi

# ─── DOCKER ────────────────────────────────────────────────────────────────────

header "2. DOCKER"

if command -v docker &>/dev/null; then
    echo "Docker version: $(docker --version 2>/dev/null)"
    echo ""

    subheader "Docker Containers"
    docker ps --format 'table {{.Names}}\t{{.Ports}}\t{{.Status}}' 2>/dev/null || echo "  Cannot list containers"

    subheader "Docker Network (bridge)"
    docker network inspect bridge --format '{{range .IPAM.Config}}Gateway: {{.Gateway}} Subnet: {{.Subnet}}{{end}}' 2>/dev/null || echo "  Cannot inspect bridge network"

    subheader "host.docker.internal resolution (from inside Docker)"
    docker run --rm alpine:3.19 sh -c "getent hosts host.docker.internal 2>/dev/null || nslookup host.docker.internal 2>/dev/null || echo 'Cannot resolve host.docker.internal'" 2>/dev/null || echo "  Cannot run test container"
else
    echo "Docker: NOT FOUND"
fi

# ─── CONSUL ────────────────────────────────────────────────────────────────────

header "3. CONSUL (${CONSUL})"

subheader "Consul Connectivity"
if curl -sf "http://${CONSUL}/v1/status/leader" -o /dev/null 2>/dev/null; then
    echo "  Consul is reachable"
    echo "  Leader: $(curl -sf "http://${CONSUL}/v1/status/leader")"
else
    echo "  *** CONSUL IS NOT REACHABLE at ${CONSUL} ***"
fi

subheader "Registered Services"
CONSUL_SERVICES=$(curl -sf "http://${CONSUL}/v1/agent/services" 2>/dev/null || echo "{}")

if command -v jq &>/dev/null; then
    echo "$CONSUL_SERVICES" | jq -r '
        to_entries[] |
        "\(.value.Service)\t\(.value.ID)\t\(.value.Address):\(.value.Port)\t tags=\(.value.Tags // [] | join(","))"
    ' 2>/dev/null | sort | column -t -s$'\t' || echo "  No services or parse error"

    subheader "Service Metadata Details"
    echo "$CONSUL_SERVICES" | jq -r '
        to_entries[] |
        "--- \(.value.ID) ---\n" +
        ((.value.Meta // {}) | to_entries | map("  \(.key) = \(.value)") | join("\n")) + "\n"
    ' 2>/dev/null || echo "  No metadata"

    subheader "Duplicate Service Names Check"
    DUPES=$(echo "$CONSUL_SERVICES" | jq -r '[to_entries[].value.Service] | group_by(.) | map(select(length > 1)) | map({name: .[0], count: length})' 2>/dev/null)
    if [[ "$DUPES" == "[]" ]]; then
        echo "  No duplicate service names"
    else
        echo "  *** DUPLICATES FOUND ***"
        echo "$DUPES" | jq -r '.[] | "  \(.name): \(.count) instances"' 2>/dev/null
    fi
else
    echo "(install jq for formatted output)"
    echo "$CONSUL_SERVICES"
fi

# ─── CONSUL HEALTH CHECKS ─────────────────────────────────────────────────────

subheader "Consul Health Check Status"
HEALTH_CHECKS=$(curl -sf "http://${CONSUL}/v1/agent/checks" 2>/dev/null || echo "{}")
if command -v jq &>/dev/null; then
    echo "$HEALTH_CHECKS" | jq -r '
        to_entries[] |
        "\(.value.ServiceID)\t\(.value.Status)\t\(.value.Output[:120] // "no output")"
    ' 2>/dev/null | sort | column -t -s$'\t' || echo "  No health checks"
else
    echo "$HEALTH_CHECKS"
fi

# ─── SERVICE PROBES ────────────────────────────────────────────────────────────

header "4. SERVICE PROBES"

probe_service() {
    local name="$1"
    local port="$2"

    subheader "$name (port $port)"

    # TCP check
    if (echo >/dev/tcp/localhost/"$port") 2>/dev/null; then
        echo "  TCP:        OPEN"
    else
        echo "  TCP:        CLOSED (not listening)"
        return
    fi

    # HTTP health
    local health
    health=$(curl -sf --max-time 3 "http://localhost:${port}/q/health/live" 2>/dev/null) && {
        if command -v jq &>/dev/null; then
            local status
            status=$(echo "$health" | jq -r '.status' 2>/dev/null)
            echo "  Health:     $status"
        else
            echo "  Health:     responded (install jq for details)"
        fi
    } || echo "  Health:     NO RESPONSE or ERROR"

    # gRPC reflection
    if command -v grpcurl &>/dev/null; then
        local services
        services=$(grpcurl -plaintext "localhost:${port}" list 2>/dev/null) && {
            echo "  gRPC services:"
            echo "$services" | while read -r svc; do
                echo "    - $svc"
            done
            # Check specifically for health
            if echo "$services" | grep -q "grpc.health.v1.Health"; then
                echo "  gRPC Health: PRESENT"
                # Actually call health check
                local hc
                hc=$(grpcurl -plaintext "localhost:${port}" grpc.health.v1.Health/Check 2>/dev/null) && {
                    echo "  gRPC Health Check: $hc"
                } || echo "  gRPC Health Check: FAILED"
            else
                echo "  gRPC Health: *** MISSING ***"
            fi
        } || echo "  gRPC reflection: NOT AVAILABLE"
    else
        echo "  gRPC:       (grpcurl not installed, skipping)"
    fi
}

for entry in "${CORE_SERVICES[@]}"; do
    IFS=: read -r name port <<< "$entry"
    probe_service "$name" "$port"
done

for entry in "${MODULES[@]}"; do
    IFS=: read -r name port <<< "$entry"
    probe_service "$name" "$port"
done

# ─── GRADLE / BUILD INFO ──────────────────────────────────────────────────────

header "5. BUILD INFO"

subheader "BOM versions (gradle.properties)"
for dir in /work/core-services/platform-registration-service /work/modules/module-parser /work/modules/module-opensearch-sink /work/core-services/pipestream-platform; do
    if [[ -f "$dir/gradle.properties" ]]; then
        bom=$(grep pipestreamBomVersion "$dir/gradle.properties" 2>/dev/null || echo "not set")
        echo "  $(basename "$dir"): $bom"
    fi
done

subheader "Maven Local pipestream-server extension JARs"
find ~/.m2/repository/ai/pipestream/pipestream-server-runtime -name "*.jar" -printf "  %T@ %Tc  %p\n" 2>/dev/null | sort -rn | head -5 | cut -d' ' -f2- || echo "  No pipestream-server-runtime JARs found in Maven Local"

echo ""
echo "$DIVIDER"
echo "  Troubleshooting complete. Compare output across machines."
echo "$DIVIDER"
