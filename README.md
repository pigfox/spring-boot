# spring-boot

An API-first blockchain ledger service in Java 21 and Spring Boot 3.5. It registers assets,
anchors a keccak256 hash of each asset payload on an EVM chain, signs that hash with a
secp256k1 key held only in the process environment, and publishes the result to Kafka —
behind a stateless, deny-by-default JWT perimeter, with Prometheus metrics and OTLP traces.

Everything below is implemented and tested. 216 tests, 100% line and branch coverage,
enforced by a build gate rather than asserted in prose.

```
Clone         git clone git@github.com:pigfox/spring-boot.git
API           http://localhost:8087
Telemetry     http://localhost:55437/actuator/health
Contract      src/main/resources/openapi/asset-api.yaml
```

## What each hiring requirement maps to

| Requirement | Where it lives | How it is proved |
|---|---|---|
| Java + Spring Boot | [`pom.xml`](pom.xml), [`LedgerNodeApplication.java`](src/main/java/com/pigfox/ledger/LedgerNodeApplication.java) | Java 21, Boot 3.5.16; [`LedgerNodeApplicationTest`](src/test/java/com/pigfox/ledger/LedgerNodeApplicationTest.java) boots the real entry point |
| API-first design | [`asset-api.yaml`](src/main/resources/openapi/asset-api.yaml) written first, then [`AssetController`](src/main/java/com/pigfox/ledger/api/AssetController.java), [`AuthController`](src/main/java/com/pigfox/ledger/api/AuthController.java), [`OpenApiConfig`](src/main/java/com/pigfox/ledger/config/OpenApiConfig.java) | [`OpenApiContractTest`](src/test/java/com/pigfox/ledger/api/OpenApiContractTest.java) diffs the committed spec against the springdoc-generated description on every build — paths, methods, operation ids, schemas, security and response codes |
| Blockchain networks | [`AnchorService`](src/main/java/com/pigfox/ledger/chain/AnchorService.java), [`Web3Config`](src/main/java/com/pigfox/ledger/config/Web3Config.java) | web3j over JSON-RPC; [`AnchorServiceTest`](src/test/java/com/pigfox/ledger/chain/AnchorServiceTest.java) covers anchored, rejected, unreachable and disabled |
| Smart contracts | [`contracts/AssetRegistry.sol`](contracts/AssetRegistry.sol) | `bytes32 → address` registry with an `AssetAnchored` event, first-write-wins; called through `registerAsset` / `signerOf` in `AnchorService` |
| Consensus awareness | [`AnchorService.anchor`](src/main/java/com/pigfox/ledger/chain/AnchorService.java), [`AnchorResult`](src/main/java/com/pigfox/ledger/chain/AnchorResult.java) | EIP-155 chain id in every signed transaction, pending-nonce reads, and submission treated as *proposed* not *final* — see [Degradation](#degradation) |
| Cryptographic signing | [`SignatureService`](src/main/java/com/pigfox/ledger/crypto/SignatureService.java), [`PayloadHasher`](src/main/java/com/pigfox/ledger/crypto/PayloadHasher.java) | secp256k1 sign then recover-and-verify; [`SignatureServiceTest`](src/test/java/com/pigfox/ledger/crypto/SignatureServiceTest.java) (32 tests) covers the round trip, tampering, and malformed input |
| Key management | [`SignatureService`](src/main/java/com/pigfox/ledger/crypto/SignatureService.java), [`LedgerProperties`](src/main/java/com/pigfox/ledger/config/LedgerProperties.java), [`application.yml`](src/main/resources/application.yml) | Key read once at startup from `LEDGER_SIGNING_KEY`, never logged or returned; no in-repo default, so a missing key fails startup |
| Zero-trust | [`SecurityConfig`](src/main/java/com/pigfox/ledger/config/SecurityConfig.java) | Stateless, deny-by-default, JWT required everywhere except the token endpoint and the health probe; [`ZeroTrustSecurityTest`](src/test/java/com/pigfox/ledger/api/ZeroTrustSecurityTest.java) and [`ManagementPortSecurityTest`](src/test/java/com/pigfox/ledger/api/ManagementPortSecurityTest.java) |
| Method-level authorization on writes | [`AssetController`](src/main/java/com/pigfox/ledger/api/AssetController.java) | `@PreAuthorize` per method, independent of the URL rules; a read-only token gets 403 on a write |
| Kafka event-driven | [`KafkaConfig`](src/main/java/com/pigfox/ledger/config/KafkaConfig.java), [`AssetEventPublisher`](src/main/java/com/pigfox/ledger/kafka/AssetEventPublisher.java), [`AssetEventListener`](src/main/java/com/pigfox/ledger/kafka/AssetEventListener.java) | `asset.events`, JSON serde with trusted packages pinned to `com.pigfox.ledger.domain`, idempotent producer, `acks=all`; [`KafkaConfigTest`](src/test/java/com/pigfox/ledger/config/KafkaConfigTest.java) pins each setting |
| Telemetry | [`TelemetryConfig`](src/main/java/com/pigfox/ledger/config/TelemetryConfig.java), [`application.yml`](src/main/resources/application.yml), [`AssetService`](src/main/java/com/pigfox/ledger/service/AssetService.java) | Actuator, Prometheus scrape, OTLP tracing, and the `ledger.assets.registered` domain counter |
| CI/CD | [`.github/workflows/ci.yml`](.github/workflows/ci.yml) | Build, test, 100% coverage gate, secret scan, dependency scan, image build and image scan |
| DevSecOps | [`.github/workflows/ci.yml`](.github/workflows/ci.yml), [`Dockerfile`](Dockerfile) | gitleaks over full history, Trivy filesystem and image scans failing on HIGH/CRITICAL, non-root distro-minimal runtime, image smoke test asserting the node refuses to start unconfigured |

## The API

Five operations, all defined in [`asset-api.yaml`](src/main/resources/openapi/asset-api.yaml)
before any of them were implemented.

| Method | Path | Scope | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/auth/token` | — | Exchange client credentials for a 15-minute HS256 JWT |
| `POST` | `/api/v1/assets` | `ledger.write` | Hash, sign, anchor, store and announce an asset |
| `GET` | `/api/v1/assets` | `ledger.read` | List assets, newest first |
| `GET` | `/api/v1/assets/{id}` | `ledger.read` | Fetch one asset |
| `POST` | `/api/v1/assets/{id}/verify` | `ledger.write` | Recompute the hash, recover the signer, report anchor status |

Interactive docs are at `/swagger-ui.html` and the generated description at `/v3/api-docs`.
Neither is public — they need a bearer token like every other route.

### Registering an asset

```bash
TOKEN=$(curl -sS -X POST http://localhost:8087/api/v1/auth/token \
  -H 'Content-Type: application/json' \
  -d "{\"clientId\":\"$LEDGER_CLIENT_ID\",\"clientSecret\":\"$LEDGER_CLIENT_SECRET\"}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])')

curl -sS -X POST http://localhost:8087/api/v1/assets \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
        "name": "Unit 4B, Harbour Court",
        "assetType": "REAL_ESTATE",
        "owner": "Estate JV",
        "metadata": {"jurisdiction": "GB", "titleNumber": "NGL123456"}
      }'
```

The response carries the `payloadHash`, the 65-byte `signature`, the `signerAddress`, and
`anchorTxHash` when the chain accepted the anchor. `POST /api/v1/assets/{id}/verify` then
recomputes the hash from stored fields and recovers the signer from the signature, so a
tampered record fails verification even though nothing about it was re-signed.

## A note on the Spring Boot version

This was specified as Spring Boot 3.3.x and is built on 3.5.16 instead. The two
requirements collided and one had to give.

Boot 3.3.x is out of OSS support and carries
[CVE-2026-22733](https://avd.aquasec.com/nvd/cve-2026-22733), an **authentication bypass in
Spring Boot Actuator**, patched only in 3.5.12 and 4.0.4 — there is no 3.3.x fix, and 3.3.13
is the end of that line. Since the CI gate is required to fail on HIGH and CRITICAL
findings, staying on 3.3.x meant either a permanently red pipeline or suppressing the
scanner. Suppressing an actuator authentication bypass in a repository whose central claim
is a deny-by-default actuator would be self-defeating.

Reverting is a one-line change to the parent version in [`pom.xml`](pom.xml) if the 3.3.x
pin matters more, though `springdoc` must go back to 2.6.x with it — 2.8.x requires Spring
Framework 6.2.

The same reasoning drove the transitive pins in [`pom.xml`](pom.xml). Tomcat, spring-kafka,
micrometer and BouncyCastle are held ahead of the versions the Boot BOM manages, because
those carry advisories the gate rejects; each is a patch bump inside the same minor line, and
each should be dropped once the BOM catches up.

## Configuration

Every secret comes from the process environment. **There is no `.env` file in this
repository and the application does not read one.** No secret has an in-repo default, so a
missing one is a startup failure naming the property, not a silent misconfiguration.

### Required

| Variable | Meaning |
|---|---|
| `LEDGER_SIGNING_KEY` | secp256k1 private key, 32 bytes hex, `0x` prefix optional |
| `LEDGER_JWT_SECRET` | HS256 signing secret; at least 32 bytes, as HS256 requires |
| `LEDGER_CLIENT_ID` | Client identifier the token endpoint accepts |
| `LEDGER_CLIENT_SECRET` | Client secret the token endpoint accepts |

### Optional

| Variable | Default | Meaning |
|---|---|---|
| `ETH_RPC_URL` | `http://127.0.0.1:8545` | EVM JSON-RPC endpoint |
| `LEDGER_REGISTRY_CONTRACT` | *(empty)* | Deployed `AssetRegistry` address; empty disables anchoring and leaves the rest of the node working |
| `LEDGER_CHAIN_ID` | `31337` | EIP-155 chain id used when signing transactions |
| `LEDGER_CHAIN_ENABLED` | `true` | Set `false` to skip all chain I/O |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:19092` | Kafka bootstrap servers |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318/v1/traces` | OTLP trace collector |

### Ports

| Port | Serves |
|---|---|
| `8087` | The API. Every route needs a bearer token except `POST /api/v1/auth/token` |
| `55437` | Telemetry: `/actuator/health` (public), plus `prometheus`, `metrics` and `info` (token required) |

Telemetry is on its own port so a network policy can expose scrape and probe traffic to the
cluster without exposing it beside the public API. Both ports are reserved for this service,
which is why `docker-compose.yml` binds neither. To collapse them onto 8087, remove
`management.server.port` from [`application.yml`](src/main/resources/application.yml).

## Running it

```bash
# 1. Local dependencies: Kafka in KRaft mode on 19092, anvil on 8545.
docker compose up -d

# 2. A signing key. anvil prints ten funded test accounts and their private keys at
#    startup; the compose file fixes the mnemonic, so they are the same every time.
docker compose logs anvil | sed -n '/Private Keys/,/^$/p'

# 3. Secrets, into this shell only. Never a file.
export LEDGER_SIGNING_KEY=<one of the private keys printed above>
export LEDGER_JWT_SECRET=$(openssl rand -hex 32)
export LEDGER_CLIENT_ID=local-client
export LEDGER_CLIENT_SECRET=$(openssl rand -hex 16)

# 4. Deploy the registry and point the node at it (see below), then run.
./mvnw -B spring-boot:run
```

Those anvil keys are published in Foundry's documentation and funded only on a throwaway
local chain. They are fine for step 3 and must never be used anywhere else, which is also
why none of them is written down in this repository.

Without step 4 the node still runs: anchoring reports `DISABLED`, assets are still hashed,
signed, stored and published, and `anchored` comes back `false`.

### The contract

[`contracts/AssetRegistry.sol`](contracts/AssetRegistry.sol) maps a payload hash to the
address that anchored it, first write wins, and emits `AssetAnchored`. Compile and deploy it
with [Foundry](https://book.getfoundry.sh/) against the anvil container:

```bash
# Compile
forge build --contracts contracts --out contracts/out

# Deploy against the running anvil, reusing the key exported above
forge create contracts/AssetRegistry.sol:AssetRegistry \
  --rpc-url http://localhost:8545 \
  --private-key "$LEDGER_SIGNING_KEY" \
  --broadcast

# Point the node at the address forge prints as "Deployed to:"
export LEDGER_REGISTRY_CONTRACT=0x...
```

Deploying with the same key the node signs with is a local-development convenience. In any
real deployment the deployer and the signer are separate keys with separate custody.

With `solc` instead of Foundry:

```bash
solc --optimize --bin --abi contracts/AssetRegistry.sol -o contracts/out
```

Anchored hashes can then be read back independently of the node:

```bash
cast call "$LEDGER_REGISTRY_CONTRACT" 'signerOf(bytes32)(address)' "$PAYLOAD_HASH" \
  --rpc-url http://localhost:8545
```

## Design notes

### Degradation

Neither the chain nor the broker is on the write path. Both are corroboration layered on top
of a hash that is already signed and stored, so neither may fail a caller's request.

For the chain, a node that is down, a transaction the node refuses, or a contract that
reverts each produce an [`AnchorResult`](src/main/java/com/pigfox/ledger/chain/AnchorResult.java)
rather than an exception. An unavailable chain yields `anchored: false` and an absent
`anchorTxHash` — never a 500. The `UNREACHABLE` / `REJECTED` distinction is kept so an
operator can tell an outage from a bad configuration, and `ledger.assets.anchor.failures`
counts both.

The broker needed two guards, and only one of them is obvious. `KafkaTemplate.send` reports
a delivery failure through the future it returns, but when there is no reachable broker at
all it cannot resolve topic metadata, so it blocks for `max.block.ms` and then throws
*synchronously* on the request thread. Handling only the future leaves that exception to
surface as a 500 for a write that actually succeeded — which is exactly what a smoke test
against the built image caught after a fully green suite, because every integration test
mocked the publisher.
[`AssetEventPublisher`](src/main/java/com/pigfox/ledger/kafka/AssetEventPublisher.java) now
guards both paths, `max.block.ms` is 2s rather than the 60s default so an outage costs a
moment instead of a minute, and
[`BrokerOutageIntegrationTest`](src/test/java/com/pigfox/ledger/api/BrokerOutageIntegrationTest.java)
registers an asset against a closed port with the real publisher in place.

### Canonicalisation

Two nodes must agree byte-for-byte on what was signed, so
[`PayloadHasher`](src/main/java/com/pigfox/ledger/crypto/PayloadHasher.java) length-prefixes
every segment and sorts metadata keys. Without length prefixes, `owner="a:b"` and
`owner="a", assetType="b"` would collide onto the same hash; without sorting, map iteration
order would change it.

### Event delivery

The producer is idempotent with `acks=all`, which removes duplicates caused by its own
retries — not duplicates from redelivery after a consumer restart. At-least-once is the
guarantee that actually applies, so
[`AssetEventListener`](src/main/java/com/pigfox/ledger/kafka/AssetEventListener.java) tracks
event ids and treats a repeat as a no-op, acknowledging offsets only after handling. Trusted
packages are pinned to the domain package: left open, the JSON deserialiser instantiates
whatever type a record's headers name, which turns topic write access into arbitrary class
loading.

### Scanning the artifact, not the manifest

Trivy over the source tree reads `pom.xml`, which names about fifteen dependencies. The jar
contains about 120, because the Boot starters and web3j pull deep transitive trees — and a
CVE in a transitive dependency ships exactly as surely as one in a declared dependency. The
first version of this pipeline scanned only the tree and reported two findings; scanning the
built artifact surfaced twenty-three. CI now scans both, so the gate means "nothing
vulnerable ships" rather than "nothing vulnerable is written down".

### Two independent authorization gates

[`SecurityConfig`](src/main/java/com/pigfox/ledger/config/SecurityConfig.java) ends in
`anyRequest().authenticated()`, so a route added tomorrow is protected without anyone
remembering to protect it. Writes are checked *again* at the method level with
`@PreAuthorize`. The duplication is deliberate: the two gates fail independently, so a
mistyped path matcher is not on its own enough to authorise a write.

## Build and test

```bash
./mvnw -B verify        # tests, JaCoCo report, and the coverage gate
./mvnw -B test          # tests only
```

`verify` fails the build below 100% line or branch coverage — see `jacoco.line.minimum` in
[`pom.xml`](pom.xml). The HTML report lands at `target/site/jacoco/index.html`.

No test needs a running broker or a reachable chain node. Listener startup and admin topic
creation are switched off, the anchor service takes its disabled path, and the one
collaborator that would open a broker connection is mocked. The signing key used by tests is
derived from an integer seed at runtime, so nothing shaped like a private key is committed
anywhere in the repository.

| Suite | Covers |
|---|---|
| [`OpenApiContractTest`](src/test/java/com/pigfox/ledger/api/OpenApiContractTest.java) | The committed spec and the implementation still describe the same API |
| [`SignatureServiceTest`](src/test/java/com/pigfox/ledger/crypto/SignatureServiceTest.java) | Sign/recover round trip, wrong signer, wrong payload, malformed and unrecoverable signatures, bad keys |
| [`AnchorServiceTest`](src/test/java/com/pigfox/ledger/chain/AnchorServiceTest.java) | Every anchor and read outcome, including an unreachable node and a reverting call |
| [`ZeroTrustSecurityTest`](src/test/java/com/pigfox/ledger/api/ZeroTrustSecurityTest.java) | Anonymous callers, wrong-scope tokens, unlisted routes, hardening headers, no session cookie |
| [`ManagementPortSecurityTest`](src/test/java/com/pigfox/ledger/api/ManagementPortSecurityTest.java) | The real two-port topology: health public, everything else on the telemetry port authenticated |
| [`AssetApiIntegrationTest`](src/test/java/com/pigfox/ledger/api/AssetApiIntegrationTest.java) | Token to registration to verification over the real filter chain |
| [`AssetServiceTest`](src/test/java/com/pigfox/ledger/service/AssetServiceTest.java) | Registration ordering, counters, and tamper detection using the real hasher and signer |
| [`BrokerOutageIntegrationTest`](src/test/java/com/pigfox/ledger/api/BrokerOutageIntegrationTest.java) | A write still succeeds, with the real publisher, when no broker is reachable |
| [`LedgerPropertiesValidationTest`](src/test/java/com/pigfox/ledger/config/LedgerPropertiesValidationTest.java) | A missing or too-short secret fails startup |
| [`PrometheusNamingTest`](src/test/java/com/pigfox/ledger/config/PrometheusNamingTest.java) | The metric names that actually reach a scrape, which are not the names the code asks for |

## Container

```bash
docker build -t spring-boot:local .
docker run --rm -p 8087:8087 -p 55437:55437 \
  -e LEDGER_SIGNING_KEY -e LEDGER_JWT_SECRET \
  -e LEDGER_CLIENT_ID -e LEDGER_CLIENT_SECRET \
  spring-boot:local
```

Multi-stage, layered so a release pushes application bytes rather than 120 dependency jars,
running as a non-root user that owns none of its own files. No secret is baked in and none
has a default, so the image refuses to start unconfigured — which CI asserts on every run.

## Licence

Apache-2.0.
