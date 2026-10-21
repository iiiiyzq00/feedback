# 40-table consolidation runbook

## Recovery point

Pre-change recovery snapshot:

`/Users/yang/projects/backups/feedback-platform-pre-consolidation-20260804-180427`

Contents:

- `feedback-platform-code.tar.gz`: complete application source snapshot.
- `feedback_platform_full.sql`: consistent MySQL dump created with `--single-transaction`, routines, triggers, events and binary-safe output.
- `flyway-history.tsv`: applied migration history and checksums.
- `table-inventory.tsv`: pre-change table inventory and storage estimates.
- `backend-status.txt`: service state before consolidation.
- `SHA256SUMS`: integrity hashes for every snapshot artifact.

The original tables must not be dropped until dual-write comparison, read cutover,
functional regression, performance regression and restore rehearsal have all passed.

## Target physical model

The target contains the original 38 compact business tables plus three justified
isolation tables (41 physical business tables in total, still within the agreed
"around 40" boundary):

1. `t_authorized_contact_vault`, retained for encrypted sensitive-data isolation.
2. `t_http_audit_queue`, retained for mutable lease/retry queue semantics.
3. `t_import_upload_chunk`, retained as the transient encrypted large-object cold
   store so chunk upload does not duplicate payloads into a hot shared table.

All other post-baseline tables are consolidated into existing compact tables by domain:

- Configuration, policy, catalog and version records -> `t_system_config`.
- Durable tasks, orchestration steps and test runs -> `t_ai_task`.
- Immutable events, decisions, receipts and evidence -> `t_system_audit_log`.
- Alert quality snapshots and time buckets -> `t_alert_occurrence`.
- Import quality and normalization history -> `t_import_batch` / `t_import_row`.
- SDK transport telemetry -> `t_sdk_ingest` facts where an ingest envelope exists,
  otherwise a typed immutable event.

## Migration gates

1. Add compatibility columns and access abstractions without removing old tables.
2. Backfill with deterministic source identifiers and row-count/hash reconciliation.
3. Dual-write old and compact models; compare continuously.
4. Switch reads one domain at a time behind a configuration flag.
5. Require all backend tests, verification scripts and API contract checks to pass.
6. Require P95 latency, task throughput, lock waits and scanned rows not to regress.
7. Stop legacy writes and retain legacy tables for an observation period.
8. Drop legacy tables only in a final, separately approved Flyway migration.

## Rollback

Before the final drop phase, rollback is configuration-only: switch reads back to the
legacy repositories and stop compact-model dual writes. After the final drop phase,
restore the code archive and database dump into an empty database, verify Flyway
history checksums, then redirect the application after validation.

## Domain progress

