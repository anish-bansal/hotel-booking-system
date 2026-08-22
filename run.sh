#!/usr/bin/env bash
#
# run.sh — start the hotel booking service, connect its external services, and prove it works.
#
# The distinction this script is built around: starting a process is not the same as having a working
# service. So it does not just launch and print a URL. It waits for real readiness signals, asserts
# that each external dependency is genuinely reachable, and then drives the whole
# search -> book -> pay -> cancel flow through the live API with a pass/fail per step.
#
# If this script prints ALL CHECKS PASSED, the service is not merely up — the booking flow, the
# idempotency guarantee and the inventory release have all just been exercised against it.
#
# Usage:  ./run.sh [options]
#   (no options)   H2 in-memory. Zero dependencies.
#   --postgres     Real Postgres via Docker.
#   --redis        Real Redis via Docker (swaps in the cluster-safe sweeper lock).
#   --all          Both of the above.
#   --seed         Force demo data on (default with H2, off with Postgres).
#   --test         Run the full test suite before starting.
#   --smoke        Verify, then shut down and exit. For CI.
#   --no-verify    Start only; skip the end-to-end checks.
#   --port N       HTTP port (default 8080).
#   --stop         Stop a running server and its containers, then exit.
#   --doctor       Report what is installed and what is missing, then exit.
#   --install-deps Install anything missing via Homebrew, then carry on.
#   --dry-run      Show the install commands without running them, then exit.
#   --help
#
# Written for macOS. Uses only bash 3.2 features, since that is what /bin/bash on macOS still is.

set -euo pipefail

# ------------------------------------------------------------------------------------------------
# Configuration
# ------------------------------------------------------------------------------------------------

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN_DIR="$PROJECT_DIR/.run"
PID_FILE="$RUN_DIR/server.pid"
SERVER_LOG="$RUN_DIR/server.log"

PORT=8080
USE_POSTGRES=false
USE_REDIS=false
FORCE_SEED=false
RUN_TESTS=false
SMOKE_ONLY=false
VERIFY=true
STOP_ONLY=false
AUTO_INSTALL=false
DRY_RUN=false
DOCTOR_ONLY=false

JAVA_BIN=""
JAVA_VERSION=""
PATH_JAVA_VERSION=""
JAVA_RESOLVED_VIA_JAVA_HOME=false

READY_TIMEOUT_SECONDS=120
DOCKER_TIMEOUT_SECONDS=90

CHECKS_PASSED=0
CHECKS_FAILED=0
SERVER_PID=""
STARTED_CONTAINERS=false

# ------------------------------------------------------------------------------------------------
# Output helpers
# ------------------------------------------------------------------------------------------------

if [ -t 1 ]; then
  BOLD=$'\033[1m'; DIM=$'\033[2m'; RED=$'\033[31m'; GREEN=$'\033[32m'
  YELLOW=$'\033[33m'; BLUE=$'\033[34m'; RESET=$'\033[0m'
else
  BOLD=''; DIM=''; RED=''; GREEN=''; YELLOW=''; BLUE=''; RESET=''
fi

section()  { printf '\n%s==> %s%s\n' "$BOLD$BLUE" "$1" "$RESET"; }
info()     { printf '    %s\n' "$1"; }
detail()   { printf '    %s%s%s\n' "$DIM" "$1" "$RESET"; }
warn()     { printf '    %s!  %s%s\n' "$YELLOW" "$1" "$RESET"; }
die()      { printf '\n%sx  %s%s\n\n' "$RED$BOLD" "$1" "$RESET" >&2; exit 1; }

pass() {
  CHECKS_PASSED=$((CHECKS_PASSED + 1))
  printf '    %s✓%s %s\n' "$GREEN" "$RESET" "$1"
}

fail() {
  CHECKS_FAILED=$((CHECKS_FAILED + 1))
  printf '    %s✗ %s%s\n' "$RED" "$1" "$RESET"
  if [ -n "${2:-}" ]; then
    printf '      %sgot: %s%s\n' "$DIM" "$2" "$RESET"
  fi
}

# Assert two values are equal. Every functional claim this script makes goes through here, so a
# failure always reports what was expected and what actually arrived.
expect_eq() {
  local label="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then
    pass "$label"
  else
    fail "$label — expected '$expected'" "$actual"
  fi
}

# Money needs its own comparison. "18000", "18000.0" and "18000.00" are the same amount, but JSON
# parsers disagree about which one they hand back — so both sides are normalised to two decimal
# places before comparing. Without this the price assertions would fail on a formatting difference
# and look like a pricing bug.
normalize_amount() { printf '%s' "$1" | awk '{ printf "%.2f", $0 + 0 }'; }

expect_money() {
  expect_eq "$1" "$(normalize_amount "$2")" "$(normalize_amount "$3")"
}

# 2,29 is exactly the header comment block. A wider range spills `set -euo pipefail` and the
# section banners into the help text.
usage() { sed -n '2,29p' "${BASH_SOURCE[0]}" | sed 's/^#\{1,2\} \{0,1\}//'; exit 0; }

# ------------------------------------------------------------------------------------------------
# Argument parsing
# ------------------------------------------------------------------------------------------------

while [ $# -gt 0 ]; do
  case "$1" in
    --postgres)  USE_POSTGRES=true ;;
    --redis)     USE_REDIS=true ;;
    --all)       USE_POSTGRES=true; USE_REDIS=true ;;
    --seed)      FORCE_SEED=true ;;
    --test)      RUN_TESTS=true ;;
    --smoke)     SMOKE_ONLY=true ;;
    --no-verify) VERIFY=false ;;
    --stop)      STOP_ONLY=true ;;
    --install-deps) AUTO_INSTALL=true ;;
    --dry-run)   DRY_RUN=true; AUTO_INSTALL=true ;;
    --doctor)    DOCTOR_ONLY=true ;;
    --port)      shift; PORT="${1:-}"; [ -n "$PORT" ] || die "--port needs a value" ;;
    --help|-h)   usage ;;
    *)           die "Unknown option: $1  (try --help)" ;;
  esac
  shift
