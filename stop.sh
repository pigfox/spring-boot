#!/usr/bin/env bash
#
# Stops the application, then brings the docker-compose dependencies down.
#
# Stopping something that is already stopped is a successful outcome, not an error, so
# every path here exits 0 unless something genuinely refuses to die. That makes the script
# safe to run from a teardown hook or twice in a row.
#
# Volumes survive by default: the Kafka log directory is usually worth keeping between
# runs. Pass --clean to discard them.

set -euo pipefail

# Resolve everything against the script's own location so the script works from any cwd.
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
readonly RUN_DIR="${SCRIPT_DIR}/.run"
readonly PID_FILE="${RUN_DIR}/app.pid"

readonly APP_PORT=8087

# How long to let the app shut down gracefully before insisting.
readonly TERM_TIMEOUT=30

CLEAN=false

usage() {
    cat <<'USAGE'
Usage: stop.sh [--clean] [--help]

  (no flags)  Stop the app, then stop the dependencies. Volumes are kept.
  --clean     Also remove the compose volumes, discarding the Kafka log directory
              and the anvil chain state.
  --help      Show this message.

Exits 0 when nothing is running.
USAGE
}

log() {
    printf '==> %s\n' "$1"
}

port_open() {
    local host="$1" port="$2"
    (: >"/dev/tcp/${host}/${port}") >/dev/null 2>&1
}

stop_app() {
    local pid waited=0

    if [ ! -f "$PID_FILE" ]; then
        # No pid file. Either nothing ran, or something else owns the port; say which,
        # but do not go hunting for a process this script did not start.
        if port_open 127.0.0.1 "$APP_PORT"; then
            log "no pid file, but something is still listening on ${APP_PORT}"
            log 'this script only stops what run.sh started; leaving it alone'
        else
            log 'app: not running'
        fi
        return 0
    fi

    pid="$(cat "$PID_FILE")"
    if [ -z "$pid" ] || ! kill -0 "$pid" 2>/dev/null; then
        log 'app: not running (clearing stale pid file)'
        rm -f "$PID_FILE"
        return 0
    fi

    log "stopping app (pid ${pid})"
    kill "$pid" 2>/dev/null || true

    while kill -0 "$pid" 2>/dev/null; do
        if [ "$waited" -ge "$TERM_TIMEOUT" ]; then
            log "did not exit after ${TERM_TIMEOUT}s, sending SIGKILL"
            kill -9 "$pid" 2>/dev/null || true
            break
        fi
        sleep 1
        waited=$((waited + 1))
    done

    rm -f "$PID_FILE"
    log 'app: stopped'
}

compose_down() {
    if ! command -v docker >/dev/null 2>&1; then
        log 'docker not on PATH, skipping dependencies'
        return 0
    fi

    local args=(--project-directory "$SCRIPT_DIR" down --remove-orphans)
    if [ "$CLEAN" = true ]; then
        log 'stopping dependencies and removing volumes'
        args+=(--volumes)
    else
        log 'stopping dependencies (volumes kept)'
    fi

    # `down` on a stack that was never up is a no-op that exits 0, so this needs no guard.
    docker compose "${args[@]}"
}

main() {
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --clean) CLEAN=true ;;
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

    stop_app
    compose_down
    log 'done'
}

main "$@"
