# Spec A Mongo repair — operator runbook

This runbook covers checkpoint **9.7**, the one-shot `MM.NS` → `M&M.NS`
MongoDB repair. It does not authorize checkpoint 9.7: starting the Job still
requires a separate explicit go.

The procedures are deliberately specific to this repository and its production
resource names. Do not reuse them for a different repair ID or collection.

---

## Resource map

| Thing | Value |
|---|---|
| Resource group | `wealth-azure-prod-rg` |
| ACR | `wealthprodacr` |
| Image repository | `market-data-service` |
| Repair Job | `market-data-repair-job` |
| Repair container | `market-data-repair` |
| Platform Log Analytics workspace | `wealth-prod-la` |
| Mongo database | `portfolio_db` (verified live and via the production connection string's embedded database segment — `application.yml`'s `market_db` is the local-dev-only fallback, used solely when no `SPRING_MONGODB_URI`/`SPRING_DATA_MONGODB_URI` is set; `market_db` does not exist in this Atlas cluster at all) |
| Price collection | `market_prices` |
| Lease collection | `repair_leases` |
| Archive collection | `repair_archive` |
| Repair ID | `mm-ns-repair` |
| Source / destination | `MM.NS` / `M&M.NS` |

The repair's atomic price tuple is:

1. `currentPrice`
2. `quoteCurrency`
3. `updatedAt`
4. `previousReferencePrice`
5. `previousReferenceAt`

Compare and move all five fields together. Never repair only the current price
or only the timestamp.

---

## 1. Build and provision the exact candidate

The repair Job does not exist before the task-7 Terraform is applied, and the
repair code does not exist in any older `market-data-service` image. Provision
it in this order:

1. Rebase and review `feat/supported-asset-mongo-repair` on the current `main`.
2. Merge it and record the full merge SHA as `RepairSha`.
3. While `main` still points at that SHA, dispatch `deploy-azure.yml` with
   `services=market-data-service`. Wait for the scoped deploy to finish.
4. Confirm ACR has exactly one tag named `RepairSha` and record its digest.
5. While the workflow ref still resolves to `RepairSha`, dispatch
   `terraform-azure.yml` with `action=apply`, `use_seed_image=false`, and
   `recreate_market_data_job=false`. This creates the repair Job directly on the
   real image.
6. Do not start the Job during provisioning.

The two workflow dispatches are:

```powershell
gh workflow run deploy-azure.yml --ref main --field services=market-data-service

gh workflow run terraform-azure.yml `
  --ref main `
  --field action=apply `
  --field use_seed_image=false `
  --field recreate_market_data_job=false
```

Before each dispatch, confirm the remote `main` SHA still equals `RepairSha`.
If it has advanced, stop and re-establish one reviewed candidate SHA; do not let
the image workflow and Terraform workflow consume different commits.

The task-7 `market_data_repair_job_use_seed_image` variable is a bootstrap
escape hatch, not the normal path. It defaults to `false` and is not wired to a
repair-Job rollout in `deploy-azure.yml`. Creating the Job on the seed image
first would therefore require a separate manual image update; Terraform also
ignores later image drift. Use the seed only if direct creation on the existing
ACR artifact fails.

The scoped deploy also rolls the ordinary market-data app and the suspended
refresh Job to the same tag. That is expected. Re-read the refresh fence after
the deploy; do not infer that its disabled environment value survived.

### Artifact checks

PowerShell:

```powershell
$RepairSha = '<full merge SHA>'

az acr repository show-tags `
  --name wealthprodacr `
  --repository market-data-service `
  --detail `
  --query "[?name=='$RepairSha'].{tag:name,digest:digest,updated:lastUpdateTime}" `
  --output table

$RepairDigest = az acr repository show-tags `
  --name wealthprodacr `
  --repository market-data-service `
  --detail `
  --query "[?name=='$RepairSha'].digest | [0]" `
  --output tsv

$RepairImage = "wealthprodacr.azurecr.io/market-data-service@$RepairDigest"
```

Stop if the tag is absent, duplicated in the output, or its digest changes
between checks.

### Digest pin for the one-shot execution

The commit-SHA tag is still mutable in ACR. Do not add a generalized digest
deployment mode to `deploy-azure.yml` solely for this one-shot Job. After
Terraform has created the dormant Job, pin only this Job to the recorded digest:

```powershell
az containerapp job update `
  --name market-data-repair-job `
  --resource-group wealth-azure-prod-rg `
  --container-name market-data-repair `
  --image $RepairImage
```