| Domain | Compact target | Migration | State |
|---|---|---|---|
| Compatibility foundation | Core compact tables | V121 | Applied |
| Import quality snapshots | `t_import_batch` | V122/V123 | Read switched, shadow dual-write active, reconciliation passed |
| Field conversion history | `t_system_audit_log` | V122/V123 | Read switched, shadow dual-write active, reconciliation passed |
| Metric definition catalog | `t_system_config` | V124 | Read switched, reconciliation passed |
| Dynamic configuration catalog | `t_system_config` | V124 | Read switched, reconciliation passed |
| Report idempotency keys | `t_ai_task` | V125/V126 | Shadow dual-write/delete active, reconciliation passed |
| Report generation tasks | `t_ai_task` | V125/V126 | Read switched, shadow lifecycle writes active, six-report verification passed |
| Report schedule runs | `t_ai_task` | V125/V126 | Shadow dual-write/delete active |
| RCA orchestration tasks and steps | `t_ai_task` | V127 | Status read switched, shadow lifecycle writes active, reconciliation passed |
| Alert hourly baselines and rule-input quality | `t_alert_occurrence` | V128 | Strongly typed read model and shadow synchronization added |
| Alert and incident lifecycle evidence | `t_system_audit_log` | V128 | Timeline read switched, append-only shadow synchronization added |
| Notification delivery attempts | `t_alert_occurrence` | V129 | Read switched, typed indexes and shadow synchronization added, reconciliation passed |
| Delivery evidence and provider receipts | `t_system_audit_log` | V129 | Read switched, callback idempotency preserved, shadow synchronization passed |
| In-app read receipts | `t_system_audit_log` | V129 | Read switched, per-user uniqueness and unread lookup index preserved |
| Alert rule test runs | `t_ai_task` | V130 | Read switched, snapshot lifecycle and shadow synchronization added |
| Alert isolated drills | `t_ai_task` | V130 | Read switched, parent test-run relationship preserved |
| Alert acceptance assessments | `t_ai_task` | V130 | Read switched, independent approval state and evidence hash preserved |
| Alert data contracts and rule dependencies | `t_system_config` | V131 | Read switched for governance gates, expiry and approval metadata preserved |
| Alert governance rule catalog | `t_system_config` | V131 | Runtime activation read switched, implementation evidence preserved |
| Notification policies and provider acceptance | `t_system_config` | V131 | Provider gate/list reads switched, idempotency and capability evidence preserved |
| Team membership and on-call schedules | `t_system_config` | V132 | Typed team/user/time columns, conflict indexes and responsibility-chain reads switched |
| Data freshness current state | `t_system_config` | V133 | Scheduler shadow synchronization active, status and threshold metadata preserved |
| Data freshness history | `t_alert_occurrence` | V133 | Typed time-series facts and channel/time indexes added, reconciliation passed |
| Data processing versions | `t_system_config` | V133 | Scope, lifecycle, effective time and content hash preserved |
| Incorrect-dedup reviews and idempotency conflicts | `t_alert_occurrence` | V134 | Typed case status, resolution and source-row uniqueness preserved |
| Security signals | `t_alert_occurrence` | V134 | Typed risk/evidence/status indexes and conclusion lifecycle preserved |
| External security receipts | `t_system_audit_log` | V134 | Provider-event idempotency and signed evidence metadata preserved |
| SDK request telemetry | `t_alert_occurrence` | V135 | Read switched, typed transport fields retained, immutable insert/delete shadow synchronization and reconciliation passed |
| Application version releases | `t_system_config` | V135 | Read switched, release/control versions and effective lifecycle retained, insert/update/delete shadow synchronization passed |
| Import template catalog | `t_system_config` | V136/V137 | Read switched, versioned header/mapping JSON retained, insert/update/delete shadow synchronization and collation-safe key matching passed |
| Import attachment tasks | `t_import_row` | V138 | Cold encrypted URL fields reused, typed task indexes added, quality/reconciliation reads switched and lifecycle shadow synchronization passed |
| Import upload sessions | `t_import_row` | V139 | Session reads switched, expiry/status indexes and legacy synchronization added; encrypted chunks remain in the dedicated cold table |
| Knowledge usage events | `t_system_audit_log` | V140 | Adoption/effectiveness and entity-closure reads switched, business idempotency and incident correlation preserved |
| Attachment one-time access grants | `t_authorized_contact_vault` | V142 | Hashed-token lookup switched, atomic consume synchronization and contact/grant shape isolation passed |
| Authorized contact access audits | `t_system_audit_log` | V143 | Canonical security audit trigger added, duplicate application audit removed, plaintext-exclusion and append-only behavior passed |
| Masking SLA and NER allowlist | `t_system_config` | V144 | Read switched, typed queue/term fields and lifecycle shadow synchronization passed |
| Masking releases, datasets and runs | `t_system_config` | V145/V146 | Full evidence-gate read switch, lifecycle shadow synchronization and timeline indexes passed |
| Masking review/evaluation/rule-change audit | `t_system_audit_log` | V147 | Append-only insert synchronization, privacy scan and real import delta reconciliation passed |
| Report AI circuit health | `t_system_config` | V148 | Unique-key lock reads switched, mutable circuit lifecycle synchronization and six-report verification passed |
| Alert escalation chains and targets | `t_alert_notification` | V149-V151 | Runtime reads switched, strong lifecycle/order indexes and real on-call boundary verification passed |
| Cluster review decisions | `t_alert_occurrence` | V152 | Volume/overturn reads switched, immutable evidence synchronization and real governance verification passed |
| Audit action catalog | `t_system_config` | V153 | Static catalog consolidated, full lifecycle synchronization and reconciliation passed |

Legacy shadow tables remain present until the final retirement migration. Three
orphan `t_import_quality_snapshot` records reference deleted batches (33, 34 and
35); they are intentionally not copied into `t_import_batch` and are excluded from
the valid-parent reconciliation set.

## V135 verification record

- Flyway V135 applied successfully; the backend is healthy on port 18080.
- Full backend regression passed (`409` tests, `13` skipped).
- SDK telemetry reconciled `20 -> 20`; field differences and compact-model
  orphans are both zero. The release source currently contains no production
  records, so both source and compact counts are zero.
