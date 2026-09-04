#!/usr/bin/env bash
#
# One command: starts everything, exercises every endpoint, tears down after.
#
# Run it with no arguments and no environment. If nothing is already running, this script
# owns the lifecycle for the run — it generates all four credentials in its own process,
# brings up the dependencies and the application, walks the nine steps, and tears down on
# the way out whether the run succeeded, failed, or was interrupted.
#
# Generating the credentials here rather than asking you to export them is the point.
# One process holds them, the application inherits them, and nothing else ever sees them:
# they are not printed, not written to disk, and not passed on a command line. There is no
# .env file, and none is read or written.
#
# If an application is already running, this script uses it and leaves it alone. It does
# not restart it, does not stop it at the end, and generates nothing — credentials must
# then come from the environment, because only the process that started that application
# knows what it was given.
#
# None of this weakens the security model. No route becomes public, nothing disables the
# filter chain, and step 2 asserts that an anonymous read is still refused before any token
# exists. lib/auth.sh is an ordinary client calling the same public token endpoint anyone
# else would.
#
# No secret, token, or key appears in any output, including failures and the teardown.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR

# shellcheck source=lib/stack.sh
. "${SCRIPT_DIR}/lib/stack.sh"
# shellcheck source=lib/credentials.sh
. "${SCRIPT_DIR}/lib/credentials.sh"
# shellcheck source=lib/auth.sh
. "${SCRIPT_DIR}/lib/auth.sh"

# Actuator is not under the API base: this service serves telemetry on its own port so a
# network policy can expose probes and scrapes without exposing the API.
BASE_URL="http://localhost:${STACK_APP_PORT}"
MGMT_URL="http://localhost:${STACK_MGMT_PORT}"

KEEP=false
NO_START=false

# Teardown state. Only what this invocation started is ever stopped.
STARTED_APP=false
STARTED_DEPS=false
TEARDOWN_DONE=false

STEP=0
ASSET_NAME="Unit 4B, Harbour Court"
ASSET_TYPE="REAL_ESTATE"
ASSET_OWNER="Estate JV"

usage() {
    cat <<'USAGE'
Usage: demo.sh [--keep] [--no-start] [--base-url URL] [--mgmt-url URL] [--help]

  (no flags)      If nothing is running, generate credentials, start the dependencies
                  and the app, run the demo, and tear down afterwards. If something is
                  already running, use it and leave it running.
  --keep          Having started the app, leave it running at the end instead of
                  tearing it down. No effect when the app was already running.
  --no-start      Never start anything. Require an already-running app, and require
                  LEDGER_CLIENT_ID and LEDGER_CLIENT_SECRET in the environment.
  --base-url URL  API base. Default http://localhost:8087
  --mgmt-url URL  Actuator base. Default http://localhost:55437
  --help          Show this message.

Any credential already exported is respected and never overridden.
USAGE
}

step() {
    STEP=$((STEP + 1))
    printf '\n\033[1m[%d] %s\033[0m\n' "$STEP" "$1"
}

ok() {
    printf '    \033[32mok\033[0m  %s\n' "$1"
}

detail() {
    printf '        %s\n' "$1"
}

die() {
    printf '\n    \033[31mFAILED\033[0m at step %d: %s\n' "$STEP" "$1" >&2
    exit 1
}

expect_status() {
    local expected="$1" actual="$2" what="$3"
    if [ "$actual" != "$expected" ]; then
        die "${what}: expected HTTP ${expected}, got ${actual}"
    fi
    ok "HTTP ${actual} ${what}"
}

# Stops only what this invocation started. Runs from the EXIT trap, so it fires on a failed
# assertion and on Ctrl-C as well as on success. Every command is guarded, because a
# teardown that aborts halfway is worse than one that reports a problem and continues.
teardown() {
    local code=$?

    if [ "$TEARDOWN_DONE" = true ]; then
        exit "$code"
    fi
    TEARDOWN_DONE=true

    if [ "$STARTED_APP" = false ] && [ "$STARTED_DEPS" = false ]; then
        exit "$code"
    fi

    if [ "$KEEP" = true ]; then
        printf '\n'
        stack_log '--keep: leaving everything this run started up'
        stack_log 'stop it with ./stop.sh'
        exit "$code"
    fi

    printf '\n'
    stack_log 'tearing down what this run started'
    if [ "$STARTED_APP" = true ]; then
        stack_stop_app || true
    fi
    if [ "$STARTED_DEPS" = true ]; then
        stack_compose_down keep-volumes || true
    fi
    exit "$code"
}

# Reads one field from a JSON document on stdin.
json_field() {
    python3 -c 'import json,sys
doc = json.load(sys.stdin)
for key in sys.argv[1].split("."):
    doc = doc[key] if isinstance(doc, dict) else doc
print("" if doc is None else doc)' "$1"
}