done

BASE_URL="http://localhost:$PORT"
mkdir -p "$RUN_DIR"

# ------------------------------------------------------------------------------------------------
# Docker helpers
# ------------------------------------------------------------------------------------------------

# `docker compose` (v2 plugin) vs `docker-compose` (v1 standalone). Both are still in the wild.
compose() {
  if docker compose version >/dev/null 2>&1; then
    docker compose -f "$PROJECT_DIR/docker-compose.yml" "$@"
  else
    docker-compose -f "$PROJECT_DIR/docker-compose.yml" "$@"
  fi
}

needs_docker() { [ "$USE_POSTGRES" = true ] || [ "$USE_REDIS" = true ]; }

# ------------------------------------------------------------------------------------------------
# Shutdown
# ------------------------------------------------------------------------------------------------

stop_server() {
  local pid=""
  if [ -f "$PID_FILE" ]; then
    pid="$(cat "$PID_FILE" 2>/dev/null || true)"
  fi
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    info "Stopping server (pid $pid)"
    # SIGTERM first so Spring runs its shutdown hooks and closes the connection pool cleanly.
    kill "$pid" 2>/dev/null || true
    local waited=0
    while kill -0 "$pid" 2>/dev/null && [ "$waited" -lt 15 ]; do
      sleep 1; waited=$((waited + 1))
    done
    if kill -0 "$pid" 2>/dev/null; then
      warn "Server ignored SIGTERM; sending SIGKILL"
      kill -9 "$pid" 2>/dev/null || true
    fi
  fi
  rm -f "$PID_FILE"
}

cleanup() {
  local exit_code=$?
  trap - EXIT INT TERM
  if [ -n "$SERVER_PID" ]; then
    printf '\n'
    stop_server
  fi
  if [ "$STARTED_CONTAINERS" = true ] && [ "$SMOKE_ONLY" = true ]; then
    info "Stopping containers"
    compose down >/dev/null 2>&1 || true
  fi
  exit $exit_code
}

if [ "$STOP_ONLY" = true ]; then
  section "Stopping"
  stop_server
  # Kill anything still holding the port — a previous run killed with Ctrl-Z, say.
  if command -v lsof >/dev/null 2>&1; then
    orphan="$(lsof -ti ":$PORT" 2>/dev/null || true)"
    if [ -n "$orphan" ]; then
      info "Killing orphaned process on port $PORT (pid $orphan)"
      kill -9 $orphan 2>/dev/null || true
    fi
  fi
  # `docker info` rather than just `command -v docker`: the binary being installed says nothing
  # about the daemon running, and claiming to stop containers when Docker is off is a small lie.
  if [ -f "$PROJECT_DIR/docker-compose.yml" ] && command -v docker >/dev/null 2>&1 \
     && docker info >/dev/null 2>&1; then
    info "Stopping containers"
    compose down >/dev/null 2>&1 || true
  fi
  printf '\n%sStopped.%s\n\n' "$GREEN" "$RESET"
  exit 0
fi

trap cleanup EXIT INT TERM

# ------------------------------------------------------------------------------------------------
# JSON access
# ------------------------------------------------------------------------------------------------
#
# Paths are dotted with numeric indices: properties.0.roomTypes.0.id
# python3 is preferred because macOS has it wherever the Xcode command line tools are; jq is used if
# present. Parsing JSON with grep and sed was the other option, and it silently returns the wrong
# field the moment a response shape changes — not a good property for a script whose whole job is
# making assertions.

JSON_TOOL=""

json_get() {
  local json="$1" path="$2"
  case "$JSON_TOOL" in
    python3)
      printf '%s' "$json" | python3 -c '
import json, sys
try:
    # parse_float/parse_int as str keeps numbers exactly as the server wrote them. Without this,
    # 18000.00 becomes the float 18000.0 and prints as "18000.0" - which would make a correct
    # price assertion fail on formatting alone.
    node = json.load(sys.stdin, parse_float=str, parse_int=str)
except Exception:
    print("<invalid-json>"); sys.exit(0)
# Filtering empties lets an empty path mean "the whole document", which is how a top-level JSON
# array (the payment history) gets counted.
for part in [p for p in sys.argv[1].split(".") if p]:
    try:
        node = node[int(part)] if part.isdigit() else node[part]
    except Exception:
        print("<missing>"); sys.exit(0)
if isinstance(node, bool):
    print("true" if node else "false")
elif node is None:
    print("null")
elif isinstance(node, (list, dict)):
    print(json.dumps(node, separators=(",", ":"), sort_keys=True))
else:
    print(node)
