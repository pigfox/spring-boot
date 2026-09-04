#!/usr/bin/env bash
#
# Brings up the local dependencies and starts the application.
#
# The app runs in the background with its pid recorded, so this script returns and
# stop.sh has something concrete to stop. Readiness is established by connecting to each
# port rather than by sleeping, because a fixed sleep is either too short on a cold
# machine or wasted time on a warm one.
#
# No secret is defined, defaulted, read from a file, or printed here. Every credential
# must already be in the environment; see the preflight list below.

set -euo pipefail

# Resolve everything against the script's own location so the script works from any cwd.
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
readonly RUN_DIR="${SCRIPT_DIR}/.run"
readonly PID_FILE="${RUN_DIR}/app.pid"
readonly LOG_FILE="${RUN_DIR}/app.log"

# Ports allocated to this project. Nothing in docker-compose.yml may bind either.
readonly APP_PORT=8087
readonly MGMT_PORT=55437

# Dependency ports, matching docker-compose.yml.
readonly KAFKA_PORT=19092
readonly ANVIL_PORT=8545

readonly DEP_TIMEOUT=120
readonly APP_TIMEOUT=180

# Credentials the application refuses to start without. Each is bound in application.yml
# from this exact name and has no default, by design.
readonly REQUIRED_ENV=(
    LEDGER_SIGNING_KEY
    LEDGER_JWT_SECRET
    LEDGER_CLIENT_ID
    LEDGER_CLIENT_SECRET
)

WITH_DEPS=true
WITH_APP=true

usage() {
    cat <<'USAGE'
Usage: run.sh [--deps-only | --no-deps] [--help]

  (no flags)    Start the docker-compose dependencies, wait for them, then start the app.
  --deps-only   Start and wait for the dependencies only. Does not start the app.
  --no-deps     Start the app only. Assumes Kafka and anvil are already running.
  --help        Show this message.

Required environment variables (no defaults, never read from a file):
  LEDGER_SIGNING_KEY    secp256k1 private key, 32 bytes hex
  LEDGER_JWT_SECRET     HS256 secret, at least 32 bytes
  LEDGER_CLIENT_ID      client id the token endpoint accepts
  LEDGER_CLIENT_SECRET  client secret the token endpoint accepts
USAGE
}

log() {
    printf '==> %s\n' "$1"
}

fail() {
    printf 'error: %s\n' "$1" >&2
    exit 1
}

# Reports whether anything is accepting TCP connections on a port. Uses bash's own
# /dev/tcp rather than nc or lsof, neither of which is guaranteed to be installed.
port_open() {
    local host="$1" port="$2"
    (: >"/dev/tcp/${host}/${port}") >/dev/null 2>&1
}

wait_for_port() {
    local host="$1" port="$2" label="$3" timeout="$4"
    local waited=0

    printf '==> waiting for %s on %s:%s ' "$label" "$host" "$port"
    while ! port_open "$host" "$port"; do
        if [ "$waited" -ge "$timeout" ]; then
            printf 'timed out after %ss\n' "$timeout" >&2
            return 1
        fi
        sleep 1
        waited=$((waited + 1))
        printf '.'
    done
    printf 'ready\n'
}

preflight_env() {
    local missing=()
    local var

    for var in "${REQUIRED_ENV[@]}"; do
        # Indirect expansion, so the value itself is never expanded into a message.
        if [ -z "${!var:-}" ]; then
            missing+=("$var")
        fi
    done

    if [ "${#missing[@]}" -gt 0 ]; then
        printf 'error: %s required environment variable(s) not set:\n' "${#missing[@]}" >&2
        printf '  %s\n' "${missing[@]}" >&2
        printf '\nExport them in your shell. There is no .env file and none will be read.\n' >&2
        exit 1
    fi
    log "environment: all ${#REQUIRED_ENV[@]} required variables present"
}

compose_up() {
    command -v docker >/dev/null 2>&1 || fail 'docker is not installed or not on PATH'

    log 'starting dependencies (kafka, anvil)'
    # --remove-orphans keeps a renamed or removed service from leaving a container behind.
    docker compose --project-directory "$SCRIPT_DIR" up -d --remove-orphans

    wait_for_port 127.0.0.1 "$KAFKA_PORT" kafka "$DEP_TIMEOUT" || fail 'kafka never accepted connections'
    wait_for_port 127.0.0.1 "$ANVIL_PORT" anvil "$DEP_TIMEOUT" || fail 'anvil never accepted connections'
}

