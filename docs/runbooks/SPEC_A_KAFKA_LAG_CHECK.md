# Spec A — Kafka consumer lag check

**Purpose:** Read-only verification that both `portfolio-group` and `insight-group` have
consumed every message on the `market-prices` topic (lag = 0 on every partition) before
executing checkpoint 9.9.

Run this procedure immediately before dispatching the 9.9 apply and again during GO.
Record the full output both times.

**Verified result (2026-08-23T19:05:10Z):**

| Group | Partition | Committed | Log end | Lag |
|---|---:|---:|---:|---:|
| `portfolio-group` | 0 | 24541 | 24541 | 0 |
| `insight-group`   | 0 | 24541 | 24541 | 0 |

---

## Procedure — kafka-consumer-groups CLI

### Image

Use the immutable digest reference to pin the exact image used in the 2026-08-23 verification:

```
confluentinc/cp-kafka@sha256:acbbf674f2ed40e5d0a8ca51beb0f00692c866fc22b5ce06f8cadbdc54cd4436
```

(This digest is both the local image ID and the repository digest — confirmed 2026-08-23.)

### Prerequisites

Export the three Kafka secrets (same values as the GitHub Actions secrets):

```bash
export KAFKA_BOOTSTRAP_SERVERS=<value of KAFKA_BOOTSTRAP_SERVERS secret>
export KAFKA_SASL_USERNAME=<value of KAFKA_SASL_USERNAME secret>
export KAFKA_SASL_PASSWORD=<value of KAFKA_SASL_PASSWORD secret>
```

Locate the JKS truststore:

```bash
export KAFKA_TRUSTSTORE_PATH=common-dto/src/main/resources/kafka-truststore.jks
export KAFKA_TRUSTSTORE_PASSWORD=changeit   # change if the password has been rotated
```

### Run the check

```bash
TMPDIR=$(mktemp -d)
trap 'rm -rf "$TMPDIR"' EXIT

cat > "$TMPDIR/client.properties" <<EOF
security.protocol=SASL_SSL
sasl.mechanism=PLAIN
sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required \
  username='${KAFKA_SASL_USERNAME}' password='${KAFKA_SASL_PASSWORD}';
ssl.truststore.location=/tmp/kafka-truststore.jks
ssl.truststore.password=${KAFKA_TRUSTSTORE_PASSWORD}
EOF

for GROUP in portfolio-group insight-group; do
  echo "=== $GROUP ==="
  docker run --rm \
    -v "$TMPDIR/client.properties:/tmp/client.properties:ro" \
    -v "$(realpath "$KAFKA_TRUSTSTORE_PATH"):/tmp/kafka-truststore.jks:ro" \
    "confluentinc/cp-kafka@sha256:acbbf674f2ed40e5d0a8ca51beb0f00692c866fc22b5ce06f8cadbdc54cd4436" \
    kafka-consumer-groups \
      --bootstrap-server "$KAFKA_BOOTSTRAP_SERVERS" \
      --command-config /tmp/client.properties \
      --group "$GROUP" \
      --describe --offsets \
      --timeout 30000
done
```

The `trap 'rm -rf "$TMPDIR"' EXIT` fires on normal exit, Ctrl-C (SIGINT), and SIGTERM, ensuring
the ephemeral `client.properties` (which contains SASL credentials) is deleted even if the loop
is interrupted.

### Expected output (PASS)

```
=== portfolio-group ===
GROUP           TOPIC          PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
portfolio-group market-prices  0          24541           24541           0

=== insight-group ===
GROUP           TOPIC          PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
insight-group   market-prices  0          24541           24541           0
```

`LOG-END-OFFSET` will be ≥ 24541 if messages have been published since the 2026-08-22 baseline;
`CURRENT-OFFSET` must equal `LOG-END-OFFSET` and `LAG` must be `0` for every row.

### Read-only guarantee

`--describe --offsets` is a read-only Kafka metadata query from the group coordinator.
It does not join any consumer group, does not commit any offset, and does not reset or delete
anything.  See the [Apache Kafka operations guide](https://kafka.apache.org/documentation/#basic_ops_consumer_group)
and [Aiven offset documentation](https://aiven.io/docs/products/kafka/howto/viewing-resetting-offset).

---

## If lag is non-zero (FAIL condition)

- Do **not** dispatch the 9.9 apply.
- Verify the `market-data-refresh-job` fence: `MARKET_DATA_JOB_RUNNER_ENABLED` must be `false`
  and no execution may be running (checkpoint 9.3 gate condition).
- If the job fence is confirmed and lag remains non-zero, the Kafka retention window may have
  elapsed — see tasks.md checkpoint 9.4 waiver condition; a waiver requires counting and surfacing
  the discarded events explicitly on the record.
- Record the exact output (TOPIC, PARTITION, CURRENT-OFFSET, LOG-END-OFFSET, LAG) before any decision.

---

## Recording the result

After a PASS run, record in the relevant checkpoint (tasks.md 9.4 re-run note or the GO step in
`docs/superpowers/plans/2026-08-23-spec-a-checkpoint-9.9-execution.md`):

```
Kafka lag check: <UTC timestamp>
  Image: confluentinc/cp-kafka@sha256:acbbf674f2ed40e5d0a8ca51beb0f00692c866fc22b5ce06f8cadbdc54cd4436
  topic: market-prices  partition: 0
  portfolio-group: current-offset=<N>, log-end=<N>, lag=0
  insight-group:   current-offset=<N>, log-end=<N>, lag=0
  Result: PASS
```
