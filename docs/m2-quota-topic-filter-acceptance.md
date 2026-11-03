# M2.T6.B03: quota, topic, and filter server cases

These are the acceptance oracles for `M2QuotaTopicFilterAcceptanceIntegrationTest`. The test uses real HTTP routes and a disposable V013 PostGIS clone named exactly `roy_m2t6_quota_topic_filter`. It creates random synthetic users and keys on every run, so its assertions are scoped to those users and Points. `TEST_DATABASE_URL` is mandatory and guarded by the exact database name.

| Case | HTTP expectation | Independent committed DB expectation |
| --- | --- | --- |
| Two prior creation ledger events, then last weekly slot | `POST /api/v1/points` → 201; response reports `weekRemaining=0` | One new Point graph and one new ledger event; actor has three rolling-week events. |
| Last member leaves that Point | `DELETE /api/v1/chats/{id}/membership` → 204 | Point graph cascades away; its ledger event remains with `point_id=NULL`. |
| Next create exceeds quota | First `POST` → 409 `POINT_QUOTA_EXCEEDED`; same key → identical terminal 409 body; new key → 409 `POINT_QUOTA_BLOCKED` | One refusal idempotency row has status 409 snapshot; `create_point_blocked_until = refusal.created_at + 720h`; no new Point or ledger event; blocked new-key request creates no record and does not extend block. |
| Canonical duplicate custom topic | First `POST /api/v1/points/{id}/topics` → 201; same label with uppercase and surrounding spaces → 409 `DUPLICATE_TOPIC` | Exactly one `other` topic and Chat for that label, no idempotency row for the rejected duplicate key, and no rejected membership. |
| User joins one topic of a multi-topic Point | `PUT /api/v1/chats/{id}/membership` → 200; public Point detail → 200 | Topic Chat has two active memberships; Point `peopleCount=2` counts distinct users across topics. |
| Query-bound map cursor | First page and continuation → 200; changed `peopleMin` with old cursor → 400 `INVALID_CURSOR`; reversed people bounds → 400 `INVALID_FIELD` | Both rejected GETs leave topic, membership, and Point counts unchanged. |

The test must pass before this task is marked ready. Its observed command, database revision, JUnit result, and task commit belong in the M2 server result protocol.

## Observed focused run

On 2026-11-03, the first focused run on the guarded `roy_m2t6_quota_topic_filter` V013 PostGIS clone ran both tests: the quota case passed, while the topic/filter case failed in its final DB assertion because the test bound a UUID parameter to the text `operation_idempotency.idempotency_key` column (SQLSTATE 42883). The assertion was corrected with an explicit `?::text` cast; no production code changed. The rerun with `--rerun-tasks` on the same guarded database completed **BUILD SUCCESSFUL, JUnit 2 tests, 0 skipped, 0 failures, 0 errors**. The test actors and topic label are generated per run, so the earlier partial test data did not alter the second run's assertions. This focused result does not establish the full M2 server PASS.