' "$path"
      ;;
    jq)
      # Seeds empty, not ".", because each object key already contributes its own leading dot —
      # starting from "." would build the invalid filter `..\"key\"`.
      local filter=""
      local IFS_SAVE="$IFS"; IFS='.'
      for part in $path; do
        case "$part" in
          '')          ;;                                       # empty path segment: skip
          *[!0-9]*)    filter="$filter.\"$part\"" ;;            # object key
          *)           filter="${filter:-.}[$part]" ;;          # array index; needs `.[0]`, not `[0]`
        esac
      done
      IFS="$IFS_SAVE"
      [ -n "$filter" ] || filter="."                            # empty path = whole document
      local result
      # Deliberately NOT using jq's `//` alternative operator. In jq both `null // x` AND
      # `false // x` evaluate to x, so a field that is legitimately `false` would come back as
      # "<missing>" — and an assertion expecting "false" would then fail for the wrong reason
      # (or, worse, one expecting "<missing>" would pass for the wrong reason). jq's exit status
      # plus an explicit null check gets booleans right.
      if ! result="$(printf '%s' "$json" | jq -r "$filter" 2>/dev/null)"; then
        result="<missing>"
      fi
      # jq cannot distinguish an absent key from an explicit null — both yield null. They are
      # collapsed to "<missing>" here; nothing this script asserts on depends on the difference.
      if [ -z "$result" ] || [ "$result" = "null" ]; then
        result="<missing>"
      fi
      printf '%s\n' "$result"
      ;;
  esac
}

json_len() {
  local json="$1" path="$2"
  local value; value="$(json_get "$json" "$path")"
  case "$JSON_TOOL" in
    python3) printf '%s' "$value" | python3 -c 'import json,sys
try: print(len(json.load(sys.stdin)))
except Exception: print(-1)' ;;
    jq)      printf '%s' "$value" | jq -r 'length' 2>/dev/null || printf '%s' '-1' ;;
  esac
}

# Performs a request and splits the body from the status code. The status code is captured because a
# 200 where a 201 was expected is exactly the kind of thing that matters here (it is how an idempotent
# replay is distinguished from a fresh charge).
HTTP_BODY=""
HTTP_STATUS=""

http() {
  local method="$1" path="$2" body="${3:-}" ; shift 3 || shift $#
  local response
  if [ -n "$body" ]; then
    response="$(curl -sS -X "$method" "$BASE_URL$path" \
      -H 'Content-Type: application/json' "$@" -d "$body" -w $'\n%{http_code}' 2>&1)" || true
  else
    response="$(curl -sS -X "$method" "$BASE_URL$path" "$@" -w $'\n%{http_code}' 2>&1)" || true
  fi
  HTTP_STATUS="$(printf '%s' "$response" | tail -n 1)"
  HTTP_BODY="$(printf '%s' "$response" | sed '$d')"
}

# ------------------------------------------------------------------------------------------------
# Java discovery
# ------------------------------------------------------------------------------------------------