- Transactional shadow tests passed for SDK insert/delete and application release
  insert/update/delete. The transaction was rolled back and left no sample rows.
- SDK time-window list and count queries use `idx_occurrence_type_time`; the
  result-status filter remains a residual predicate after the indexed type/time
  range and does not introduce a full-table scan.
- `verify-topic03-sdk-health.sh` passed its isolated six-result transport
  reconciliation and detail API checks.
- `verify-day10-p07-performance.sh` could not be repeated against the existing
  fixture because prior runs had already consumed the required PENDING review
  rows. It failed while constructing the empty conflict sample, before issuing
  the measured batch operations; this is a fixture precondition, not a V135
  functional failure. Re-seed the isolated D10P07 fixture before the final
  performance gate.

## V136/V137 verification record

- Flyway V136 backfilled all `11` import templates into `t_system_config`; source
  and compact counts match, field differences and compact orphans are zero.
- V137 replaces only the V136 update/delete triggers to make their key comparison
  explicit `utf8mb4_0900_as_cs`. This was required because a real publish-style
  update exposed the connection's `utf8mb4_0900_ai_ci` collation boundary. Applied
  Flyway history was not rewritten.
- Transactional publish simulation passed for deactivating the current template,
  inserting and updating a new active version, and deleting it. The transaction
  was rolled back; each channel still has exactly one active source and compact
  record and no sample rows remain.
- Active-template lookup uses `idx_config_team_member` and examines one candidate
  for the current data distribution.
- Full backend regression passed after the read cutover. Authenticated API
  verification returned four active channel templates, and the JIRA CSV download
  retained its UTF-8 BOM and Chinese headers without mojibake.
- Chunk-upload sessions and chunks were intentionally not included in V136/V137.
  Their encrypted binary payloads require a cold-payload design; placing up to
  5 MB chunks in `t_ai_task` would enlarge a hot shared table and violate the
  no-performance-regression gate.

## V138 verification record

- Import attachment tasks now use `t_import_row(record_type='ATTACHMENT_TASK')` as
  their compact model. Encrypted source URLs reuse the existing import-domain
  `raw_*` cold columns; attachment identity, URL hash/domain, expected integrity,
  status and retry fields remain strongly typed.
- The source table was empty at migration time (`0 -> 0`). A transaction sample
  bound to a real import row verified encrypted insert, lifecycle update with
  ciphertext destruction, and delete synchronization. It was rolled back with no
  source or compact residue.
- Batch metrics use `idx_batch_record_status`, per-row reconciliation uses
  `uk_import_attachment_reference`, and worker lookup uses
  `idx_import_attachment_worker`; no JSON scan was introduced.
- Full backend regression, Topic06 main-chain reconciliation, Day5 import
  governance, and Topic04 large-file concurrency/privacy verification passed.
  Topic04 confirmed 1,201 raw rows were destroyed, no raw phone value remained,
  masking was retained, authorization boundaries returned 403 as expected, and
  the isolated admin rollback completed.
- Operational JPA writes and attachment upload transactions remain on the legacy
  table during the observation window. Quality metrics and reconciliation exports
  read the compact task rows. The legacy table has not been dropped.
- Upload sessions and encrypted chunks were deferred from V138 and handled by the
  bounded transient cold-payload design recorded in V139 below.

## V139 verification record

- Upload-session metadata now uses `t_import_row(record_type='UPLOAD_SESSION')` as
  the compact read model. `batch_id` is nullable only for this staging record type;
  a check constraint still requires a batch owner for every other import row.
- `t_import_upload_chunk` remains the single encrypted payload copy. Its new
  `compact_session_id` associates each chunk with the compact session and is
  populated synchronously on insert. This avoids nearly doubling database writes
  for chunks up to 5 MB.
- Migration-time source, compact and chunk counts were all zero. Field-difference,
  orphan-session and unlinked-chunk reconciliation are zero.
- A real authenticated one-chunk upload passed in approximately 0.052 seconds:
  source/compact sessions reconciled `1 -> 1`, the chunk linked to the compact
  session, and status reads returned the complete chunk set.
- A second real upload exercised assembly and import creation. Completion took
  approximately 0.206 seconds, both session models reached `COMPLETED`, encrypted
  chunks were deleted, and an import batch was created. All isolated sessions,
  chunks, batch rows, users and temporary files were then removed with zero
  residue.
- Session lookup uses the unique `uk_import_upload_file`; chunk enumeration uses
  `idx_import_chunk_compact_session`. Expiry scanning has the dedicated
  `idx_import_upload_expiry` index (the optimizer may choose another record-type
  prefix index while the staging set is empty).