# True when the assets array on stdin contains the given id.
json_has_asset() {
    python3 -c 'import json,sys
ids = [a["id"] for a in json.load(sys.stdin)]
sys.exit(0 if sys.argv[1] in ids else 1)' "$1"
}

json_len() {
    python3 -c 'import json,sys; print(len(json.load(sys.stdin)))'
}

# Reads the value of a Prometheus counter from a scrape on stdin. Prints 0 when absent.
prom_counter() {
    python3 -c 'import sys
name = sys.argv[1]
for line in sys.stdin:
    if line.startswith(name):
        print(float(line.rsplit(" ", 1)[1]))
        break
else:
    print(0.0)' "$1"
}

require_client_credentials() {
    local missing
    mapfile -t missing < <(credentials_missing "${CREDENTIAL_CLIENT_VARS[@]}")

    if [ "${#missing[@]}" -gt 0 ]; then
        printf 'error: not set: %s\n\n' "${missing[*]}" >&2
        printf 'An application is already running, so this script did not start one and has\n' >&2
        printf 'no way to know which credentials it was given. They must match, so they have\n' >&2
        printf 'to come from you:\n\n' >&2
        printf '  export LEDGER_CLIENT_ID=...\n' >&2
        printf '  export LEDGER_CLIENT_SECRET=...\n\n' >&2
        printf 'Or stop it with ./stop.sh and run ./demo.sh again, and this script will\n' >&2
        printf 'generate everything itself.\n' >&2
        exit 1
    fi
}

# Decides who owns the application for this run, and starts it when nobody else does.
establish_app() {
    if stack_app_healthy; then
        stack_log "using the application already running on ${STACK_MGMT_PORT}"
        stack_log 'it was not started here, so it will be left running'
        require_client_credentials
        return 0
    fi

    if [ "$NO_START" = true ]; then
        printf 'error: no healthy application at %s\n\n' "$MGMT_URL" >&2
        # %s form, not a bare format string: a format beginning with "--" is parsed as
        # an option terminator and printf rejects the rest.
        printf '%s\n' '--no-start was given, so nothing will be started. Either drop the flag and' >&2
        printf 'let this script own the lifecycle, or start one yourself:\n\n' >&2
        printf '  ./run.sh --dev\n' >&2
        exit 1
    fi

    stack_log 'nothing running: this run will start it and tear it down afterwards'

    # All four, in this process. The application inherits them; nothing else sees them.
    credentials_generate

    if stack_deps_running; then
        stack_log 'dependencies are already up, leaving them to whoever started them'
    else
        stack_compose_up
        STARTED_DEPS=true
    fi

    stack_start_app
    STARTED_APP=true
}

