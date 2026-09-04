#!/usr/bin/env bash
# shellcheck shell=bash
#
# The four credentials the application refuses to start without, and the two ways of
# getting them: demand them from the environment, or generate throwaway ones.
#
# One implementation, shared by run.sh and demo.sh, so "which variables are required" and
# "how is a dev secret made" cannot drift apart between the two.
#
# Nothing here prints a value. Generation reports which names it filled in and which it
# left alone, and that is all. There is no .env file: none is read, none is written.
# Sourced, never executed.

# Bound in application.yml from these exact names, each with no default, so a missing one
# is a startup failure rather than a silent misconfiguration.
CREDENTIAL_VARS=(
    LEDGER_SIGNING_KEY
    LEDGER_JWT_SECRET
    LEDGER_CLIENT_ID
    LEDGER_CLIENT_SECRET
)
readonly CREDENTIAL_VARS

# The pair a client needs to obtain a token. A caller reusing an already-running
# application needs these but has no use for the signing key or the JWT secret.
CREDENTIAL_CLIENT_VARS=(
    LEDGER_CLIENT_ID
    LEDGER_CLIENT_SECRET
)
readonly CREDENTIAL_CLIENT_VARS

# Prints the names of any listed variables that are unset or empty.
credentials_missing() {
    local var
    for var in "$@"; do
        if [ -z "${!var:-}" ]; then
            printf '%s\n' "$var"
        fi
    done
}

# Fails unless all four are present. This is the default path: a service that signs things
# should not invent its own key.
credentials_require_all() {
    local missing
    mapfile -t missing < <(credentials_missing "${CREDENTIAL_VARS[@]}")

    if [ "${#missing[@]}" -gt 0 ]; then
        printf 'error: %s required environment variable(s) not set:\n' "${#missing[@]}" >&2
        printf '  %s\n' "${missing[@]}" >&2
        printf '\nExport them in your shell. There is no .env file and none will be read.\n' >&2
        printf 'Or use --dev to generate throwaway values for local use.\n' >&2
        exit 1
    fi
    printf '==> environment: all %s required variables present\n' "${#CREDENTIAL_VARS[@]}"
}

# Sets one variable only when it is unset or empty, so anything already exported wins.
# The value is assigned by name and never passes through this function's output.
credentials_set_if_absent() {
    local var="$1" value="$2"

    if [ -n "${!var:-}" ]; then
        printf '    %-22s kept (already exported)\n' "$var"
        return 0
    fi
    printf -v "$var" '%s' "$value"
    export "${var?}"
    printf '    %-22s generated\n' "$var"
}

credentials_warn_throwaway() {
    cat <<'WARNING'

  ############################################################################
  #  THROWAWAY CREDENTIALS FOR LOCAL USE ONLY                                #
  #                                                                          #
  #  Generated fresh, held only in this process tree, and inherited by the   #
  #  application. They are never printed, never written to disk, and never   #
  #  passed on a command line. Nothing signed with them means anything, and  #
  #  no token issued under them survives a restart.                          #
  #                                                                          #
  #  Never use generated credentials for anything you would mind forging.    #
  ############################################################################

WARNING
}

# Generates any of the four that is not already exported.
credentials_generate() {
    command -v openssl >/dev/null 2>&1 ||
        { printf 'error: generating credentials needs openssl, which is not on PATH\n' >&2; exit 1; }

    credentials_warn_throwaway
    credentials_set_if_absent LEDGER_SIGNING_KEY "$(openssl rand -hex 32)"
    credentials_set_if_absent LEDGER_JWT_SECRET "$(openssl rand -base64 48)"
    credentials_set_if_absent LEDGER_CLIENT_ID demo
    credentials_set_if_absent LEDGER_CLIENT_SECRET "$(openssl rand -hex 24)"
    printf '\n'
}