This is a pre-trigger configuration change, not an execution. If the installed
CLI rejects the digest reference, stop; do not silently substitute `latest`.
For this portfolio environment, a recorded SHA tag plus an unchanged ACR digest
immediately before the trigger is the acceptable fallback.

Read the configured value back:

```powershell
az containerapp job show `
  --name market-data-repair-job `
  --resource-group wealth-azure-prod-rg `
  --query "properties.template.containers[?name=='market-data-repair'].image | [0]" `
  --output tsv
```

It must equal `$RepairImage` (or the explicitly accepted SHA-tag fallback).
Because Terraform ignores the Job image field, a later apply must not undo this
pin.

---

## 2. Pre-trigger gate

All of the following must be true immediately before the separate go decision:

- checkpoint 9.6 remains verified;
- ingress remains closed;
- `market-data-refresh-job` still has
  `MARKET_DATA_JOB_RUNNER_ENABLED=false`;
- no refresh or repair execution is `Running`;
- the repair Job is `Manual`, with parallelism `1`, completion count `1`, retry
  limit `0`, and timeout `300` seconds;
- the repair container has `MARKET_DATA_REPAIR_ENABLED=true`;
- `MARKET_DATA_JOB_RUNNER_ENABLED` is absent from the repair container;
- the Job image matches the recorded candidate;
- the accepted `market_prices` export and its SHA-256 checksum are available;
- live preflight still shows source `MM.NS`, no destination `M&M.NS`, no lease,
  and no archive records for `mm-ns-repair`.

Use `az containerapp job show --output json` for the full Job template. Use
`az containerapp job execution list` for execution state. Do not rely on the
portal summary alone.

---

## 3. Start once and observe

Only after the explicit checkpoint go:

```powershell
az containerapp job start `
  --name market-data-repair-job `
  --resource-group wealth-azure-prod-rg `
  --output json
```

Record the returned execution name. Poll, but do not start a second execution:

```powershell
az containerapp job execution list `
  --name market-data-repair-job `
  --resource-group wealth-azure-prod-rg `
  --query "[].{name:name,status:properties.status,start:properties.startTime,end:properties.endTime,image:properties.template.containers[0].image}" `
  --output table
```

In the `wealth-prod-la` Logs blade, substitute the execution name:

```kusto
let ExecutionName = "market-data-repair-job-<execution suffix>";
ContainerAppConsoleLogs_CL
| where ContainerJobName_s == "market-data-repair-job"
| where ContainerGroupName_s startswith ExecutionName
| project TimeGenerated, ContainerGroupName_s, ContainerImage_s, Log_s
| order by TimeGenerated asc
```

The application emits these exact messages:

```text
MarketDataRepairJobRunner: starting MM.NS Mongo repair
MarketDataRepairJobRunner: finished outcome=<OUTCOME> generation=<N> exit=<CODE>
```

An uncaught failure instead emits:

```text
MarketDataRepairJobRunner: repair failed
```

`FAILED_CONFLICT` is observed when the execution is terminal/non-successful and
the finish line contains `outcome=FAILED_CONFLICT ... exit=1`. The execution
status alone is insufficient: always capture the finish line and generation.

---

## 4. Success evidence

Checkpoint 9.7 succeeds only when all of these agree:

- the execution status is `Succeeded`;
- the finish log is `outcome=COMPLETE ... exit=0` (or
  `outcome=ALREADY_COMPLETE ... exit=0` for a verified repeat inspection);
- `repair_leases/{_id: "mm-ns-repair"}` is durably `state: "COMPLETE"`;
- `market_prices/MM.NS` is absent;
- `market_prices/M&M.NS` exists with the expected five-field source tuple;
- `repairGeneration` is absent from the destination;
- exactly one applicable `repair_archive` record is `COMMITTED`, its payload
  describes the discarded source/destination state, and no applicable record is
  left `PENDING`;
- the execution's recorded image is the approved candidate image.

Do not re-enable ingress or the refresh writer here. Those are later checkpoints.

---

## 5. `FAILED_CONFLICT`: immediate response

`FAILED_CONFLICT` is terminal by design. It is not limited to the equal-time
collision policy. The implementation also uses it for a non-corroborated
`PENDING` archive, an inconsistent `COMMITTED` archive, and certain failed
compare-and-set mutations.

On any conflict:

1. Do not start the Job again.
2. Confirm the failed execution is no longer `Running`.
3. Keep ingress closed and the refresh writer disabled.
4. Do not delete the lease, decrement its generation, or unset either document's
   `repairGeneration`.
5. Export the complete current documents below, in canonical Extended JSON:
   - `market_prices/MM.NS`;
   - `market_prices/M&M.NS`;
   - `repair_leases/mm-ns-repair`;
   - every `repair_archive` row with `repairId: "mm-ns-repair"`.
6. Record the execution name, image, start/end times, full logs, terminal
   generation, and checksums of the exports.

The Mongo evidence comes directly from the repair database. In `mongosh`:

```javascript
const evidence = {
  capturedAt: new Date(),
  prices: db.getSiblingDB("portfolio_db").market_prices
    .find({ _id: { $in: ["MM.NS", "M&M.NS"] } })
    .sort({ _id: 1 })
    .toArray(),
  lease: db.getSiblingDB("portfolio_db").repair_leases
    .find({ _id: "mm-ns-repair" })
    .toArray(),
  archives: db.getSiblingDB("portfolio_db").repair_archive
    .find({ repairId: "mm-ns-repair" })
    .sort({ generation: 1 })
    .toArray()
};

