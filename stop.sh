#!/usr/bin/env bash
#
# Stops the application, then brings the docker-compose dependencies down.
#
# The mechanics live in lib/stack.sh, shared with run.sh and demo.sh, so there is one
# answer to "which process is the app" and "how is it stopped".
#
# Stopping something that is already stopped is a successful outcome, not an error, so
# every path here exits 0 unless something genuinely refuses to die. That makes the script
# safe to run from a teardown hook or twice in a row.
#
# Volumes survive by default: the Kafka log directory is usually worth keeping between
# runs. Pass --clean to discard them.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR

# shellcheck source=lib/stack.sh
. "${SCRIPT_DIR}/lib/stack.sh"

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

    stack_stop_app
    if [ "$CLEAN" = true ]; then
        stack_compose_down clean
    else
        stack_compose_down
    fi
    stack_log 'done'
}

main "$@"
