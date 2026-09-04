#!/usr/bin/env bash
#
# Mutation testing: how much of the test suite actually checks anything.
#
# Line coverage answers "was this line executed". Mutation testing answers the question
# that matters — "if this line were wrong, would a test fail". PIT changes one instruction
# at a time (a > becomes >=, a return becomes null, a call is deleted) and reruns the tests
# that cover it. A mutant that dies is a line some assertion is genuinely watching. A mutant
# that survives is a line the suite executes but does not check, which is exactly the gap a
# 100% coverage number cannot show you.
#
# Report only. Nothing here fails a build on a low score, and the profile this drives is
# bound to no lifecycle phase, so a normal build and every CI job are untouched.
#
# This script deliberately shares nothing with lib/stack.sh: mutation analysis needs no
# application, no containers, and no credentials, and coupling a test tool to the runtime
# stack would only mean it could break for reasons that have nothing to do with tests.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
readonly REPORT_DIR="${SCRIPT_DIR}/target/pit-reports"
readonly REPORT_XML="${REPORT_DIR}/mutations.xml"
readonly REPORT_HTML="${REPORT_DIR}/index.html"

CLASS_PATTERN=""
OPEN_HTML=false

usage() {
    cat <<'USAGE'
Usage: mutants.sh [--class PATTERN] [--html] [--help]

  (no flags)        Mutate every class under com.pigfox.ledger and report.
  --class PATTERN   Scope the run to matching classes, e.g. --class '*.AssetService'
                    or --class com.pigfox.ledger.crypto.*. Much faster for a rerun
                    while working on one class.
  --html            Also open the HTML report at the end, or name its path when
                    there is no way to open a browser.
  --help            Show this message.

Needs no running application, no containers and no credentials. It compiles the
code and runs the existing test suite many times over, so expect it to take
considerably longer than a normal build.
USAGE
}

log() {
    printf '==> %s\n' "$1"
}

fail() {
    printf 'error: %s\n' "$1" >&2
    exit 1
}

run_analysis() {
    # test-compile first: the pitest goal does not compile, so invoking it alone would
    # happily analyse whatever stale classes happen to be in target/ from an earlier build.
    local args=(-B -P mutation test-compile org.pitest:pitest-maven:mutationCoverage)

    if [ -n "$CLASS_PATTERN" ]; then
        log "mutating ${CLASS_PATTERN}"
        args+=("-DtargetClasses=${CLASS_PATTERN}")
    else
        log 'mutating com.pigfox.ledger.* against the whole suite'
    fi
    log 'this runs the tests once for coverage, then again per surviving candidate'

    # PIT writes its own progress; let it through rather than hiding a long silence.
    (cd "$SCRIPT_DIR" && ./mvnw "${args[@]}") || fail 'mutation analysis did not complete'
}

summarise() {
    [ -f "$REPORT_XML" ] || fail "no report at ${REPORT_XML}"

    python3 - "$REPORT_XML" <<'PYTHON'
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

root = ET.parse(sys.argv[1]).getroot()

status_counts = defaultdict(int)
survivors_by_class = defaultdict(list)
total = 0

for mutation in root.iter("mutation"):
    total += 1
    status = mutation.get("status", "UNKNOWN")
    status_counts[status] += 1
    if status in ("SURVIVED", "NO_COVERAGE"):
        mutator = (mutation.findtext("mutator") or "").rsplit(".", 1)[-1]
        survivors_by_class[mutation.findtext("mutatedClass") or "?"].append(
            {
                "line": int(mutation.findtext("lineNumber") or 0),
                "mutator": mutator.removesuffix("Mutator"),
                "method": mutation.findtext("mutatedMethod") or "?",
                "description": mutation.findtext("description") or "",
                "status": status,
            }
        )

# PIT counts anything it detected as killed, timeouts and memory errors included: the
# mutant changed observable behaviour and the suite noticed.
killed = status_counts["KILLED"]
timed_out = status_counts["TIMED_OUT"]
memory = status_counts["MEMORY_ERROR"]
survived = status_counts["SURVIVED"]
no_coverage = status_counts["NO_COVERAGE"]
detected = killed + timed_out + memory
other = total - detected - survived - no_coverage

score = (100.0 * detected / total) if total else 100.0
# Test strength ignores mutants no test reaches, so it measures the assertions rather
# than the reach of the suite.
reachable = detected + survived
strength = (100.0 * detected / reachable) if reachable else 100.0

print()
print(f"  Mutation score   {score:.1f}%   ({detected}/{total} mutants detected)")
print(f"  Test strength    {strength:.1f}%   (ignoring mutants no test reaches)")
print()
print(f"  generated        {total}")
print(f"  killed           {killed}")
if timed_out:
    print(f"  timed out        {timed_out}   (counted as detected)")
if memory:
    print(f"  memory error     {memory}   (counted as detected)")
print(f"  survived         {survived}")
print(f"  no coverage      {no_coverage}")
if other:
    print(f"  other            {other}")

if not survivors_by_class:
    print()
    print("  No surviving mutants. Every mutation the suite reached was caught.")
    sys.exit(0)

print()
print(f"  Surviving mutants ({survived + no_coverage}), worst class first:")

# Worst first: most survivors, then alphabetically so repeated runs read the same.
for cls, mutants in sorted(survivors_by_class.items(), key=lambda kv: (-len(kv[1]), kv[0])):
    print()
    print(f"  {cls}  ({len(mutants)})")
    for m in sorted(mutants, key=lambda m: (m["line"], m["mutator"])):
        flag = "" if m["status"] == "SURVIVED" else "  [NO COVERAGE]"
        print(f"    line {m['line']:>4}  {m['mutator']:<28} {m['method']}(){flag}")
        if m["description"]:
            print(f"               {m['description']}")
PYTHON
}

show_html() {
    [ -f "$REPORT_HTML" ] || fail "no HTML report at ${REPORT_HTML}"

    printf '\n'
    log "HTML report: ${REPORT_HTML}"
    if [ "$OPEN_HTML" = true ]; then
        if command -v xdg-open >/dev/null 2>&1; then
            xdg-open "$REPORT_HTML" >/dev/null 2>&1 &
            log 'opened in your browser'
        else
            log 'no xdg-open available, so the path above is the report'
        fi
    fi
}

main() {
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --class)
                [ "$#" -ge 2 ] || fail '--class needs a pattern'
                CLASS_PATTERN="$2"
                shift
                ;;
            --html) OPEN_HTML=true ;;
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

    run_analysis
    summarise
    show_html
}

main "$@"