# Returns the pid of a live app recorded by a previous run, or nothing.
running_pid() {
    local pid
    [ -f "$PID_FILE" ] || return 1
    pid="$(cat "$PID_FILE")"
    [ -n "$pid" ] || return 1
    kill -0 "$pid" 2>/dev/null || return 1
    printf '%s' "$pid"
}

find_jar() {
    find "${SCRIPT_DIR}/target" -maxdepth 1 -name 'spring-boot-*.jar' -print -quit 2>/dev/null
}

start_app() {
    local pid jar

    # Idempotency, in two steps. A recorded live pid means this script started it; a bare
    # listener means something else did. Neither is an error worth a stack trace.
    if pid="$(running_pid)"; then
        log "already running as pid ${pid}; leaving it alone"
        print_urls
        return 0
    fi
    if port_open 127.0.0.1 "$APP_PORT"; then
        log "something is already listening on ${APP_PORT}; not starting a second instance"
        log "if that is a stale app, run ./stop.sh first"
        print_urls
        return 0
    fi

    jar="$(find_jar || true)"
    if [ -z "$jar" ]; then
        log 'no jar found, building (this is the slow path, and only happens once)'
        (cd "$SCRIPT_DIR" && ./mvnw -B -q -DskipTests package)
        jar="$(find_jar || true)"
        [ -n "$jar" ] || fail 'build produced no jar'
    fi
    log "starting app from $(basename "$jar")"

    mkdir -p "$RUN_DIR"
    # Secrets reach the app through the inherited environment, never the command line,
    # where they would be visible to anyone running ps.
    nohup java -jar "$jar" --server.port="$APP_PORT" >"$LOG_FILE" 2>&1 &
    pid=$!
    printf '%s\n' "$pid" >"$PID_FILE"
    log "app pid ${pid}, log ${LOG_FILE}"

    wait_for_app "$pid"
}

wait_for_app() {
    local pid="$1"
    local waited=0

    printf '==> waiting for health on %s ' "$MGMT_PORT"
    until curl -fsS "http://localhost:${MGMT_PORT}/actuator/health" >/dev/null 2>&1; do
        if ! kill -0 "$pid" 2>/dev/null; then
            printf 'process exited\n' >&2
            printf '\n--- last 20 lines of %s ---\n' "$LOG_FILE" >&2
            tail -n 20 "$LOG_FILE" >&2 || true
            rm -f "$PID_FILE"
            fail 'app failed to start'
        fi
        if [ "$waited" -ge "$APP_TIMEOUT" ]; then
            printf 'timed out after %ss\n' "$APP_TIMEOUT" >&2
            fail 'app never became healthy'
        fi
        sleep 1
        waited=$((waited + 1))
        printf '.'
    done
    printf 'up\n'
    print_urls
}

print_urls() {
    cat <<URLS

  API          http://localhost:${APP_PORT}/api/v1
  Swagger UI   http://localhost:${APP_PORT}/swagger-ui.html
  Health       http://localhost:${MGMT_PORT}/actuator/health
  Prometheus   http://localhost:${MGMT_PORT}/actuator/prometheus

  Only health is public. Every other route needs a bearer token from
  POST http://localhost:${APP_PORT}/api/v1/auth/token

URLS
}

main() {
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --deps-only) WITH_APP=false ;;
            --no-deps) WITH_DEPS=false ;;
            --help | -h)
                usage
                exit 0
                ;;
            *)
                printf 'error: unknown option %s\n\n' "$1" >&2
                usage >&2
                exit 2
                ;;
        esac
        shift
    done

    if [ "$WITH_DEPS" = false ] && [ "$WITH_APP" = false ]; then
        fail '--deps-only and --no-deps are mutually exclusive'
    fi

    # Before docker, always: discovering a missing credential after a container start is
    # the slow way to learn it.
    preflight_env

    if [ "$WITH_DEPS" = true ]; then
        compose_up
    fi
    if [ "$WITH_APP" = true ]; then
        start_app
    fi

    return 0
}

main "$@"