- Full backend regression passed before applying the migration, and the two real
  API workflows passed after application. The backend remains healthy on port
  18080. Neither legacy upload sessions nor the cold chunk table has been dropped.

## V140 verification record

- Immutable knowledge SHOWN/ADOPTED/EFFECTIVE/INEFFECTIVE events are represented
  by `KNOWLEDGE_USAGE_*` business audit operations. `target_id` identifies the
  knowledge item, `correlation_id` identifies the incident, and the unique
  operation/idempotency key preserves `(knowledge, incident, event type)`.
- Migration-time source and compact counts were both zero. Every extant source
  event must have an exact compact match; compact audit records may intentionally
  outlive deleted fixture/source events because the audit table is append-only.
- Knowledge adoption prerequisites, effectiveness counters, last-used time and
  incident entity-closure checks now read the compact audit model. The previous
  extra application audit write was removed so one business event produces one
  canonical compact record.
- Real Topic07 verification initially exposed an `as_cs`/`ai_ci` comparison at
  numeric-to-string join boundaries. All generated knowledge and incident keys
  now explicitly convert to `utf8mb4_0900_as_cs`; applied V140 history was not
  rewritten.
- Full backend regression passed after the collation correction. Topic07 then
  passed blocked-before-evidence, premature-close rejection, atomic rollback,
  READY transition and final feedback/cluster/RCA/knowledge/incident closure.
  V141 subsequently removed the invalid V140 legacy-delete trigger after Topic07
  cleanup exposed the database-level append-only guard. Legacy fixtures are
  deleted, while their compact business audit remains as durable verification
  evidence by design; applied V140 history was not rewritten.
- Attachment access grants are deliberately excluded from V140. They are mutable,
  one-time token records and must be consolidated into the authorization vault,
  not into the append-only audit stream.

## V142 verification record

- One-time attachment grants now use `t_authorized_contact_vault` with
  `contact_type='ATTACHMENT_GRANT'`. The existing `fingerprint` column stores only
  the SHA-256 token hash; attachment, source, user, reason, expiry, consumption and
  revocation remain strongly typed.
- Vault shape constraints keep attachment grants free of contact ciphertext and
  keep PHONE/EMAIL records free of attachment-token fields. Existing contact
  authorization/ciphertext requirements remain unchanged.
- All three historical source grants reconciled `3 -> 3`; field differences and
  compact orphans are zero.
- A transaction sample passed insert, compact `FOR UPDATE` lookup, single-use
  consumption, lifecycle synchronization and delete. It was rolled back with no
  residue and zero contact-record shape violations.
- The first consumption implementation used a single legacy-update/compact-join
  statement. Real MySQL validation rejected it with error 1442 because the legacy
  update trigger also updates the joined vault. Consumption now locks the compact
  grant first and updates the legacy row by primary key in a second statement in
  the same transaction, preserving concurrency safety without trigger re-entry.
- Full backend regression passed after updating the success and denial unit-test
  fixtures for the compact lock query.
- A real authenticated authorization API call passed source/compact `1 -> 1` and
  confirmed the raw token is not stored. The isolated grant and user were removed;
  source, compact and user residue are zero.
- The legacy grant table remains the observation-period write authority and has
  not been dropped.

## V143 verification record

- Authorized contact access now produces one canonical
  `SECURITY/CONTACT_DECRYPT` compact audit from the legacy immutable source insert.
  `target_id` is the feedback source and `correlation_id` is the work-order
  reference. The application-level duplicate audit insert was removed.
- The compact detail contains only source ID, work-order reference, declared
  purpose and contact types. It never contains decrypted phone/email values.
- Migration-time source and legacy-linked compact counts were both zero, with no
  unmatched source records.
- A transaction sample verified source-to-compact insertion, schema/class/outcome,
  typed JSON fields, zero plaintext markers and append-only survival after source
  deletion. Rollback left no source or compact sample residue.
- Feedback timeline queries use `idx_audit_target_timeline`; per-user access
  queries use `idx_audit_user_target`.
- Full backend regression passed. A real decrypt attempt against the existing
  local authorized-contact fixture failed before audit insertion with
  `AEADBadTagException`: the 436 stored ciphertext rows were produced with a
  different historical key than the current local key. No V143 source/compact
  row or temporary user remained. This is an existing key-fixture issue; the
  migration does not rewrite or weaken encrypted contact data to force acceptance.
- The legacy contact audit table remains present for the observation period.

