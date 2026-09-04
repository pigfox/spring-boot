#!/usr/bin/env bash
# shellcheck shell=bash
#
# The local stack: dependency containers, the application process, and the readiness
# checks that decide when either is usable.
#
# This is the single implementation. run.sh, stop.sh and demo.sh are thin front ends over
# it, so "how do we know Kafka is up" and "where is the pid recorded" each have exactly one
# answer. Sourced, never executed.
#
# Nothing here prints a credential. Secrets reach the application through the environment
# it inherits, never through a command line, where ps would show them.

# Repository root, resolved from this file rather than from the caller's cwd.
STACK_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
readonly STACK_ROOT
readonly STACK_RUN_DIR="${STACK_ROOT}/.run"
readonly STACK_PID_FILE="${STACK_RUN_DIR}/app.pid"
readonly STACK_LOG_FILE="${STACK_RUN_DIR}/app.log"

# Ports allocated to this project. Nothing in docker-compose.yml may bind either.
readonly STACK_APP_PORT=8087
readonly STACK_MGMT_PORT=55437

# Dependency ports, matching docker-compose.yml.
readonly STACK_KAFKA_PORT=19092
readonly STACK_ANVIL_PORT=8545

readonly STACK_DEP_TIMEOUT=120
readonly STACK_APP_TIMEOUT=180

stack_log() {
    printf '==> %s\n' "$1"
}

stack_fail() {
    printf 'error: %s\n' "$1" >&2
    exit 1
}

# Reports whether anything is accepting TCP connections on a port. Uses bash's own
# /dev/tcp rather than nc or lsof, neither of which is guaranteed to be installed.
stack_port_open() {
    local host="$1" port="$2"
    (: >"/dev/tcp/${host}/${port}") >/dev/null 2>&1
}

