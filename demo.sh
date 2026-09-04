#!/usr/bin/env bash
#
# Walks every endpoint against a running application and asserts each status code.
#
# This is a check, not a tour: every step states the status it expects and the script exits
# non-zero on the first mismatch, naming the step that failed. Step 2 is the important one
# — it proves an unauthenticated caller is refused before any token exists, so nothing that
# follows can be mistaken for the gate being open.
#
# The script never starts the application. If nothing is listening it says so and stops,
# because silently starting a server is not something a demo should do behind your back.
#
# No secret, token, or key appears in the output.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR

# shellcheck source=lib/auth.sh
. "${SCRIPT_DIR}/lib/auth.sh"

# The API. Actuator is not here: this service serves telemetry on its own port so a network
# policy can expose probes and scrapes without exposing the API, so health and prometheus
# have their own base URL.
BASE_URL="http://localhost:8087"
MGMT_URL="http://localhost:55437"

STEP=0
ASSET_NAME="Unit 4B, Harbour Court"
ASSET_TYPE="REAL_ESTATE"
ASSET_OWNER="Estate JV"

usage() {
    cat <<'USAGE'
Usage: demo.sh [--base-url URL] [--mgmt-url URL] [--help]

  --base-url URL  API base. Default http://localhost:8087
  --mgmt-url URL  Actuator base. Default http://localhost:55437
                  Telemetry runs on its own port in this service, so health and
                  prometheus are not under --base-url.
  --help          Show this message.

Requires LEDGER_CLIENT_ID and LEDGER_CLIENT_SECRET, the same credentials the running
application was started with. The application must already be running; this script will
not start it.
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

require_running() {
    if ! curl -fsS -o /dev/null --max-time 5 "${MGMT_URL}/actuator/health" 2>/dev/null; then
        printf 'error: no healthy application at %s\n\n' "$MGMT_URL" >&2
        printf 'Start it first:\n' >&2
        printf '  ./run.sh --dev        # generates throwaway credentials\n' >&2
        printf '  ./run.sh              # uses the credentials already in your environment\n' >&2
        printf '\nThis script does not start the application itself.\n' >&2
        exit 1
    fi
}

require_credentials() {
    local missing=()
    [ -n "${LEDGER_CLIENT_ID:-}" ] || missing+=(LEDGER_CLIENT_ID)
    [ -n "${LEDGER_CLIENT_SECRET:-}" ] || missing+=(LEDGER_CLIENT_SECRET)

    if [ "${#missing[@]}" -gt 0 ]; then
        printf 'error: not set: %s\n\n' "${missing[*]}" >&2
        printf 'These must be the same credentials the running application was started with.\n\n' >&2
        printf 'If you started it with ./run.sh --dev and did not export these yourself, the\n' >&2
        printf 'client secret it generated exists only inside the application process. It was\n' >&2
        printf 'deliberately never printed or written anywhere, so nothing can recover it and\n' >&2
        printf 'no client can authenticate. Export them first, then start:\n\n' >&2
        printf '  export LEDGER_CLIENT_ID=demo\n' >&2
        # SC2016: the literal $(...) is the point — this line is copy-paste guidance.
        # shellcheck disable=SC2016
        printf '  export LEDGER_CLIENT_SECRET=$(openssl rand -hex 24)\n' >&2
        printf '  ./run.sh --dev     # keeps both, generates only the signing key and JWT secret\n' >&2
        printf '  ./demo.sh\n\n' >&2
        exit 1
    fi
}

main() {
    while [ "$#" -gt 0 ]; do
        case "$1" in
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

    printf '\033[1mAPI\033[0m       %s\n' "$BASE_URL"
    printf '\033[1mTelemetry\033[0m %s\n' "$MGMT_URL"

    require_running
    require_credentials

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
    detail "recovered:    $(printf '%s' "$AUTH_BODY" | json_field recoveredAddress)"
    detail "stored signer:$(printf '%s' "$AUTH_BODY" | json_field signerAddress)"
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

    printf '\n\033[1;32mAll %d steps passed.\033[0m\n' "$STEP"
    printf 'The gate held: step 2 was refused without a token, and every step after it\n'
    printf 'carried one. No token or secret appears anywhere in this output.\n\n'
}

main "$@"