## V144 verification record

- Masking-review SLA policies and exact-term NER allowlist entries now use
  `t_system_config` as their compact read model. SLA queue, urgency and maximum
  minutes remain strongly typed in `scope_key`, `config_subtype` and
  `record_version`; allowlist type, term, lifecycle status and version remain
  strongly typed in `scope_key`, `config_desc`, `record_status` and
  `record_version`.
- The first local migration attempt stopped before inserting data because the
  schema-version value `masking-review-sla-v1` exceeded the previous 20-character
  column. The failed Flyway row and the sole non-transactional DDL residue were
  removed after confirming that no compact rows or triggers existed. V144 then
  widened both version columns to 30 characters and applied successfully; no
  previously successful migration was rewritten.
- Historical reconciliation passed for both domains: SLA policies `3 -> 3` and
  NER allowlist entries `3 -> 3`, with zero typed-field differences and zero
  compact orphans.
- Transaction samples passed legacy insert, update/retirement and delete
  synchronization for both domains. The enclosing transaction was rolled back
  and left zero source or compact sample rows.
- SLA lookup uses the existing type/scope/subtype index and examined one
  candidate in the verification data. Active NER terms use
  `idx_config_type_active`; term matching reads `config_desc` directly and does
  not scan JSON.
- Application reads were cut over to the compact model while writes remain on
  the legacy tables during the observation window. All six shadow-write
  triggers are installed, and neither legacy table has been dropped.
- Full backend regression passed with 409 tests, 13 skipped and zero failures.
  The Day7 authenticated governance harness could not complete because newly
  inserted bcrypt fixture users were rejected by the current local authentication
  runtime before any governance endpoint was reached. Its cleanup completed;
  this is recorded separately from the successful migration, reconciliation and
  transactional checks rather than weakening authentication to force acceptance.
- `t_masking_rule_release` remains deliberately deferred together with its
  evaluation datasets, run records and publication gates.

## V145/V146 verification record

- The complete masking-release evidence domain now has compact read models in
  `t_system_config`: `MASKING_RELEASE`, `MASKING_DATASET` and `MASKING_RUN`.
  The three legacy tables remain the observation-period write authority because
  they enforce the circular release/run foreign keys and independent reviewer,
  approver and publisher gates.
- Release version, allowlist version, lifecycle state, evaluation-run link and
  approval actors are strongly typed. Dataset identity, evidence kind, status,
  sample count, actors and expiry are strongly typed. Run release/dataset links,
  engine version, gate/status, actors and execution time are strongly typed.
  Distribution counts, computed metrics and evidence hashes are read from JSON
  only after indexed record selection; no unbounded JSON scan was introduced.
- Migration-time reconciliation passed: releases `1 -> 1`, datasets `0 -> 0`
  and runs `0 -> 0`. Release field differences and compact orphans are zero.
  Nine V145 insert/update/delete triggers cover all three legacy tables.
- One transaction sample created a release, approved historical dataset and
  passed evaluation run, then exercised dataset/run approval and release
  evaluation transitions. Every compact transition synchronized, all three
  deletes synchronized, and the final rollback left zero source or compact
  residue.
- Version, dataset, run, execution gate, approval gate, publication gate,
  production-readiness and masking quality metric reads now use the compact
  model. Writes still target the constrained legacy tables and synchronously
  update the compact records.
- Real authenticated API reads returned one release, zero datasets and zero runs,
  matching the database. Day7 governance verification passed role canonicality,
  auditor boundaries, rule-catalog completeness and the knowledge five-state
  workflow. Its local bcrypt fixture now normalizes Apache `$2y$` to the
  equivalent Spring-compatible `$2a$`; production authentication is unchanged.
- Full backend regression passed with 409 tests, 13 skipped and zero failures.
  V146 added type/timeline and type/scope/timeline indexes; both list plans use
  backward index scans without filesort. The backend remains healthy on port
  18080.

## V147 verification record

- Masking REVIEW, EVALUATION and RULE_CHANGE evidence now has one canonical
  `SECURITY/MASKING_*` compact event in `t_system_audit_log`. Rule version is the
  typed audit target, evaluation key is the correlation identifier, and reviewer,
  metrics, sample counts and already-sanitized error details are retained.
- The first local attempt failed while parsing an ambiguous `id=id` no-op in the
  duplicate branch, before any row or trigger was created. The failed Flyway row
  was removed after confirming zero residue, the unnecessary branch was removed,
  and the unapplied migration then succeeded. No successful migration history was
  rewritten.