# Reads the major version out of a `java -version` banner.
#
# Two traps handled here. First, `head -n 1` is wrong: with JAVA_TOOL_OPTIONS or _JAVA_OPTIONS set,
# the JVM prints a "Picked up ..." line before the version, so the first line is noise. Second,
# Java 8 and earlier report "1.8.0_x", where the meaningful number is the *second* field.
java_major_of() {
  local java_bin="$1" version_line raw major
  version_line="$("$java_bin" -version 2>&1 | grep -E 'version "[^"]+"' | head -n 1)"
  [ -n "$version_line" ] || return 1

  raw="$(printf '%s' "$version_line" | sed -E 's/.*version "([^"]+)".*/\1/')"
  major="$(printf '%s' "$raw" | cut -d. -f1)"
  [ "$major" = "1" ] && major="$(printf '%s' "$raw" | cut -d. -f2)"

  # The major must be numeric before anyone compares it. A non-numeric value makes `-lt` error out,
  # and an errored test is falsy — so an unparseable version would sail through as if it passed.
  case "$major" in
    ''|*[!0-9]*) return 1 ;;
  esac
  printf '%s %s\n' "$major" "$raw"
}

# Finds a JDK 17+ even when the one on PATH is older.
#
# This matters on macOS more than anywhere else. Homebrew's `openjdk` is keg-only, so installing it
# does not put it on PATH; and plenty of Macs carry an ancient system Java ahead of a perfectly good
# modern JDK. `/usr/libexec/java_home` is the OS's own registry of installed JDKs, so asking it is
# how you find the JDK that is actually there rather than telling the user to fix their PATH.
resolve_java() {
  local result major

  # 1. An explicit JAVA_HOME wins if it is new enough — the user has already made a choice.
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    if result="$(java_major_of "$JAVA_HOME/bin/java")"; then
      major="${result%% *}"
      if [ "$major" -ge 17 ]; then
        JAVA_BIN="$JAVA_HOME/bin/java"; JAVA_VERSION="${result#* }"; return 0
      fi
    fi
  fi

  # 2. Whatever is on PATH.
  if command -v java >/dev/null 2>&1; then
    if result="$(java_major_of java)"; then
      major="${result%% *}"
      if [ "$major" -ge 17 ]; then
        JAVA_BIN="$(command -v java)"; JAVA_VERSION="${result#* }"; return 0
      fi
      PATH_JAVA_VERSION="${result#* }"
    fi
  fi

  # 3. Ask macOS for a 17+ JDK, newest first, before giving up on a machine that has one installed.
  if [ -x /usr/libexec/java_home ]; then
    local candidate
    for want in 23 22 21 20 19 18 17; do
      candidate="$(/usr/libexec/java_home -v "$want" 2>/dev/null || true)"
      if [ -n "$candidate" ] && [ -x "$candidate/bin/java" ]; then
        if result="$(java_major_of "$candidate/bin/java")"; then
          JAVA_HOME="$candidate"; export JAVA_HOME
          JAVA_BIN="$candidate/bin/java"; JAVA_VERSION="${result#* }"
          # Maven and the JVM both honour JAVA_HOME, so putting it in front of PATH is enough —
          # no sudo, no symlinks into /Library, nothing to undo later.
          PATH="$candidate/bin:$PATH"; export PATH
          JAVA_RESOLVED_VIA_JAVA_HOME=true
          return 0
        fi
      fi
    done
  fi
  return 1
}

# ------------------------------------------------------------------------------------------------
# Dependency installation
# ------------------------------------------------------------------------------------------------

BREW=""

have_brew() {
  if [ -n "$BREW" ]; then return 0; fi
  if command -v brew >/dev/null 2>&1; then BREW="$(command -v brew)"; return 0; fi
  # Homebrew is not on PATH in a fresh shell on Apple Silicon until the shellenv line is added.
  for candidate in /opt/homebrew/bin/brew /usr/local/bin/brew; do
    if [ -x "$candidate" ]; then BREW="$candidate"; return 0; fi
  done
  return 1
}

# Works out what is missing and either reports it or installs it.
#
# Kept as one function with a dry-run switch so the plan and the action cannot drift apart: the list
# of things it would install is, by construction, the list of things it does install.
plan_and_install_deps() {
  local mode="$1"   # report | install
  local missing_formulae="" missing_casks="" needs_java=false

  if ! resolve_java; then
    needs_java=true
    missing_formulae="$missing_formulae openjdk@21"
  fi
  # Maven is deliberately absent from this list: the repo ships ./mvnw, which downloads the exact
  # Maven this project was built against. Installing a system Maven would add a prerequisite and a
  # version variable for no benefit.
  # python3 covers this; jq is only needed as the fallback, so it is not worth installing eagerly.
  if ! command -v python3 >/dev/null 2>&1 && ! command -v jq >/dev/null 2>&1; then
    missing_formulae="$missing_formulae jq"
  fi
  if needs_docker && ! command -v docker >/dev/null 2>&1; then
    missing_casks="$missing_casks docker"
  fi

  if [ -z "$missing_formulae" ] && [ -z "$missing_casks" ]; then
    pass "All dependencies already present"
    return 0
  fi

  section "Dependencies"
  [ -n "$missing_formulae" ] && info "Missing:$missing_formulae"
  [ -n "$missing_casks" ] && info "Missing (cask):$missing_casks"

  if [ "$mode" = "report" ]; then
    printf '\n'
    info "Install them with:"
    [ -n "$missing_formulae" ] && printf '        brew install%s\n' "$missing_formulae"
    [ -n "$missing_casks" ] && printf '        brew install --cask%s\n' "$missing_casks"
    printf '\n'
    info "Or let this script do it:  ./run.sh --install-deps"
    return 1
  fi

  if ! have_brew; then
    die "Homebrew is needed to install dependencies but was not found. Install it with:
     /bin/bash -c \"\$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)\"
   then re-run ./run.sh --install-deps"
  fi
  detail "using $BREW"

  if [ "$DRY_RUN" = true ]; then
    warn "DRY RUN — commands that would be executed:"
    [ -n "$missing_formulae" ] && printf '        %s install%s\n' "$BREW" "$missing_formulae"
    [ -n "$missing_casks" ] && printf '        %s install --cask%s\n' "$BREW" "$missing_casks"
    [ "$needs_java" = true ] && printf '        export JAVA_HOME="$(%s --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"\n' "$BREW"
    return 0
  fi

  local formula
  for formula in $missing_formulae; do
    info "Installing $formula"
    "$BREW" install "$formula" || die "brew install $formula failed"
    pass "$formula installed"
  done
  for formula in $missing_casks; do
    info "Installing $formula (cask)"
    "$BREW" install --cask "$formula" || die "brew install --cask $formula failed"
    pass "$formula installed — start Docker Desktop before using --postgres/--redis"
  done

  # brew's openjdk is keg-only: installed but not linked, so PATH still will not find it. Point
  # JAVA_HOME at the keg for this run and tell the user the one line that makes it permanent.
  if [ "$needs_java" = true ]; then
    local keg
    keg="$("$BREW" --prefix openjdk@21 2>/dev/null || true)"
    if [ -n "$keg" ] && [ -x "$keg/libexec/openjdk.jdk/Contents/Home/bin/java" ]; then
      JAVA_HOME="$keg/libexec/openjdk.jdk/Contents/Home"; export JAVA_HOME
      PATH="$JAVA_HOME/bin:$PATH"; export PATH
      pass "JAVA_HOME set for this run"
      printf '\n'
      info "To make it permanent, add this to ~/.zshrc:"
      printf '        export JAVA_HOME="%s"\n' "$JAVA_HOME"
      printf '        export PATH="$JAVA_HOME/bin:$PATH"\n\n'
    fi
  fi
}

doctor() {
  section "Environment"
  if resolve_java; then
    pass "Java $JAVA_VERSION  ($JAVA_BIN)"
    [ "${JAVA_RESOLVED_VIA_JAVA_HOME:-false}" = true ] && \
      detail "found via /usr/libexec/java_home, not PATH — JAVA_HOME set for this run"
  else
    if [ -n "${PATH_JAVA_VERSION:-}" ]; then
      fail "Java on PATH is $PATH_JAVA_VERSION; 17+ required, and no newer JDK is installed"
    else
      fail "No JDK found"
    fi
  fi

  if [ -x "$PROJECT_DIR/mvnw" ]; then
    pass "Maven wrapper present (./mvnw — no system Maven needed)"
  else
    fail "./mvnw missing; regenerate it with: mvn -N wrapper:wrapper"
  fi

  for tool in curl python3 jq docker git; do
    if command -v "$tool" >/dev/null 2>&1; then
      pass "$tool"
    else
      detail "$tool not found$( [ "$tool" = docker ] && printf ' (only needed for --postgres/--redis)')"
    fi
  done

  if have_brew; then pass "Homebrew ($BREW)"; else detail "Homebrew not found"; fi

  printf '\n'
  plan_and_install_deps report || true
}

# ------------------------------------------------------------------------------------------------
# Preflight
# ------------------------------------------------------------------------------------------------

preflight() {
  section "Preflight"

  if ! resolve_java; then
    if [ "$AUTO_INSTALL" = true ]; then
      plan_and_install_deps install
      resolve_java || die "Still no JDK 17+ after installing. Run ./run.sh --doctor for details."
    elif [ -n "${PATH_JAVA_VERSION:-}" ]; then
      die "Java 17+ required, found $PATH_JAVA_VERSION and no newer JDK installed.
   Fix it with:  ./run.sh --install-deps     (or: brew install openjdk@21)"
    else
      die "No JDK found.
   Fix it with:  ./run.sh --install-deps     (or: brew install openjdk@21)"
    fi
  fi
  pass "Java $JAVA_VERSION"
  [ "${JAVA_RESOLVED_VIA_JAVA_HOME:-false}" = true ] && \
    detail "via /usr/libexec/java_home — JAVA_HOME set for this run"

  # No Maven check: ./mvnw bootstraps its own. A JDK is the only build prerequisite.
  [ -x "$PROJECT_DIR/mvnw" ] || die "./mvnw is missing or not executable.
   Fix it with:  mvn -N wrapper:wrapper     (or: chmod +x ./mvnw)"
  pass "Maven wrapper (./mvnw)"

  command -v curl >/dev/null 2>&1 || die "curl not found"

  if command -v python3 >/dev/null 2>&1; then
    JSON_TOOL=python3; pass "JSON via python3"
  elif command -v jq >/dev/null 2>&1; then
    JSON_TOOL=jq; pass "JSON via jq"
  else
    die "Need python3 or jq to check API responses. Try: brew install jq"
  fi

  if needs_docker; then
    command -v docker >/dev/null 2>&1 || die "Docker needed for --postgres/--redis but not on PATH"
    docker info >/dev/null 2>&1 || die "Docker is installed but not running. Start Docker Desktop."
    pass "Docker running"
  fi

  # Better to say so now than to let Spring fail with a bind exception 40 seconds into a build.
  if command -v lsof >/dev/null 2>&1 && lsof -i ":$PORT" >/dev/null 2>&1; then
    die "Port $PORT is already in use. Free it with './run.sh --stop', or pick another with --port."
  fi
  pass "Port $PORT free"
}

# ------------------------------------------------------------------------------------------------
# External services
# ------------------------------------------------------------------------------------------------

wait_for_container() {
  local service="$1" waited=0 status
  while [ "$waited" -lt "$DOCKER_TIMEOUT_SECONDS" ]; do
    # Ask Docker for the healthcheck verdict rather than sleeping and hoping. `docker compose up -d`
    # returns when containers are started, which is seconds before Postgres accepts connections.
    status="$(docker inspect --format '{{.State.Health.Status}}' "hotel-booking-$service" 2>/dev/null || printf 'unknown')"
    case "$status" in
      healthy)  pass "$service healthy (${waited}s)"; return 0 ;;
      unhealthy) die "$service reported unhealthy. Logs: docker logs hotel-booking-$service" ;;
    esac
    sleep 1; waited=$((waited + 1))
    if [ $((waited % 10)) -eq 0 ]; then
      detail "still waiting for $service (${waited}s, status=$status)"
    fi
  done
  die "$service did not become healthy within ${DOCKER_TIMEOUT_SECONDS}s"
}

