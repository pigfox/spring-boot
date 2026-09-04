#!/usr/bin/env bash
#
# Bearer-token helper for the demo scripts.
#
# This adds no privilege and weakens nothing. It is an ordinary API client: it calls the
# same public POST /api/v1/auth/token that any caller uses, with the same client
# credentials, and puts the resulting bearer on the same Authorization header the server
# already demands. There is no bypass here, no dev profile, and no route made public. An
# unauthenticated request still gets 401, which demo.sh asserts before it ever asks for a
# token.
#
# Two rules shape the implementation.
#
#   The token never crosses a boundary where it could be observed. It lives in one shell
#   variable for the life of the process, is never echoed, never written to disk, and never
#   passed as a command-line argument — a header on a curl command line is visible to
#   anyone who can run ps. It reaches curl through a config on stdin instead. The client
#   secret reaches the token endpoint the same way, as a request body on stdin.
#
#   A 401 is treated as an expired token, once. Tokens last fifteen minutes and a long demo
#   could outlive one, so a 401 triggers a single re-fetch and one retry. A second 401 is a
#   real authorization failure and is returned to the caller rather than retried forever.
#
# Callers use auth_request and read AUTH_STATUS and AUTH_BODY.

# Cached for the life of the run. Deliberately not readonly and not exported: a child
# process has no business inheriting it.
AUTH_TOKEN=""

# Set by auth_request and _auth_curl.
AUTH_STATUS=""
AUTH_BODY=""

# Number of times a token has been fetched, so a demo can show the cache is working.
AUTH_FETCH_COUNT=0

auth_fail() {
    printf 'auth: %s\n' "$1" >&2
    return 1
}

# Splits curl's response into AUTH_BODY and AUTH_STATUS. The status is appended by
# -w on its own final line, so the body is everything before it.
_auth_split_response() {
    local raw="$1"
    AUTH_STATUS="${raw##*$'\n'}"
    AUTH_BODY="${raw%$'\n'*}"
    # A body-less response leaves body and status identical; normalise that to empty.
    if [ "$AUTH_BODY" = "$AUTH_STATUS" ]; then
        AUTH_BODY=""
    fi
}

# Exchanges client credentials for a token. The credentials go over stdin as the request
# body, so they never appear in the process table.
auth_fetch_token() {
    local url="${1}/api/v1/auth/token"
    local raw

    [ -n "${LEDGER_CLIENT_ID:-}" ] || auth_fail 'LEDGER_CLIENT_ID is not set' || return 1
    [ -n "${LEDGER_CLIENT_SECRET:-}" ] || auth_fail 'LEDGER_CLIENT_SECRET is not set' || return 1

    raw="$(printf '{"clientId":"%s","clientSecret":"%s"}' \
        "$LEDGER_CLIENT_ID" "$LEDGER_CLIENT_SECRET" |
        curl -sS -X POST \
            -H 'Content-Type: application/json' \
            --data @- \
            -w '\n%{http_code}' \
            "$url")" || return 1

    _auth_split_response "$raw"
    if [ "$AUTH_STATUS" != "200" ]; then
        auth_fail "token endpoint returned ${AUTH_STATUS}"
        return 1
    fi

    AUTH_TOKEN="$(printf '%s' "$AUTH_BODY" | _auth_extract_token)"
    [ -n "$AUTH_TOKEN" ] || auth_fail 'token endpoint returned no accessToken' || return 1
    AUTH_FETCH_COUNT=$((AUTH_FETCH_COUNT + 1))
}

# Reads accessToken from stdin. Kept separate so the token never becomes an argument.
_auth_extract_token() {
    python3 -c 'import json,sys; print(json.load(sys.stdin).get("accessToken",""))'
}

# Performs one authenticated request. The bearer reaches curl through a config on stdin.
_auth_curl() {
    local method="$1" url="$2" body="${3:-}"
    local raw
    local args=(-sS -X "$method" -w '\n%{http_code}')

    if [ -n "$body" ]; then
        # The body is asset data, not a credential, so a command line is fine for it —
        # and stdin is already carrying the Authorization header.
        args+=(-H 'Content-Type: application/json' -d "$body")
    fi

    raw="$(printf 'header = "Authorization: Bearer %s"\n' "$AUTH_TOKEN" |
        curl "${args[@]}" --config - "$url")" || return 1
    _auth_split_response "$raw"
}

# Performs an unauthenticated request, for asserting that the gate is closed.
#
# Usage: auth_request_anonymous <method> <url>
auth_request_anonymous() {
    local method="$1" url="$2"
    local raw

    raw="$(curl -sS -X "$method" -w '\n%{http_code}' "$url")" || return 1
    _auth_split_response "$raw"
}

# Performs an authenticated request, fetching a token if there is none and retrying once
# if the server says the one held has expired.
#
# Usage: auth_request <base-url-for-token> <method> <url> [json-body]
# Sets:  AUTH_STATUS, AUTH_BODY
auth_request() {
    local token_base="$1" method="$2" url="$3" body="${4:-}"

    if [ -z "$AUTH_TOKEN" ]; then
        auth_fetch_token "$token_base" || return 1
    fi

    _auth_curl "$method" "$url" "$body" || return 1

    if [ "$AUTH_STATUS" = "401" ]; then
        # One retry: assume the cached token aged out mid-run.
        auth_fetch_token "$token_base" || return 1
        _auth_curl "$method" "$url" "$body" || return 1
    fi
}