- Initial reconciliation passed `6717 -> 6717`, with zero field differences,
  zero compact orphans and zero phone/email-pattern matches in compact details.
  Only an INSERT trigger is installed: deleting a legacy fixture must not delete
  durable compact audit evidence.
- A transaction sample verified compact insertion, exact metric/sample mapping
  and compact survival after legacy deletion. Rolling back the enclosing
  transaction left zero source or compact sample residue. The rule-version
  timeline query uses `idx_audit_target_timeline` with a backward index scan.
- Topic04 real privacy import passed 1,201-row raw destruction, zero stored raw
  phone values, 1,201 masked phone values and all permission boundaries. It
  produced 2,406 legacy masking audit rows and exactly 2,406 compact events.
- Full backend regression passed with 409 tests, 13 skipped and zero failures.
  After the test build, the local backend was restarted before API verification
  because `bootRun` consumes mutable Gradle class output. Day7 governance then
  passed all four gates. The backend remains healthy on port 18080.

## V148 verification record

- Mutable report-narration circuit state now has a compact
  `REPORT_AI_HEALTH` record in `t_system_config`. Circuit state, consecutive
  failures, probe time, last failure time/code and last success time are retained.
- Migration-time reconciliation passed `1 -> 1`, with zero field differences and
  zero compact orphans. Insert, update and delete synchronization triggers remain
  active while `t_report_ai_health` is the observation-period write authority.
- A transaction sample passed CLOSED -> OPEN -> HALF_OPEN -> CLOSED transitions,
  insert/delete synchronization and full rollback with zero sample residue.
- Permit, probe, success and failure reads now lock the compact row by the unique
  `(config_key, config_version)` index. The plan is a one-row const lookup, so the
  concurrency gate does not widen as more health keys are introduced.
- Full backend regression passed with 409 tests, 13 skipped and zero failures
  after updating two SQL-aware circuit-breaker fixtures. The service was restarted
  after Gradle compilation, then the real six-report MySQL/JWT/HTTP workflow passed
  all distinct-type, facts, validation, degraded-boundary, scope, ownership and PDF
  checks. The local six-report bcrypt fixture was normalized to Spring-compatible
  `$2a$`; production authentication was not changed.
- Four non-isolation post-baseline tables remain unconsolidated:
  `t_alert_escalation_chain`, `t_alert_escalation_target`,
  `t_cluster_review_decision_event` and `t_system_audit_action_catalog`.

## V149-V151 verification record

- Mutable alert escalation chains and responsibility targets now use
  `t_alert_notification` record types `ESCALATION_CHAIN` and
  `ESCALATION_TARGET`. Alert/rule/incident identity, chain and target status,
  target/channel order, acknowledgement deadline and actor, team, severity,
  target user and risk reason remain strongly typed. Channel arrays and display
  snapshots reuse the notification aggregate's JSON/text payload fields.
- Historical reconciliation passed for chains `8 -> 8` and targets `7 -> 7`,
  with zero field differences and zero compact orphans. Six insert/update/delete
  triggers keep both compact record types synchronized while the relational
  legacy tables remain the observation-period write authority.
- The first delete transaction exposed a self-reference through delivery-only
  foreign keys. V150 detached compact chain/target rows from their own legacy IDs
  while retaining the target-to-chain grouping ID. Real notification rows keep
  both original foreign keys unchanged. Insert, lifecycle update, target delete,
  chain delete and full rollback then passed with zero residue.
- V151 stores target display snapshots as valid JSON strings because generic
  notification idempotency checks safely apply `JSON_EXTRACT` to every
  `notify_content`. API reads explicitly unquote the display value. This preserves
  heterogeneous record compatibility without weakening the existing idempotency
  query.
- Chain creation, target resolution, event reconstruction, timeout scanning,
  target advancement, alert detail and notification timeline reads now use the
  compact model. Timeout scans use the typed due index. Source writes remain on
  the legacy constrained tables and synchronize in the same transaction.
- Real ONCALL-REM verification passed both boundaries: a team with no eligible
  owner exhausted with `NO_ELIGIBLE_ONCALL`, P0 notification risk and unchanged P1
  incident severity; a valid leader produced one IN_PROGRESS personal target and
  a RUNNING chain. The verification also exposed numeric `is_primary=1` versus
  JSON boolean comparison, which is now handled by explicit unsigned conversion.
- Full backend regression passed with 409 tests, 13 skipped and zero failures.
  Two non-isolation post-baseline tables remain unconsolidated:
  `t_cluster_review_decision_event` and `t_system_audit_action_catalog`.