stack_wait_for_port() {
    local host="$1" port="$2" label="$3" timeout="$4"
    local waited=0

    printf '==> waiting for %s on %s:%s ' "$label" "$host" "$port"
    while ! stack_port_open "$host" "$port"; do
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

# True when both dependency ports already answer, so a caller can tell whether it is the
# one starting them — and therefore whether it is the one that should stop them.
stack_deps_running() {
    stack_port_open 127.0.0.1 "$STACK_KAFKA_PORT" &&
        stack_port_open 127.0.0.1 "$STACK_ANVIL_PORT"
}

stack_app_healthy() {
    curl -fsS -o /dev/null --max-time 5 \
        "http://localhost:${STACK_MGMT_PORT}/actuator/health" 2>/dev/null
}

stack_require_docker() {
    command -v docker >/dev/null 2>&1 || stack_fail 'docker is not installed or not on PATH'
}

stack_compose_up() {
    stack_require_docker

    stack_log 'starting dependencies (kafka, anvil)'
    # --remove-orphans keeps a renamed or removed service from leaving a container behind.
    docker compose --project-directory "$STACK_ROOT" up -d --remove-orphans

    stack_wait_for_port 127.0.0.1 "$STACK_KAFKA_PORT" kafka "$STACK_DEP_TIMEOUT" ||
        stack_fail 'kafka never accepted connections'
    stack_wait_for_port 127.0.0.1 "$STACK_ANVIL_PORT" anvil "$STACK_DEP_TIMEOUT" ||
        stack_fail 'anvil never accepted connections'
}

# Brings the dependencies down. Pass "clean" to discard volumes too.
stack_compose_down() {
    local mode="${1:-keep-volumes}"

    if ! command -v docker >/dev/null 2>&1; then
        stack_log 'docker not on PATH, skipping dependencies'
        return 0
    fi

    local args=(--project-directory "$STACK_ROOT" down --remove-orphans)
    if [ "$mode" = clean ]; then
        stack_log 'stopping dependencies and removing volumes'
        args+=(--volumes)
    else
        stack_log 'stopping dependencies (volumes kept)'
    fi

    # `down` on a stack that was never up is a no-op that exits 0, so this needs no guard.
    docker compose "${args[@]}"
}

# Prints the pid of a live application recorded by a previous start, or returns non-zero.
stack_running_pid() {
    local pid
    [ -f "$STACK_PID_FILE" ] || return 1
    pid="$(cat "$STACK_PID_FILE")"
    [ -n "$pid" ] || return 1
    kill -0 "$pid" 2>/dev/null || return 1
    printf '%s' "$pid"
}

stack_find_jar() {
    find "${STACK_ROOT}/target" -maxdepth 1 -name 'spring-boot-*.jar' -print -quit 2>/dev/null
}

# Builds the jar if there is not one already.
stack_ensure_jar() {
    local jar
    jar="$(stack_find_jar || true)"
    if [ -z "$jar" ]; then
        stack_log 'no jar found, building (this is the slow path, and only happens once)'
        (cd "$STACK_ROOT" && ./mvnw -B -q -DskipTests package) || stack_fail 'build failed'
        jar="$(stack_find_jar || true)"
        [ -n "$jar" ] || stack_fail 'build produced no jar'
    fi
    printf '%s' "$jar"
}

# Launches the application and waits for it to answer its health probe.
#
# The caller's environment carries the credentials; they are inherited, never echoed and
# never placed on the command line. The pid file is the record of what was started.
stack_start_app() {
    local jar pid

    jar="$(stack_ensure_jar)"
    stack_log "starting app from $(basename "$jar")"

    mkdir -p "$STACK_RUN_DIR"
    nohup java -jar "$jar" --server.port="$STACK_APP_PORT" >"$STACK_LOG_FILE" 2>&1 &
    pid=$!
    printf '%s\n' "$pid" >"$STACK_PID_FILE"
    stack_log "app pid ${pid}, log ${STACK_LOG_FILE}"

    stack_wait_for_app "$pid"
}

stack_wait_for_app() {
    local pid="$1"
    local waited=0

    printf '==> waiting for health on %s ' "$STACK_MGMT_PORT"
    until stack_app_healthy; do
        if ! kill -0 "$pid" 2>/dev/null; then
            printf 'process exited\n' >&2
            printf '\n--- last 20 lines of %s ---\n' "$STACK_LOG_FILE" >&2
            tail -n 20 "$STACK_LOG_FILE" >&2 || true
            rm -f "$STACK_PID_FILE"
            stack_fail 'app failed to start'
        fi
        if [ "$waited" -ge "$STACK_APP_TIMEOUT" ]; then
            printf 'timed out after %ss\n' "$STACK_APP_TIMEOUT" >&2
            stack_fail 'app never became healthy'
        fi
        sleep 1
        waited=$((waited + 1))
        printf '.'
    done
    printf 'up\n'
}

# How long to let the app shut down gracefully before insisting.
readonly STACK_TERM_TIMEOUT=30

# Stops the recorded application. Never an error: stopping something already stopped is a
# successful outcome, which is what makes teardown safe to run from a trap.
stack_stop_app() {
    local pid waited=0

    if [ ! -f "$STACK_PID_FILE" ]; then
        if stack_port_open 127.0.0.1 "$STACK_APP_PORT"; then
            stack_log "no pid file, but something is still listening on ${STACK_APP_PORT}"
            stack_log 'only what this tooling started is stopped; leaving it alone'
        else
            stack_log 'app: not running'
        fi
        return 0
    fi

    pid="$(cat "$STACK_PID_FILE")"
    if [ -z "$pid" ] || ! kill -0 "$pid" 2>/dev/null; then
        stack_log 'app: not running (clearing stale pid file)'
        rm -f "$STACK_PID_FILE"
        return 0
    fi

    stack_log "stopping app (pid ${pid})"
    kill "$pid" 2>/dev/null || true

    while kill -0 "$pid" 2>/dev/null; do
        if [ "$waited" -ge "$STACK_TERM_TIMEOUT" ]; then
            stack_log "did not exit after ${STACK_TERM_TIMEOUT}s, sending SIGKILL"
            kill -9 "$pid" 2>/dev/null || true
            break
        fi
        sleep 1
        waited=$((waited + 1))
    done

    rm -f "$STACK_PID_FILE"
    stack_log 'app: stopped'
}

stack_print_urls() {
    cat <<URLS

  API          http://localhost:${STACK_APP_PORT}/api/v1
  Swagger UI   http://localhost:${STACK_APP_PORT}/swagger-ui.html
  Health       http://localhost:${STACK_MGMT_PORT}/actuator/health
  Prometheus   http://localhost:${STACK_MGMT_PORT}/actuator/prometheus

  Only health is public. Every other route needs a bearer token from
  POST http://localhost:${STACK_APP_PORT}/api/v1/auth/token

URLS
}
