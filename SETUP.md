# Setup

Everything needed to build and run this service on macOS, and what each dependency is actually for.

- [The short version](#the-short-version)
- [What gets installed and why](#what-gets-installed-and-why)
- [Installing manually](#installing-manually)
- [The JAVA_HOME problem on macOS](#the-java_home-problem-on-macos)
- [Optional: Postgres and Redis](#optional-postgres-and-redis)
- [Verifying the install](#verifying-the-install)
- [Troubleshooting](#troubleshooting)
- [Uninstalling](#uninstalling)

---

## The short version

```bash
cd hotel-booking-service
./run.sh --doctor         # what do I have, what am I missing?
./run.sh --install-deps   # install whatever is missing, then start
```

If everything is already installed, `./run.sh` on its own is the whole thing.

Three commands worth knowing before anything else:

| Command | Purpose |
|---|---|
| `./run.sh --doctor` | Reports every dependency and exits. Changes nothing. |
| `./run.sh --dry-run` | Prints the exact `brew` commands it *would* run, then exits. |
| `./run.sh --install-deps` | Installs what is missing, then continues into a normal run. |

`--dry-run` exists because a script that installs software should be readable before it is trusted.
Run it first if you would rather see the commands than take them on faith.

---

## What gets installed and why

| Dependency | Required? | Why this project needs it |
|---|---|---|
| **JDK 17+** | Yes | The brief specifies Java 17+. The code uses records, sealed switch expressions and text blocks, so 17 is a real floor, not a preference. `openjdk@21` is installed because it is current and fully backward compatible — the build still targets 17. |
| **Maven** | No — bundled | Not a prerequisite. The repo commits the Maven wrapper, so `./mvnw` downloads the exact Maven this project was built against on first use. Nothing to install, and no version drift between machines. |
| **curl** | Yes | Ships with macOS. `run.sh` uses it to poll the health endpoint and to drive the end-to-end verification. |
| **python3** *or* **jq** | Yes (one of) | `run.sh` makes assertions about JSON responses. python3 ships with the Xcode command line tools, which almost every developer machine already has; `jq` is the fallback. Only installed if **neither** is present. |
| **Docker** | Only for `--postgres` / `--redis` | Runs Postgres 16 and Redis 7 from `docker-compose.yml`. Not needed for the default in-memory run. |
| **Homebrew** | Only for automatic install | The package manager `--install-deps` drives. Not needed if you install things yourself. |

Nothing else. No database to provision, no message broker, no cloud account, no API keys — the payment
gateways are mocked behind our own abstraction, as the brief asks.

---

## Installing manually

If you would rather not let a script install things:

```bash
# 1. Homebrew, if you do not already have it
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

# 2. JDK (Maven is not needed - ./mvnw provides it)
brew install openjdk@21

# 3. Only if you want the real external services
brew install --cask docker

# 4. Only if you have neither python3 nor jq
brew install jq
```

Then read the next section, because on macOS step 2 is not quite finished.

---

## The JAVA_HOME problem on macOS

Homebrew installs `openjdk` **keg-only**, meaning it deliberately does *not* put it on your `PATH`.
So immediately after `brew install openjdk@21`, this still happens:

```bash
$ java -version
openjdk version "11.0.31"     # ...the old one. Or "command not found".
```

The JDK is installed. It is just not the one `java` resolves to. Two ways to deal with it.

### The script handles it automatically

`run.sh` asks macOS itself — `/usr/libexec/java_home`, the OS's registry of installed JDKs — for a
17+ JDK, newest first, and sets `JAVA_HOME` for its own run if `PATH` gives it something older. Maven
and the JVM both honour `JAVA_HOME`, so that is sufficient: no `sudo`, no symlinks into
`/Library/Java`, nothing to undo later. When this happens you will see:

```
✓ Java 21.0.10
  via /usr/libexec/java_home — JAVA_HOME set for this run
```

This is worth knowing about even if you never use the script, because it is why "I installed the JDK
and it still says Java 11" is such a common macOS confusion.

### Making it permanent for your shell

Add to `~/.zshrc`:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
```

Then `source ~/.zshrc`. Now `java` works correctly in any terminal, not just under `run.sh` — and
`./mvnw` picks the same JDK up from `JAVA_HOME`.

---

## Optional: Postgres and Redis

Both are **off by default.** The service runs entirely in memory with no infrastructure, which is the
right default for a reviewer who wants to read code rather than provision databases.

```bash
./run.sh --postgres    # Postgres 16 instead of H2
./run.sh --redis       # Redis 7 for the cluster-safe sweeper lock
./run.sh --all         # both
```

`run.sh` starts the containers itself and — importantly — waits on their Docker **healthchecks**
rather than sleeping. `docker compose up -d` returns as soon as containers are *started*, which is
several seconds before Postgres will accept a connection; a script that proceeded on "up" would fail
intermittently in a way that looks like an application bug.

To manage them yourself:

```bash
docker compose up -d postgres redis
docker compose ps
docker compose logs -f postgres
docker compose down          # stop
docker compose down -v       # stop and delete the Postgres volume
```

Connection details, all overridable by environment variable:

| Variable | Default |
|---|---|
| `POSTGRES_HOST` / `POSTGRES_PORT` | `localhost` / `5432` |
| `POSTGRES_DB` | `hotelbooking` |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | `hotelbooking` / `hotelbooking` |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` |

**Why you might care about the Postgres profile.** The double-booking defence rests on
`SELECT … ORDER BY … FOR UPDATE`. Postgres supports that unambiguously, so running the concurrency
test under this profile is a stronger result than H2 agreeing:

```bash
docker compose up -d postgres
createdb -O hotelbooking hotelbooking_test
./mvnw test -Ppostgres-it -Dtest=ConcurrentBookingIntegrationTest
```

Note that demo data seeding is **off** under the `postgres` profile — unlike H2, that database
persists, so re-seeding every boot would pile up duplicate properties. For a first run against an
empty database use `./run.sh --postgres --seed`.

---

## Verifying the install

```bash
./run.sh --doctor
```

```
==> Environment
    ✓ Java 21.0.10  (/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java)
    ✓ Maven wrapper (./mvnw)
    ✓ curl
    ✓ python3
    ✓ docker
    ✓ Homebrew (/opt/homebrew/bin/brew)
    ✓ All dependencies already present
```

Then prove the service itself works, not merely that it starts:

```bash
./run.sh --test     # full suite first, then start and verify end-to-end
```

`--test` includes `ConcurrentBookingIntegrationTest`, which fires 20 threads at a hotel with one room
and asserts exactly one wins. That takes a few seconds longer and is the single most worthwhile test
in the suite.

For a scripted check that exits non-zero on failure — CI, or just "did I break something":

```bash
./run.sh --smoke
echo $?     # 0 = everything passed
```

---

## Troubleshooting

**`Java 17+ required, found 11.0.31`**
A JDK 17+ is not installed, or is installed but not discoverable. Run `./run.sh --install-deps`, or
`brew install openjdk@21` and then follow [the JAVA_HOME section](#the-java_home-problem-on-macos).

**`./mvnw is missing or not executable`**
Regenerate it with `mvn -N wrapper:wrapper`, or restore the executable bit with `chmod +x ./mvnw`.
A system Maven is not otherwise required.

**`Port 8080 is already in use`**
Either `./run.sh --stop` (which also kills an orphaned process holding the port) or
`./run.sh --port 9090`.

**`Docker is installed but not running`**
Start Docker Desktop and wait for the whale icon to settle, then retry. `docker info` succeeding is
the actual readiness signal.

**The first `./mvnw` run takes several minutes**
Expected. The wrapper downloads Maven itself, then the Spring Boot dependency tree into
`~/.m2/repository`. Subsequent
runs are seconds. If it appears to hang, check network access to `repo.maven.apache.org` — a
corporate proxy or VPN is the usual culprit, and Maven needs `~/.m2/settings.xml` configured for it.

**`Server did not report healthy within 120s`**
The stack trace is in `.run/server.log`. The most common causes are a Postgres container that is not
actually ready and a port collision.

**Tests pass but `./run.sh` fails, or the reverse**
Worth reporting rather than working around — they exercise the same code through different entry
points, so a divergence is information. The end-to-end checks go through real HTTP and the JSON
contract; the tests go through the service layer directly.

---

## Uninstalling

The project itself leaves nothing on your machine outside its own folder:

```bash
./run.sh --stop              # stop the server and any containers
docker compose down -v       # also delete the Postgres volume
rm -rf target .run           # build output and run logs
```

The H2 database is in-memory and disappears when the process exits. If you want the installed tools
gone too:

```bash
brew uninstall openjdk@21
brew uninstall --cask docker
```