## V152 verification record

- Immutable cluster-review decisions now have a compact fact representation in
  `t_alert_occurrence` with occurrence type `CLUSTER_REVIEW_DECISION`. Decision
  type, overturn flag, request identity, candidate count, source/cluster links,
  reviewer, reason and decision time remain typed; only the small candidate
  snapshot is JSON.
- Initial reconciliation passed `9 -> 9`, with zero field differences and zero
  compact orphans. The approved `cluster_review_decision` data contract now
  declares `cluster-review-decision-v2` and `t_alert_occurrence` as its source.
- An INSERT trigger synchronizes new decisions in the source transaction. No
  delete trigger is installed because a review decision is durable evidence: a
  transaction sample verified `is_overturn=1` as `metric_value=1`, compact-row
  survival after source deletion, and zero residue after the enclosing rollback.
- Seven-day decision-volume and overturn-rate reads now use the compact facts.
  The bounded occurrence-type/time query uses a composite covering index rather
  than scanning JSON or the full occurrence table.
- Full backend regression passed with 409 tests, 13 skipped and zero failures.
  The service was restarted after Gradle compilation and remained healthy. Real
  day5 cluster-audit governance verification passed priority SLA, candidate
  comparison, evidence, role boundary, optimistic locking, idempotency, main
  association and audit checks. It added one legitimate reviewed decision, and
  post-verification reconciliation remains exact at `10 -> 10` with zero field
  differences.
- One non-isolation post-baseline table remains unconsolidated:
  `t_system_audit_action_catalog`.

## V153 verification record

- The final post-baseline audit-action catalog now has a compact
  `AUDIT_ACTION_CATALOG` representation in `t_system_config`. Operation is the
  deterministic configuration key; audit class, description and sensitive-action
  flag remain independently queryable without loss.
- Historical reconciliation passed `28 -> 28`, with zero field differences and
  zero compact orphans. Three triggers synchronize insert, operation rename and
  metadata update, and delete while the legacy table remains the observation-period
  write authority.
- A transaction sample passed insert, sensitive flag/class/description update,
  primary-key rename and delete synchronization. The enclosing rollback left zero
  source or compact sample residue. Catalog reads use `idx_config_type_active` and
  examine only the 28 catalog records.
- Full backend regression passed with 409 tests, 13 skipped and zero failures.
  The service was restarted after compilation and remains healthy. Real Day7
  governance verification passed canonical roles, auditor read boundaries, rule
  catalog completeness and the knowledge five-state workflow.
- All non-isolation post-baseline domains are now consolidated. The database still
  physically contains 89 tables because legacy tables are deliberately retained as
  rollback shadows until the separately approved observation-period retirement
  migration; the effective target model is 41 business tables as defined above.

## Observation-period gate record

- Recovery integrity passed: all six files in the pre-consolidation recovery point
  match `SHA256SUMS`. A separately named MySQL database was created and the 35.7 MB
  full dump restored into it in 3.72 seconds without touching the live database.
  The restored image contains 89 base tables, 120 successful migrations and zero
  failed migrations; representative feedback, audit and alert tables reported
  `CHECK TABLE ... OK`. The temporary rehearsal database was removed after evidence
  collection.
- Real MySQL/JWT/HTTP regression passed import governance, query governance,
  cluster-audit governance, alert-center lifecycle, Day7 audit governance, all 16
  alert/on-call controls, SDK transport reconciliation and Topic13 knowledge
  lifecycle/search trace checks.
- The Day5 query verifier had a stale fixture boundary: it inserted verification
  rows while the production endpoint correctly excludes non-production data. Its
  isolated rows now use `data_environment='PRODUCTION'` with no fixture tag and the
  filter matches their stored `OPEN` status; cleanup remains constrained by the
  unique `D5Q-` source identifiers. All nine query gates then passed.
- Full backend regression passed with 409 tests, 13 skipped and zero failures. The
  service was restarted after compilation and remains healthy. There are zero
  lock waits, zero transactions older than ten seconds, zero failed Flyway rows and
  133 synchronization/protection triggers. Final compact catalog and review-window
  queries use covering composite indexes.
- Both pre-retirement gates (full reconciliation/performance regression and restore
  rehearsal) are satisfied. Physical shadow-table removal remains a separate,
  destructive migration requiring explicit approval.

## Production write cutover record