run_steps() {
    local asset_id name_back verify_valid before after unknown_uuid

    step "GET ${MGMT_URL}/actuator/health — unauthenticated, the one public route"
    auth_request_anonymous GET "${MGMT_URL}/actuator/health"
    expect_status 200 "$AUTH_STATUS" 'health is public'
    detail "status: $(printf '%s' "$AUTH_BODY" | json_field status)"

    step "GET ${BASE_URL}/api/v1/assets — no token, the gate must refuse"
    auth_request_anonymous GET "${BASE_URL}/api/v1/assets"
    expect_status 401 "$AUTH_STATUS" 'anonymous read is refused'
    detail 'no bearer token was sent, and none was accepted'

    step "POST ${BASE_URL}/api/v1/auth/token — obtain a bearer token"
    auth_fetch_token "$BASE_URL" || die 'could not obtain a token'
    ok "token obtained and cached for this run (fetches: ${AUTH_FETCH_COUNT})"
    detail 'the token is held in memory only and is never printed'

    # Baseline for the counter assertion in the final step.
    auth_request "$BASE_URL" GET "${MGMT_URL}/actuator/prometheus"
    expect_status 200 "$AUTH_STATUS" 'scrape endpoint accepts the token'
    before="$(printf '%s' "$AUTH_BODY" | prom_counter ledger_assets_registered_total)"
    detail "ledger_assets_registered_total before: ${before}"

    step "POST ${BASE_URL}/api/v1/assets — register an asset"
    auth_request "$BASE_URL" POST "${BASE_URL}/api/v1/assets" \
        "$(printf '{"name":"%s","assetType":"%s","owner":"%s","metadata":{"jurisdiction":"GB"}}' \
            "$ASSET_NAME" "$ASSET_TYPE" "$ASSET_OWNER")"
    expect_status 201 "$AUTH_STATUS" 'asset registered'
    asset_id="$(printf '%s' "$AUTH_BODY" | json_field id)"
    [ -n "$asset_id" ] || die 'response carried no id'
    detail "id:           ${asset_id}"
    detail "payloadHash:  $(printf '%s' "$AUTH_BODY" | json_field payloadHash)"
    detail "signer:       $(printf '%s' "$AUTH_BODY" | json_field signerAddress)"
    detail "anchored:     $(printf '%s' "$AUTH_BODY" | json_field anchored)"

    step "GET ${BASE_URL}/api/v1/assets — the new asset is listed"
    auth_request "$BASE_URL" GET "${BASE_URL}/api/v1/assets"
    expect_status 200 "$AUTH_STATUS" 'assets listed'
    printf '%s' "$AUTH_BODY" | json_has_asset "$asset_id" || die "listing does not contain ${asset_id}"
    ok "listing contains ${asset_id}"
    detail "assets held: $(printf '%s' "$AUTH_BODY" | json_len)"

    step "GET ${BASE_URL}/api/v1/assets/${asset_id} — fetch it back"
    auth_request "$BASE_URL" GET "${BASE_URL}/api/v1/assets/${asset_id}"
    expect_status 200 "$AUTH_STATUS" 'asset fetched'
    name_back="$(printf '%s' "$AUTH_BODY" | json_field name)"
    [ "$name_back" = "$ASSET_NAME" ] || die "name came back as '${name_back}'"
    [ "$(printf '%s' "$AUTH_BODY" | json_field assetType)" = "$ASSET_TYPE" ] || die 'assetType differs'
    [ "$(printf '%s' "$AUTH_BODY" | json_field owner)" = "$ASSET_OWNER" ] || die 'owner differs'
    ok 'name, assetType and owner match what was registered'

    step "POST ${BASE_URL}/api/v1/assets/${asset_id}/verify — recover the signer"
    auth_request "$BASE_URL" POST "${BASE_URL}/api/v1/assets/${asset_id}/verify"
    expect_status 200 "$AUTH_STATUS" 'verification ran'
    verify_valid="$(printf '%s' "$AUTH_BODY" | json_field signatureValid)"
    [ "$verify_valid" = "True" ] || die "signatureValid came back ${verify_valid}"
    ok 'signatureValid: true'
    detail "recovered:     $(printf '%s' "$AUTH_BODY" | json_field recoveredAddress)"
    detail "stored signer: $(printf '%s' "$AUTH_BODY" | json_field signerAddress)"
    detail 'the address recovered from the signature equals the stored signer'

    step "GET ${BASE_URL}/api/v1/assets/{unknown} — an id nobody registered"
    unknown_uuid="$(python3 -c 'import uuid; print(uuid.uuid4())')"
    auth_request "$BASE_URL" GET "${BASE_URL}/api/v1/assets/${unknown_uuid}"
    expect_status 404 "$AUTH_STATUS" 'unknown asset is a 404'
    detail "title: $(printf '%s' "$AUTH_BODY" | json_field title)"

    step "GET ${MGMT_URL}/actuator/prometheus — the domain counter moved"
    auth_request "$BASE_URL" GET "${MGMT_URL}/actuator/prometheus"
    expect_status 200 "$AUTH_STATUS" 'scrape returned'
    after="$(printf '%s' "$AUTH_BODY" | prom_counter ledger_assets_registered_total)"
    detail "ledger_assets_registered_total: ${before} -> ${after}"
    python3 -c 'import sys; sys.exit(0 if float(sys.argv[2]) > float(sys.argv[1]) else 1)' \
        "$before" "$after" || die "counter did not increase (${before} -> ${after})"
    ok 'counter incremented by the registration above'
}

main() {
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --keep) KEEP=true ;;
            --no-start) NO_START=true ;;
            --base-url)
                [ "$#" -ge 2 ] || { printf 'error: --base-url needs a value\n' >&2; exit 2; }
                BASE_URL="$2"
                shift
                ;;
            --mgmt-url)
                [ "$#" -ge 2 ] || { printf 'error: --mgmt-url needs a value\n' >&2; exit 2; }
                MGMT_URL="$2"
                shift
                ;;
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

    BASE_URL="${BASE_URL%/}"
    MGMT_URL="${MGMT_URL%/}"

    # Installed before anything is started, so an interrupt during startup still cleans up.
    trap teardown EXIT
    # An interrupt exits, which runs the EXIT trap above. 130 is the conventional status.
    trap 'exit 130' INT TERM

    printf '\033[1mAPI\033[0m       %s\n' "$BASE_URL"
    printf '\033[1mTelemetry\033[0m %s\n' "$MGMT_URL"

    establish_app
    run_steps

    printf '\n\033[1;32mAll %d steps passed.\033[0m\n' "$STEP"
    printf 'The gate held: step 2 was refused without a token, and every step after it\n'
    printf 'carried one. No token or secret appears anywhere in this output.\n'
}

main "$@"