start_external_services() {
  needs_docker || return 0
  section "External services"

  local services=""
  [ "$USE_POSTGRES" = true ] && services="$services postgres"
  [ "$USE_REDIS" = true ] && services="$services redis"

  info "Starting:$services"
  compose up -d $services >/dev/null 2>&1 || die "docker compose up failed. Try: compose logs"
  STARTED_CONTAINERS=true

  for service in $services; do
    wait_for_container "$service"
  done
}

# ------------------------------------------------------------------------------------------------
# Build and launch
# ------------------------------------------------------------------------------------------------

build() {
  section "Build"
  cd "$PROJECT_DIR"

  if [ "$RUN_TESTS" = true ]; then
    info "Running the full test suite (this includes the 20-thread concurrency test)"
    if ! "$PROJECT_DIR/mvnw" -B clean package 2>&1 | tee "$RUN_DIR/build.log" | grep -E '^\[INFO\] (Running|Tests run)|^\[ERROR\]' ; then
      true
    fi
    if ! grep -q 'BUILD SUCCESS' "$RUN_DIR/build.log"; then
      printf '\n'
      grep -E '^\[ERROR\]' "$RUN_DIR/build.log" | head -30 || true
      die "Build or tests failed. Full log: $RUN_DIR/build.log"
    fi
    local summary
    summary="$(grep -E 'Tests run:.*Failures' "$RUN_DIR/build.log" | tail -n 1 | sed 's/^\[INFO\] *//')"
    pass "Tests green — $summary"
  else
    info "Compiling (skipping tests; use --test to run them)"
    if ! "$PROJECT_DIR/mvnw" -B -q clean package -DskipTests > "$RUN_DIR/build.log" 2>&1; then
      printf '\n'
      tail -40 "$RUN_DIR/build.log"
      die "Build failed. Full log: $RUN_DIR/build.log"
    fi
    pass "Compiled"
  fi

  # A glob rather than `ls | grep`: Spring Boot's repackage leaves the plain jar as
  # `*.jar.original`, which this pattern already excludes, so there is nothing to filter out.
  JAR_FILE=""
  for candidate in "$PROJECT_DIR"/target/*.jar; do
    [ -f "$candidate" ] && JAR_FILE="$candidate"
  done
  [ -n "$JAR_FILE" ] || die "No jar produced in target/"
  detail "$(basename "$JAR_FILE")"
}

start_server() {
  section "Starting server"

  local profiles=""
  [ "$USE_POSTGRES" = true ] && profiles="postgres"
  if [ "$USE_REDIS" = true ]; then
    [ -n "$profiles" ] && profiles="$profiles,redis" || profiles="redis"
  fi

  local args=""
  args="$args --server.port=$PORT"
  [ "$FORCE_SEED" = true ] && args="$args --hotel-booking.demo-data.enabled=true"

  if [ -n "$profiles" ]; then
    info "Profiles: $profiles"
    args="$args --spring.profiles.active=$profiles"
  else
    info "Profiles: default (H2 in-memory)"
  fi

  # nohup + background, with output to a log the script can quote back on failure. Running in the
  # foreground would mean the verification below could never run.
  nohup java -jar "$JAR_FILE" $args > "$SERVER_LOG" 2>&1 &
  SERVER_PID=$!
  echo "$SERVER_PID" > "$PID_FILE"
  detail "pid $SERVER_PID · log $SERVER_LOG"

  local waited=0 status
  while [ "$waited" -lt "$READY_TIMEOUT_SECONDS" ]; do
    # If the JVM died, stop waiting and show why. Otherwise a startup failure looks like a timeout,
    # and the actual stack trace stays buried in a log nobody opens.
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
      printf '\n'
      tail -40 "$SERVER_LOG"
      SERVER_PID=""
      die "Server exited during startup. Full log: $SERVER_LOG"
    fi

    status="$(curl -sS "$BASE_URL/actuator/health" 2>/dev/null || true)"
    if [ -n "$status" ]; then
      local overall; overall="$(json_get "$status" "status")"
      if [ "$overall" = "UP" ]; then
        pass "Health UP after ${waited}s"
        HEALTH_JSON="$status"
        return 0
      fi
    fi
    sleep 1; waited=$((waited + 1))
    if [ $((waited % 10)) -eq 0 ]; then
      detail "waiting for health (${waited}s)"
    fi
  done

  printf '\n'; tail -40 "$SERVER_LOG"
  die "Server did not report healthy within ${READY_TIMEOUT_SECONDS}s. Log: $SERVER_LOG"
}

# ------------------------------------------------------------------------------------------------
# Dependency verification
# ------------------------------------------------------------------------------------------------

verify_dependencies() {
  section "External service connectivity"

  # Actuator asks each dependency directly, so this is evidence rather than inference: a DOWN db
  # component means the pool could not get a connection, whatever the process status says.
  local db_status; db_status="$(json_get "$HEALTH_JSON" "components.db.status")"
  local db_kind;   db_kind="$(json_get "$HEALTH_JSON" "components.db.details.database")"

  if [ "$db_status" = "UP" ]; then
    pass "Database reachable — $db_kind"
  else
    fail "Database not reachable" "$db_status"
  fi

  if [ "$USE_POSTGRES" = true ]; then
    expect_eq "Database is PostgreSQL (not H2)" "PostgreSQL" "$db_kind"
  fi

  if [ "$USE_REDIS" = true ]; then
    local redis_status; redis_status="$(json_get "$HEALTH_JSON" "components.redis.status")"
    local redis_version; redis_version="$(json_get "$HEALTH_JSON" "components.redis.details.version")"
    if [ "$redis_status" = "UP" ]; then
      pass "Redis reachable — v$redis_version"
    else
      fail "Redis not reachable" "$redis_status"
    fi
  fi

  section "Wiring discovered at startup"

  http GET /api/v1/system/capabilities
  local caps="$HTTP_BODY"
  expect_eq "Capabilities endpoint responds" "200" "$HTTP_STATUS"

  detail "database:   $(json_get "$caps" "database")"
  detail "sweep lock: $(json_get "$caps" "sweepLock")"

  # These counts are the point of registry-based wiring: every gateway, policy and filter is
  # discovered rather than listed, so a count is a direct check that discovery worked.
  expect_eq "3 payment gateways registered" "3" "$(json_len "$caps" "paymentMethods")"
  expect_eq "3 cancellation policies registered" "3" "$(json_len "$caps" "cancellationPolicies")"
  expect_eq "3 search filters registered" "3" "$(json_len "$caps" "searchFilters")"

  if [ "$USE_REDIS" = true ]; then
    case "$(json_get "$caps" "sweepLock")" in
      Redis*) pass "Sweeper using the cluster-safe Redis lock" ;;
      *)      fail "Sweeper should use the Redis lock under --redis" "$(json_get "$caps" "sweepLock")" ;;
    esac
  fi
}

# ------------------------------------------------------------------------------------------------
# End-to-end verification
# ------------------------------------------------------------------------------------------------

verify_flow() {
  section "End-to-end flow"

  local stamp check_in check_out
  stamp="$(date +%s)"
  check_in="$(date -v+30d +%Y-%m-%d 2>/dev/null || date -d '+30 days' +%Y-%m-%d)"
  check_out="$(date -v+33d +%Y-%m-%d 2>/dev/null || date -d '+33 days' +%Y-%m-%d)"
  detail "stay $check_in -> $check_out (3 nights)"

  # --- 1. Onboard -------------------------------------------------------------------------------
  # A two-property owner, so the multi-property path is the one being exercised.
  http POST /api/v1/property-groups "$(cat <<JSON
{
  "groupName": "Runcheck Hotels $stamp",
  "contactEmail": "owner+$stamp@runcheck.example",
  "properties": [
    {
      "name": "Runcheck Grand $stamp",
      "city": "Bengaluru",
      "locality": "Runcheck-$stamp",
      "addressLine": "1 Verification Road",
      "starRating": 5,
      "amenities": ["WIFI", "POOL", "GYM"],
      "cancellationPolicyCode": "FLEXIBLE",
      "roomTypes": [
        { "name": "Deluxe King", "maxOccupancy": 2, "totalRooms": 3, "basePricePerNight": 6000.00 },
        { "name": "Penthouse",   "maxOccupancy": 4, "totalRooms": 1, "basePricePerNight": 25000.00 }
      ]
    },
    {
      "name": "Runcheck Beach $stamp",
      "city": "Goa",
      "locality": "Candolim-$stamp",
      "addressLine": "2 Verification Beach",
      "starRating": 4,
      "amenities": ["WIFI", "BAR"],
      "cancellationPolicyCode": "MODERATE",
      "roomTypes": [
        { "name": "Sea View Twin", "maxOccupancy": 2, "totalRooms": 5, "basePricePerNight": 4000.00 }
      ]
    }
  ]
}
JSON
)"
  expect_eq "Onboard multi-property owner -> 201" "201" "$HTTP_STATUS"
  local group="$HTTP_BODY"
  expect_eq "Reported as a chain, not standalone" "false" "$(json_get "$group" "standalone")"
  expect_eq "Both properties onboarded" "2" "$(json_get "$group" "propertyCount")"

  local locality property_id room_type_id
  locality="Runcheck-$stamp"
  # Properties are name-ordered, so "Runcheck Beach" sorts before "Runcheck Grand". Index by the
  # Bengaluru property explicitly rather than trusting a position.
  if [ "$(json_get "$group" "properties.0.city")" = "bengaluru" ]; then
    property_id="$(json_get "$group" "properties.0.id")"
    room_type_id="$(json_get "$group" "properties.0.roomTypes.0.id")"
  else
    property_id="$(json_get "$group" "properties.1.id")"
    room_type_id="$(json_get "$group" "properties.1.roomTypes.0.id")"
  fi
  [ "$property_id" != "<missing>" ] || die "Could not read the property id from the onboard response"

  # --- 2. Search --------------------------------------------------------------------------------
  local search_body
  search_body="$(cat <<JSON
{
  "city": "Bengaluru",
  "locality": "$locality",
  "checkIn": "$check_in",
  "checkOut": "$check_out",
  "guests": 2,
  "maxNightlyPrice": 10000.00,
  "amenities": ["WIFI", "POOL"],
  "minStarRating": 4
}
JSON
)"
  http POST /api/v1/properties/search "$search_body"
  expect_eq "Search -> 200" "200" "$HTTP_STATUS"
  expect_eq "Finds the new property" "1" "$(json_get "$HTTP_BODY" "resultCount")"

  # The Penthouse costs 25,000/night and the filter capped nightly price at 10,000, so only the
  # Deluxe King should survive — this checks the filter chain actually eliminated something.
  expect_eq "Price filter excluded the Penthouse" "1" \
    "$(json_len "$HTTP_BODY" "results.0.availableRoomTypes")"
  expect_eq "All 3 Deluxe rooms available" "3" \
    "$(json_get "$HTTP_BODY" "results.0.availableRoomTypes.0.roomsAvailable")"

  local quoted_total
  quoted_total="$(json_get "$HTTP_BODY" "results.0.availableRoomTypes.0.totalForStay.amount")"
  detail "quoted for the stay: INR $quoted_total"
  expect_money "3 nights x 6000 quoted correctly" "18000.00" "$quoted_total"

  # --- 3. Book ----------------------------------------------------------------------------------
  http POST /api/v1/bookings "$(cat <<JSON
{
  "propertyId": "$property_id",
  "roomTypeId": "$room_type_id",
  "guestName": "Run Check",
  "guestEmail": "runcheck+$stamp@example.com",
  "checkIn": "$check_in",
  "checkOut": "$check_out",
  "guests": 2,
  "rooms": 1
}
JSON
)"
  expect_eq "Create booking -> 201" "201" "$HTTP_STATUS"
  local booking="$HTTP_BODY"
  local booking_id; booking_id="$(json_get "$booking" "id")"
  expect_eq "Booking starts unpaid" "PENDING_PAYMENT" "$(json_get "$booking" "status")"
  # Search and checkout must agree. A platform that quotes one number and charges another is broken
  # in a way tests rarely catch, so this is asserted explicitly.
  expect_money "Charged price matches the quoted price" "$quoted_total" \
    "$(json_get "$booking" "totalAmount.amount")"

  # --- 4. Inventory held ------------------------------------------------------------------------
  http POST /api/v1/properties/search "$search_body"
  expect_eq "Unpaid hold already removed a room from sale" "2" \
    "$(json_get "$HTTP_BODY" "results.0.availableRoomTypes.0.roomsAvailable")"

  # --- 5. Pay -----------------------------------------------------------------------------------
  local idem_key="runcheck-$stamp"
  http POST "/api/v1/bookings/$booking_id/payments" '{"method":"CARD","payerReference":"runcheck-visa"}' \
    -H "Idempotency-Key: $idem_key"
  expect_eq "Payment -> 201" "201" "$HTTP_STATUS"
  local payment="$HTTP_BODY"
  local payment_id; payment_id="$(json_get "$payment" "paymentId")"
  expect_eq "Payment successful" "SUCCESSFUL" "$(json_get "$payment" "status")"
  expect_eq "Payment outcome confirmed the booking" "CONFIRMED" "$(json_get "$payment" "booking.status")"
  expect_eq "Not flagged as a replay" "false" "$(json_get "$payment" "idempotentReplay")"

  # --- 6. Idempotency ---------------------------------------------------------------------------
  # The marquee check. Same key, same request: the money must not move again.
  http POST "/api/v1/bookings/$booking_id/payments" '{"method":"CARD","payerReference":"runcheck-visa"}' \
    -H "Idempotency-Key: $idem_key"
  expect_eq "Retry with the same key -> 200, not 201" "200" "$HTTP_STATUS"
  expect_eq "Retry announced as a replay" "true" "$(json_get "$HTTP_BODY" "idempotentReplay")"
  expect_eq "Replay returned the original payment id" "$payment_id" "$(json_get "$HTTP_BODY" "paymentId")"

  http GET "/api/v1/bookings/$booking_id/payments"
  expect_eq "Exactly one payment record exists" "1" "$(json_len "$HTTP_BODY" "")"

  # --- 7. Rejections ----------------------------------------------------------------------------
  http POST "/api/v1/bookings/$booking_id/payments" '{"method":"UPI"}' \
    -H "Idempotency-Key: runcheck-second-$stamp"
  expect_eq "Paying a confirmed booking again -> 409" "409" "$HTTP_STATUS"
  expect_eq "  ...with the state-machine error code" "ILLEGAL_STATE_TRANSITION" \
    "$(json_get "$HTTP_BODY" "code")"

  http POST /api/v1/bookings "$(cat <<JSON
{ "propertyId": "$property_id", "roomTypeId": "$room_type_id",
  "guestName": "Bad Dates", "guestEmail": "bad+$stamp@example.com",
  "checkIn": "$check_out", "checkOut": "$check_in", "guests": 2, "rooms": 1 }
JSON
)"
  expect_eq "Inverted dates -> 400" "400" "$HTTP_STATUS"

  http POST /api/v1/bookings "$(cat <<JSON
{ "propertyId": "$property_id", "roomTypeId": "$room_type_id",
  "guestName": "Big Party", "guestEmail": "big+$stamp@example.com",
  "checkIn": "$check_in", "checkOut": "$check_out", "guests": 9, "rooms": 1 }
JSON
)"
  expect_eq "9 guests in one 2-person room -> 400" "400" "$HTTP_STATUS"

  # --- 8. Cancel --------------------------------------------------------------------------------
  http POST "/api/v1/bookings/$booking_id/cancellation" ''
  expect_eq "Cancel -> 200" "200" "$HTTP_STATUS"
  local cancellation="$HTTP_BODY"
  expect_eq "Booking cancelled" "CANCELLED" "$(json_get "$cancellation" "booking.status")"
  expect_eq "FLEXIBLE policy applied" "FLEXIBLE" "$(json_get "$cancellation" "appliedPolicy")"
  # 30 days' notice under FLEXIBLE means a full refund.
  expect_money "Full refund at 30 days' notice" "$quoted_total" \
    "$(json_get "$cancellation" "refundAmount.amount")"
  expect_eq "One room released" "1" "$(json_get "$cancellation" "roomsReleased")"

  http POST "/api/v1/bookings/$booking_id/cancellation" ''
  expect_eq "Cancelling twice -> 409" "409" "$HTTP_STATUS"

  # --- 9. Inventory released --------------------------------------------------------------------
  http POST /api/v1/properties/search "$search_body"
  expect_eq "Released room is bookable again" "3" \
    "$(json_get "$HTTP_BODY" "results.0.availableRoomTypes.0.roomsAvailable")"
}

# ------------------------------------------------------------------------------------------------
# Main
# ------------------------------------------------------------------------------------------------

printf '\n%sHotel Booking Service%s\n' "$BOLD" "$RESET"

# These two exit early, and they live here rather than up with --stop for a plain reason: bash
# resolves function names at call time, so calling doctor() before its definition would just be a
# "command not found".
if [ "$DOCTOR_ONLY" = true ]; then
  doctor
  printf '\n'
  exit 0
fi

if [ "$DRY_RUN" = true ]; then
  plan_and_install_deps install
  printf '\n'
  exit 0
fi

if [ "$AUTO_INSTALL" = true ]; then
  plan_and_install_deps install
fi

preflight
start_external_services
build
start_server

if [ "$VERIFY" = true ]; then
  verify_dependencies
  verify_flow

  section "Result"
  if [ "$CHECKS_FAILED" -eq 0 ]; then
    printf '    %s%sALL CHECKS PASSED%s — %d/%d\n' "$GREEN" "$BOLD" "$RESET" \
      "$CHECKS_PASSED" "$((CHECKS_PASSED + CHECKS_FAILED))"
    info "The booking flow, the idempotency guarantee and inventory release were all just"
    info "exercised against the running service."
  else
    printf '    %s%s%d CHECK(S) FAILED%s — %d passed\n' "$RED" "$BOLD" "$CHECKS_FAILED" "$RESET" \
      "$CHECKS_PASSED"
    info "Server log: $SERVER_LOG"
  fi
fi

if [ "$SMOKE_ONLY" = true ]; then
  printf '\n'
  [ "$CHECKS_FAILED" -eq 0 ] || exit 1
  exit 0
fi

section "Running"
info "API         $BASE_URL/api/v1"
info "Health      $BASE_URL/actuator/health"
info "Wiring      $BASE_URL/api/v1/system/capabilities"
if [ "$USE_POSTGRES" != true ]; then
  info "H2 console  $BASE_URL/h2-console  (jdbc:h2:mem:hotelbooking · sa · no password)"
fi
info "Log         $SERVER_LOG"
printf '\n    %sCtrl-C to stop.%s\n\n' "$DIM" "$RESET"

# Wait on the JVM so Ctrl-C reaches the trap and shuts everything down in order.
wait "$SERVER_PID" 2>/dev/null || true
SERVER_PID=""