print(EJSON.stringify(evidence, null, 2, { relaxed: false }));
```

Save that output without editing it and checksum the saved file. Do not paste a
Mongo URI, secret value, or unredacted connection string into the checkpoint
record.

The resolution decision needs the two complete five-field tuples, not only the
two prices. Determine which existing tuple is authoritative from its upstream
observation/provider evidence and timestamp provenance. Record the chosen key,
evidence, operator, reviewer, time, and rationale. If neither existing tuple is
defensible, stop for an engineering repair; do not invent a third tuple live.

### Classify before touching Mongo

The narrowly supported manual procedure in section 6 applies only when all of
these are true:

- both `MM.NS` and `M&M.NS` exist;
- both hold `repairGeneration: N`, matching the failed lease generation;
- the lease is `FAILED_CONFLICT` at generation `N`;
- both documents have the same non-null `updatedAt` but different five-field
  tuples;
- there is no `PENDING` or `COMMITTED` archive row for generation `N`;
- the operator chooses one of the two existing tuples unchanged.

Any other shape is an archive-reconciliation or CAS incident. Do not use the
section-6 transaction. Preserve the terminal state and prepare a case-specific
repair reviewed against `MongoMmNsRepairService.reconcile`, the accepted export,
and the archive payload. A blind lease reset can delete an uncorroborated source.

---

## 6. Equal-time conflict: CAS-protected manual resolution

This is a break-glass procedure. Have a second person compare the captured EJSON
and review the transaction before running it.

The transaction does four things atomically:

1. proves the lease, generation, fences, and both tuples still equal the reviewed
   incident snapshot;
2. writes a `SUPERSEDED` operator-resolution archive containing both originals;
3. changes only the losing document's five-field tuple to the selected existing
   winner, leaving both document fences at generation `N`;
4. converts the terminal lease into an expired `CLAIMED` lease without changing
   generation, so the next Job claim advances it to `N + 1`.

Do **not** manually unset a document fence. The next generation advances both
fences and the successful Job clears the destination fence after verification.

In `mongosh`, first paste the exact EJSON values captured in section 5 into
`ExpectedSource`, `ExpectedDestination`, and `ExpectedLease`. Set `WinnerId` to
exactly `MM.NS` or `M&M.NS`.

```javascript
const RepairId = "mm-ns-repair";
const SourceId = "MM.NS";
const DestinationId = "M&M.NS";
const WinnerId = "MM.NS"; // change to M&M.NS only after the recorded review

const ExpectedSource = EJSON.deserialize(/* captured canonical EJSON document */);
const ExpectedDestination = EJSON.deserialize(/* captured canonical EJSON document */);
const ExpectedLease = EJSON.deserialize(/* captured canonical EJSON document */);
const crypto = require("crypto");

function must(condition, message) {
  if (!condition) throw new Error(message);
}

function tuple(document) {
  return {
    currentPrice: document.currentPrice,
    quoteCurrency: document.quoteCurrency,
    updatedAt: document.updatedAt,
    previousReferencePrice: document.previousReferencePrice,
    previousReferenceAt: document.previousReferenceAt
  };
}

function guardedDocument(document) {
  return Object.assign(
    { _id: document._id, repairGeneration: document.repairGeneration },
    tuple(document)
  );
}

