# lra-ha-testsuite

Integration tests that exercise the Narayana LRA coordinator running as a
**4-node high-availability cluster** backed by a shared PostgreSQL object
store. The tests drive the cluster through an in-process Vert.x proxy on
`localhost:8080` so each LRA call lands on a different coordinator in
round-robin order, surfacing cross-coordinator coordination bugs.

The suite depends on two sibling projects living next to it on disk:

```
├── lra/                      # Narayana LRA fork (coordinator library)
├── lra-coordinator-quarkus/  # Quarkus wrapper, produces the native binary
└── lra-ha-testsuite/         # this project — tests + docker-compose stack
```

---

## Prerequisites

- Docker Desktop (or any Docker engine with `docker compose`)
- JDK 21
- Maven 3.9+
- ~6 GB free RAM for the native-image build container

---

## Step 1. Build the LRA coordinator library

The fork in `/lra` is the source of truth for the coordinator code.
Install it into your local Maven repo so the Quarkus wrapper can pick it up:

```bash
./mvnw clean install
```

---

## Step 2. Build the native coordinator binary

```bash
cd ../lra-coordinator-quarkus
./mvnw package -Dnative -Dquarkus.native.container-build=true \
  -Dquarkus.container-image.build=false -DskipTests
```

This runs the GraalVM native-image build inside a Mandrel container and
drops the executable at `target/lra-coordinator-quarkus-1.0.0-SNAPSHOT-runner`.
The build takes 1–2 minutes.

---

## Step 3. Package the binary into a Docker image

```bash
docker build -f src/main/docker/Dockerfile.native -t local-coordinator:latest .
```

The `local-coordinator:latest` tag is what `docker-compose.yml` looks for.

---

## Step 4. Start the HA stack

```bash
cd ../lra-ha-testsuite
docker compose up -d
```

This brings up:

- `postgres`  — shared object store on port 5432
- `coordinator-1` … `coordinator-4` — four coordinator instances on host
  ports 8081–8084. They all talk to the same Postgres database, so any
  coordinator can recover any LRA.

Wait for all four to be healthy before running the tests:

```bash
for p in 8081 8082 8083 8084; do
  until curl -s -f -o /dev/null http://localhost:$p/q/health/ready; do sleep 1; done
  echo "$p UP"
done
```

---

## Step 5. Run the tests

Full suite (≈30–50 min):

```bash
mvn verify
```

Single test class (fast feedback while iterating):

```bash
mvn verify -Dit.test=NestedAfterLraIT -DfailIfNoTests=false
```

The available `*IT` classes live under
`src/test/java/io/narayana/lra/ha/participants/`.

---

## How the test harness wires things up

- `CoordinatorProxyResource` starts a Vert.x proxy on `localhost:8080`
  that round-robins each HTTP call to the next healthy backend. Tests
  point `quarkus.lra.coordinator-url` at this proxy, so consecutive LRA
  operations land on different coordinators. The four backend URLs
  (`http://127.0.0.1:8081-8084/lra-coordinator`) are hard-coded in the
  `BACKENDS` list at the top of
  `src/test/java/io/narayana/lra/ha/proxy/CoordinatorProxyResource.java` —
  there is no configuration property for them. Edit that list if you
  change the ports in `docker-compose.yml` or want to point at a
  different number of coordinators.
- `Participant`, `NestedParticipant`, `AfterLraParticipant`, etc. (in
  `src/main/java/io/narayana/lra/ha/`) are the participants under test;
  they record call counts so the tests can assert what was invoked.
- `TestBase` resets per-coordinator fault-injection state and proxy
  routing before each test.

The 20 s recovery period and 10 s backoff in `docker-compose.yml`
(`-DRecoveryEnvironmentBean.periodicRecoveryPeriod=20`,
`-DRecoveryEnvironmentBean.recoveryBackoffPeriod=10`) are what the
recovery-driven tests rely on; do not lower them without adjusting the
test timeouts.
