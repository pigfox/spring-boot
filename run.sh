#!/usr/bin/env bash
#
# Brings up the local dependencies and starts the application.
#
# The mechanics live in lib/stack.sh and lib/credentials.sh, which demo.sh and stop.sh
# share, so dependency startup, health waiting and pidfile handling have one
# implementation rather than three that drift.
#
# The app runs in the background with its pid recorded, so this script returns and stop.sh
# has something concrete to stop. Readiness is established by connecting to each port
# rather than by sleeping, because a fixed sleep is either too short on a cold machine or
# wasted time on a warm one.
#
# No secret is defined, defaulted, read from a file, or printed here.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR

# shellcheck source=lib/stack.sh
. "${SCRIPT_DIR}/lib/stack.sh"
# shellcheck source=lib/credentials.sh
. "${SCRIPT_DIR}/lib/credentials.sh"

WITH_DEPS=true
WITH_APP=true
DEV_MODE=false

usage() {
    cat <<'USAGE'
Usage: run.sh [--deps-only | --no-deps] [--dev] [--help]

  (no flags)    Start the docker-compose dependencies, wait for them, then start the app.
  --deps-only   Start and wait for the dependencies only. Does not start the app.
  --no-deps     Start the app only. Assumes Kafka and anvil are already running.
  --dev         Generate throwaway credentials for any of the four that are not already
                exported, instead of refusing to start. Local use only.
  --help        Show this message.

Required environment variables (no defaults, never read from a file):
  LEDGER_SIGNING_KEY    secp256k1 private key, 32 bytes hex
  LEDGER_JWT_SECRET     HS256 secret, at least 32 bytes
  LEDGER_CLIENT_ID      client id the token endpoint accepts
  LEDGER_CLIENT_SECRET  client secret the token endpoint accepts

For a demo that needs none of this, run ./demo.sh instead: it generates all four in
its own process, starts everything, exercises the API, and tears down after.
USAGE
}

start_app_idempotently() {
    local pid

    # Idempotency, in two steps. A recorded live pid means this tooling started it; a bare
    # listener means something else did. Neither is an error worth a stack trace.
    if pid="$(stack_running_pid)"; then
        stack_log "already running as pid ${pid}; leaving it alone"
        stack_print_urls
        return 0
    fi
    if stack_port_open 127.0.0.1 "$STACK_APP_PORT"; then
        stack_log "something is already listening on ${STACK_APP_PORT}; not starting a second instance"
        stack_log 'if that is a stale app, run ./stop.sh first'
        stack_print_urls
        return 0
    fi

    stack_start_app
    stack_print_urls
}

main() {
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --deps-only) WITH_APP=false ;;
            --no-deps) WITH_DEPS=false ;;
            --dev) DEV_MODE=true ;;
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
        stack_fail '--deps-only and --no-deps are mutually exclusive'
    fi

    # Before docker, always: discovering a missing credential after a container start is
    # the slow way to learn it.
    if [ "$DEV_MODE" = true ]; then
        credentials_generate
    else
        credentials_require_all
    fi

    if [ "$WITH_DEPS" = true ]; then
        stack_compose_up
    fi
    if [ "$WITH_APP" = true ]; then
        start_app_idempotently
    fi

    return 0
}

main "$@"