const Generation = ExpectedLease.generation;
must(ExpectedLease._id === RepairId, "wrong lease snapshot");
must(ExpectedLease.state === "FAILED_CONFLICT", "lease snapshot is not terminal conflict");
must(ExpectedSource._id === SourceId, "wrong source snapshot");
must(ExpectedDestination._id === DestinationId, "wrong destination snapshot");
must(String(ExpectedSource.repairGeneration) === String(Generation), "source fence mismatch");
must(String(ExpectedDestination.repairGeneration) === String(Generation), "destination fence mismatch");
must(ExpectedSource.updatedAt !== null, "the supported case requires a known timestamp");
must(
  EJSON.stringify(ExpectedSource.updatedAt) === EJSON.stringify(ExpectedDestination.updatedAt),
  "timestamps are not equal"
);
must(
  EJSON.stringify(tuple(ExpectedSource)) !== EJSON.stringify(tuple(ExpectedDestination)),
  "tuples are already identical"
);
must(WinnerId === SourceId || WinnerId === DestinationId, "winner must be an existing tuple");

const session = db.getMongo().startSession();
const repairDb = session.getDatabase("portfolio_db");

session.withTransaction(() => {
  const liveLease = repairDb.repair_leases.findOne({
    _id: RepairId,
    state: "FAILED_CONFLICT",
    generation: Generation,
    owner: ExpectedLease.owner
  });
  must(liveLease !== null, "lease changed after review");

  const liveSource = repairDb.market_prices.findOne(guardedDocument(ExpectedSource));
  const liveDestination = repairDb.market_prices.findOne(guardedDocument(ExpectedDestination));
  must(liveSource !== null, "source changed after review");
  must(liveDestination !== null, "destination changed after review");

  const existingGenerationArchive = repairDb.repair_archive.findOne({
    repairId: RepairId,
    generation: Generation,
    sourceCollection: "market_prices",
    sourceId: SourceId
  });
  must(existingGenerationArchive === null, "archive shape is not the supported equal-time case");

  const chosenTuple = WinnerId === SourceId ? tuple(ExpectedSource) : tuple(ExpectedDestination);
  const losingSnapshot = WinnerId === SourceId ? ExpectedDestination : ExpectedSource;
  const archivePayload = {
    source: tuple(ExpectedSource),
    destinationBefore: tuple(ExpectedDestination),
    intendedDestination: chosenTuple
  };
  const archivePayloadCanonicalEjson = EJSON.stringify(
    archivePayload,
    null,
    0,
    { relaxed: false }
  );

  repairDb.repair_archive.insertOne({
    repairId: RepairId,
    generation: Generation,
    sourceCollection: "market_prices",
    sourceId: SourceId,
    payload: archivePayload,
    payloadHash: crypto.createHash("sha256").update(archivePayloadCanonicalEjson).digest("hex"),
    decision: WinnerId === SourceId
      ? "OPERATOR_RESOLVED_EQUAL_TIME_SOURCE"
      : "OPERATOR_RESOLVED_EQUAL_TIME_DESTINATION",
    status: "SUPERSEDED",
    operatorResolution: {
      resolvedAt: new Date(),
      winnerId: WinnerId,
      failedGeneration: Generation,
      payloadHashEncoding: "canonical Extended JSON, relaxed=false",
      note: "Full evidence, checksums, operator, reviewer, and rationale are in the checkpoint record"
    }
  });

  const aligned = repairDb.market_prices.updateOne(
    guardedDocument(losingSnapshot),
    { $set: chosenTuple }
  );
  must(aligned.matchedCount === 1, "losing document CAS failed");

  const cleared = repairDb.repair_leases.updateOne(
    {
      _id: RepairId,
      state: "FAILED_CONFLICT",
      generation: Generation,
      owner: ExpectedLease.owner
    },
    {
      $set: {
        state: "CLAIMED",
        expiresAt: new Date(0),
        operatorResolution: {
          resolvedAt: new Date(),
          winnerId: WinnerId,
          failedGeneration: Generation
        }
      },
      $unset: { owner: "" }
    }
  );
  must(cleared.matchedCount === 1, "lease CAS failed");
});

session.endSession();
```

If any assertion or write fails, the transaction must abort. Re-export all four
evidence sets and restart the review; do not weaken the predicates.

After commit, verify before starting anything:

- both price documents still exist;
- their five-field tuples are now identical to the recorded winner;
- both still hold `repairGeneration: N`;
- the lease remains generation `N`, is `CLAIMED`, and is expired;
- the generation-`N` operator archive is `SUPERSEDED` and contains both original
  tuples.

With a separate retry go, start exactly one new repair execution. It must claim
generation `N + 1`, classify the identical documents as
`COLLAPSE_IDENTICAL`, archive and delete the source, verify the destination,
clear its fence, and finish `COMPLETE`/exit `0`. Apply all success checks in
section 4, and retain both the generation-`N` operator archive and the
generation-`N + 1` committed archive as the audit trail.