- The first retirement batch switched cluster-review decisions and SDK request
  telemetry directly to typed `t_alert_occurrence` records, and knowledge usage
  directly to canonical `t_system_audit_log` events. These paths no longer require
  a legacy auto-increment identifier or a shadow trigger.
- Cluster, SDK transport and Topic13 knowledge real acceptance all passed. During
  cluster acceptance the legacy decision count remained `11` while compact facts
  increased `11 -> 12`, proving the write no longer traverses the old table. SDK
  and knowledge isolated fixtures cleaned up their compact events after verifying
  the expected deltas; their legacy counts did not increase.
- Runtime Kotlin references to `t_cluster_review_decision_event`,
  `t_sdk_request_telemetry` and `t_knowledge_usage_event` are now zero. Full backend
  regression passed with 409 tests, 13 skipped and zero failures.

## Full production write cutover completion

- All 47 retirement-candidate tables now have zero production Kotlin runtime
  references and zero INSERT/UPDATE/DELETE references. Production writes use the
  41-table compact model; the 47 legacy tables remain physically present only as
  rollback evidence and have not been dropped.
- Reports, RCA, alert governance and test tasks use typed `t_ai_task` record types.
  Audit/receipt/history events use `t_system_audit_log`; delivery, quality,
  security, telemetry and baseline facts use `t_alert_occurrence`; catalogs and
  mutable governance state use `t_system_config`.
- Historical report idempotency records retain pre-hash task keys after V125.
  The coordinator now checks the immutable `input_snapshot.idempotencyKey` before
  inserting a hashed key, so migrated and newly created tasks share one identity.
  Six-report HTTP/MySQL verification passed all report types, fact/number gates,
  scope isolation, listing and PDF generation.
- V154 removed only the two delivery-era foreign keys from compact escalation IDs
  to the legacy chain/target tables. Direct chain and target creation then passed
  ONCALL-REM-001/002: missing ownership records P0 notification risk without
  changing P1 incident severity, while a configured owner creates one live target.
- V155 removed duplicate compact hourly baseline buckets and added a conditional
  unique identity for `HOURLY_BASELINE` only. Duplicate bucket groups are zero;
  other occurrence types are unaffected.
- Final regression passed with 409 tests, 13 skipped and zero failures. Real
  cluster, query, alert center, six-report, Day7 governance, on-call, SDK,
  knowledge and import/privacy verification passed. Flyway V155 is successful and
  the restarted service reports `health=UP`.
- Recovery points remain unchanged and available, including
  `/Users/yang/projects/backups/feedback-platform-pre-shadow-retirement-20260804-2248`.
  Shadow-table DROP remains a separate destructive step and is not part of this
  production write cutover.

## V156 physical shadow-table retirement

- A new immediately pre-retirement recovery point was created at
  `/Users/yang/projects/backups/feedback-platform-pre-physical-retirement-20260805-0015`.
  Its V155 full dump, Flyway history and 89-table inventory all match the stored
  SHA-256 manifest. The dump was restored into an isolated database in 10 seconds;
  it reported Flyway 155, zero failed migrations and all representative tables
  `CHECK TABLE ... OK`.
- V156 was rehearsed against that restored database before touching the live
  schema. The rehearsal removed seven foreign keys wholly contained inside the
  retirement set, dropped the 47 legacy shadow tables, produced exactly 42 base
  tables and left zero retirement candidates.
- The live V156 migration completed successfully with zero failed Flyway rows.
  The final physical schema is 41 business tables plus `flyway_schema_history`.
  Production Kotlin and operational/verification tools contain zero non-migration
  references to the retired names.
- Post-retirement backend regression passed with 409 tests, 13 skipped and zero
  failures. Frontend notification regression passed 33 tests. Real MySQL/JWT/HTTP
  verification passed feedback detail, clustering, query/export, alert center,
  Day7 governance, eight notification channels, on-call escalation, SDK health,
  knowledge lifecycle/search, import/privacy, risk-report hard deletion and all
  six governed report/PDF types. The restarted service remains `health=UP`.

Recovery procedure if a post-retirement defect requires rollback:

1. Stop the backend with `tools/manage-backend-local-secure.sh stop`.
2. Restore the V155 dump into a separately named database first and verify the
   stored `SHA256SUMS`; do not overwrite the live database during diagnosis.
3. After explicit approval, recreate `feedback_platform` from
   `feedback_platform_v155_full.sql`, deploy the matching pre-V156 source state,
   and restart the backend. V156 is intentionally not reversed in place because
   dropped shadow tables are recovered from the verified full dump.
