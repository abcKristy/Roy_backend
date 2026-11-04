# M4 group 8 — cancellation events and jobs

## Pool, base and isolation

Pool 7 group 8 branch: feat/cancellation-events-jobs. Lead verified the completed
pool 6 merge/push/fetch/ls-remote checkpoint at
b29e7afc5551b7afdf7ff6765f4a9b7f80815135 before creating this branch and parallel
group 10 from that same base. The reused managed worktree path is
C:/Users/lolut/.codex/worktrees/booking-response-event/backend; its historical
basename is not the current branch name. Worker Git operations were not used.

Own alias: roy_m4_cancel_events. Container codex-m4-response,
b9cd122b11f0dfaa36d930a6ac8b3554eca4dcfef3f6bdf0e5d6cee3bc9ea871,
was explicitly reused for group 8. Its verified labels remain historical
codex.group=6 and codex.task=m4-t087-t114; no false group8 label is claimed.
Targeted inspection confirmed running state and only127.0.0.1:55485.
The ignored absolute environment recipe is dot-sourced with the own alias/port;
its contents and credentials are not reproduced.

## T104 / M4.T4.B04 — committed cancellation messages

Added BookingCancellationEventsIntegrationTest; existing production hooks and
applied migrations were retained. Three real native Netty/JDK HTTP/WS scenarios
cover unvote, chat leave and account deletion. JDBC sessions are real and deletion
uses a real Argon2 password check. Each own UUID fixture has a future active booking,
revision3, two voters, a remaining nonvoter, an adjacent topic and a nonmember.

Each scenario first installs an own-booking deferred constraint trigger that raises
23514 at COMMIT on ACTIVE->CANCELLED. Real HTTP500 must restore the full business,
membership/account/session/message baseline, GET booked/ACTIVE and WS silence.
After dropping the trigger, a SHARE lock on messages blocks the actual transaction;
bounded pg_locks observation, pending HTTP, SQL/GET still ACTIVE and ordered USER
markers verify no premature cancellation. Only after releasing the gate may the
operation commit its cancellation.

The committed snapshot must show count1/revision4, cancelled booking with
insufficient_votes and database server cancellation time bounded by pre/post SQL
clock reads. Exactly one canonical SYSTEM meeting_booking_cancelled, proposal.updated
and booking.changed(cancelled) set must reach both remaining members with matching
event IDs and stored message IDs/revision. Common payloads omit viewerGoing;
account deletion anonymizes the cancellation actor and the former booking owner.
The leaver receives only allowed user membership metadata; the deleted session
closes4401. Neither receives private cancellation payloads. Adjacent-only and
rejected outsider sockets do not receive private booking/proposal IDs; public
point.changed frames are distinguished from private chat cancellation frames.

Repeated desired-unvote/leave, revoked deletion retry and repeated GET do not add
revision, SYSTEM or notification effects. Cancellation notifications use one
booking_cancelled:<bookingId> key per each of two eligible remaining recipients.
No booking.cancelled envelope is introduced. Ordered markers plus250ms are bounded
observations, not unbounded delivery guarantees. T105 job invalidation, full races,
M5 delivery, client, combined T3–T5/T6 and BG-1 acceptance remain separate.

## T104 execution

Explicit working directory:
C:/Users/lolut/.codex/worktrees/booking-response-event/backend.
Exact command:

`.\gradlew.bat integrationTest --tests com.example.meetings.BookingCancellationEventsIntegrationTest --offline --no-daemon --max-workers=1`

Execution receipts below distinguish the initial preparation failure from actual
scenario-body acceptance; implementation alone is not PASS or review approval.
First run of the exact command failed in2m10s, exit1, before any scenario body:
JUnit tests=3/skipped=0/failures=3/errors=0, timestamp2026-11-04T15:50:37.471Z,
suite92.263s. All three fixture initializers reported FATAL database
roy_m4_cancel_events does not exist. A targeted pg_database query in the own
container confirmed absence. This is a preparation failure, not backend behavior
or acceptance PASS; no production correction was made.
After that process completed, the assigned isolated alias was provisioned with:

`docker exec codex-m4-response createdb -U m4_test roy_m4_cancel_events`

The same focused command is repeated against that prepared own database.
Prepared-database repeat used the same exact command and unchanged executable test.
04.11.2026 BUILD SUCCESSFUL in33s, exit0;1 task executed,5 up-to-date. Actual XML
build/test-results/integrationTest/TEST-com.example.meetings.BookingCancellationEventsIntegrationTest.xml:
tests=3/skipped=0/failures=0/errors=0; timestamp2026-11-04T15:53:12.326Z,
suite21.692s. Executed bodies: leave9.687s, account deletion5.911s, unvote6.090s.
All three COMMIT-fault, real precommit gate, remaining-member/actor privacy,
server-time/reason/revision and repeat/no-duplicate assertions executed. No
production finding or production/migration edit was required for T104.

Exact post-run SQL command in the own reused container:

```powershell
docker exec codex-m4-response psql -U m4_test -d roy_m4_cancel_events -At -c "SELECT current_database(),current_setting('server_version'),postgis_lib_version(); SELECT string_agg(version,',' ORDER BY installed_rank) FROM flyway_schema_history WHERE success AND version IS NOT NULL; SELECT (SELECT count(*) FROM users),(SELECT count(*) FROM points),(SELECT count(*) FROM chats),(SELECT count(*) FROM meeting_proposals),(SELECT count(*) FROM votes),(SELECT count(*) FROM bookings),(SELECT count(*) FROM messages),(SELECT count(*) FROM notification_events),(SELECT count(*) FROM push_delivery_jobs),(SELECT count(*) FROM media_deletion_jobs),(SELECT count(*) FROM operation_idempotency),(SELECT count(*) FROM auth_sessions),(SELECT count(*) FROM pg_trigger WHERE tgname LIKE 'm4_cancel_event_fault_%'),(SELECT count(*) FROM pg_proc WHERE proname LIKE 'm4_cancel_event_fault_%');"
```

It confirmed own alias, PostgreSQL18.6/PostGIS3.6.4, successful V001–V015 and
fourteen zero fixture/fault counts in that order. Container labels remain the
verified historical group6/task labels; group8 ownership is scoped by this explicit
reuse and its own alias. Authorized escalation covered assigned worktree writes,
Gradle cache and Docker pipe only; no security settings or unrelated containers changed.

Worker-owned external statuses updated: T4/B04-cancel-event.md, T4/README.md and
M4-T4.md (backend4/5 locally), physically outside backend Git. Shared checklist,
roadmap and master execution protocol belong to Lead and were not edited here.
Task subject: `Verify committed booking cancellation events`.
Independent group_review task approval and contracts branch review are separate;
no unreceived APPROVE or T105/whole-parent/BG-1 acceptance is claimed.
T104 independent read-only task review by group_review: APPROVE before commit, zero confirmed findings. Lead synchronized all five external status places: T4 backend4/5, checklist107/455, M4 backend23/28 at this checkpoint.

T104 task review by group_review: APPROVE before Lead commit
ef4e0aa357a6ddc87e9dcbc04ec1b10e1c6723a9. Lead stamped actual SHA in the five
external accounting references. T105 starts after that task commit.

## T105 / M4.T4.B05 — durable reminder invalidation

Own alias roy_m4_cancel_jobs was checked absent, then created before testing using
`docker exec codex-m4-response createdb -U m4_test roy_m4_cancel_jobs`.
The same reused container/historical group6 label and only127.0.0.1:55485 were
verified. The ignored absolute recipe selected this own alias and port.

Added jobs/BookingReminderCancellationIntegrationTest. Four actual PostgreSQL
cases cover: (1) durable PENDING/PROCESSING cancellation for unvote, leave, account
deletion and author close; (2) scheduler candidate selected by real committed SQL,
then application cancellation before scheduler's locked recheck; (3) worker after
real authenticated token decrypt but before provider; (4) provider already entered,
then cancel and late Delivered/Retryable completion. Each fixture registers two
installations through real DeviceService using ephemeral AES/GCM key material and
production token encryption/decryption. Provider adapters are local controlled
fakes; no real delivery, provider validation or BG-1 is claimed. Historical in-app
reminders may remain; no second visible reminder is allowed for remaining recipients.

First exact command, own explicit workdir:
`.\gradlew.bat integrationTest --tests com.example.jobs.BookingReminderCancellationIntegrationTest --offline --no-daemon --max-workers=1`
04.11.2026 BUILD FAILED in21s, exit1; XML4 tests/0 skipped/1 failure/0 errors,
timestamp2026-11-04T16:06:17.464Z, suite6.816s. The scheduler selected-candidate,
all-four-caller durable state and already-entered Delivered/Retryable cases passed.
The pre-send race failed: after real unvote cancelled the booking and cleared its
PROCESSING claim during the real-decrypt gate, releasing before sender invocation
still called the fake provider once (expected0, actual1). This is a concrete
point-in-time pre-send validation defect; it does not imply that an already called
provider can be unsent. Lead and group_review were notified before production editing.
Post-RED own users/points/proposals/votes/bookings/notifications/jobs/devices/
outcomes/sessions=0/0/0/0/0/0/0/0/0/0.
Raw actual RED XML was preserved at ignored .local/t105-evidence/t105-red.xml;
no test token plaintext is printed by the new assertions or protocol.
Lead and group_review independently confirmed the concrete P2 and approved the
exact production correction before editing. Only PushDeliveryWorker.processOne
changed: after decrypt and before starting provider I/O, a fresh short read-only
loadValidCandidate rechecks current claim/lease, booking/resource, recipient and
device eligibility. An invalid candidate takes the existing guarded CANCELLED
finish/return path. Provider I/O remains outside JDBC transactions/locks. This is
a point-in-time recheck; it does not atomically close the final external DB->provider
TOCTOU window and cannot recall an already entered provider call. Its late completion
must still fail the old claim-token/status CAS, as the actual tests verify.

Corrected exact command was unchanged:
`.\gradlew.bat integrationTest --tests com.example.jobs.BookingReminderCancellationIntegrationTest --offline --no-daemon --max-workers=1`
04.11.2026 BUILD SUCCESSFUL in20s, exit0;3 tasks executed,3 up-to-date.
Actual XML4 tests/0 skipped/0 failures/0 errors, timestamp2026-11-04T16:10:59.874Z,
suite5.862s. The previously failing pre-send race now made0 provider calls. The
four-caller durable matrix, selected-candidate scheduler race and both late provider
completion branches also executed successfully. Own successful V001–V015 remained
unchanged; post-GREEN users/points/proposals/votes/bookings/notifications/jobs/
devices/outcomes/sessions were ten zeros. GREEN XML was archived before the next run
as ignored .local/t105-evidence/t105-green.xml.

### Existing worker regressions on own strict-alias databases

The two exact legacy aliases were checked absent inside codex-m4-response and
created only there from the verified empty own V001–V015 schema:

```powershell
docker exec codex-m4-response createdb -U m4_test -T roy_m4_cancel_jobs roy_m1t2_step7jobs
docker exec codex-m4-response createdb -U m4_test -T roy_m4_cancel_jobs roy_m1t2_step8delete
```

They are unrelated to any original M1 database outside this own container. Lead
explicitly permitted setting only these strict-alias loopback URLs after loading
the own ignored recipe; no recipe/security/production.env file was changed.
All commands used the explicit group8 worktree working directory documented above.
Exact Jobs command:

```powershell
. 'C:\d\myFiles\proga\AndroidStudio\kotlin\vus\vkr\project\backend\.local\m4-test-environment.ps1' -DatabaseAlias roy_m4_cancel_jobs -DatabasePort 55485
$env:TEST_DATABASE_URL='jdbc:postgresql://127.0.0.1:55485/roy_m1t2_step7jobs'
.\gradlew.bat integrationTest --tests com.example.jobs.JobsIntegrationTest --offline --no-daemon --max-workers=1
```

BUILD SUCCESSFUL in13s, exit0;1 task executed,5 up-to-date. Actual XML3/0 skipped/
0 failures/0 errors, timestamp2026-11-04T16:12:42.850Z, suite1.985s. Reminder dedup/
time expiry, push claim/rebind and media lease cases ran. Raw XML was archived as
.local/t105-evidence/legacy-jobs.xml. These legacy tests retain synthetic fixtures:
users/points/proposals/bookings/events/pushjobs/mediajobs/devices before cleanup
were3/2/2/1/3/2/1/1. Explicit cleanup ONLY in this worker-created exclusive alias
used BEGIN; DELETE FROM points; DELETE FROM users; DELETE FROM media_deletion_jobs;
COMMIT; all eight subsequent counts were zero. Schema and Flyway history were kept.

Exact DeviceRebind command:

```powershell
. 'C:\d\myFiles\proga\AndroidStudio\kotlin\vus\vkr\project\backend\.local\m4-test-environment.ps1' -DatabaseAlias roy_m4_cancel_jobs -DatabasePort 55485
$env:TEST_DATABASE_URL='jdbc:postgresql://127.0.0.1:55485/roy_m1t2_step8delete'
.\gradlew.bat integrationTest --tests com.example.devices.DeviceRebindDeliveryConcurrencyIntegrationTest --offline --no-daemon --max-workers=1
```

BUILD SUCCESSFUL in12s, exit0;1 task executed,5 up-to-date. Actual XML1/0 skipped/
0 failures/0 errors, timestamp2026-11-04T16:13:49.349Z, suite1.349s. Real concurrent
in-flight rebind retained the old claim cancellation and did not resurrect it.
Raw XML was archived as .local/t105-evidence/legacy-device-rebind.xml.
Legacy retained users/points/events/jobs/devices were2/1/1/1/1; explicit own-alias
BEGIN; DELETE FROM points; DELETE FROM users; COMMIT; made all five counts zero.
Both aliases reported PostgreSQL18.6/PostGIS3.6.4; successful V001–V015 were confirmed
from the template and the device test's actual schema validation. Legacy provider/
decoder stubs remain regression boundaries, not real delivery or encryption proof;
the new T105 fixture separately used real DeviceService/AES-GCM encryption/decryption.

Total corrected acceptance: eight executed JUnit tests across the new4 + existing3
+ existing1, all with0 skips/failures/errors. Four actual XML archives (RED and three
GREEN runs) live only in ignored .local/t105-evidence; this protocol preserves safe
versioned commands, metrics and scope, not credentials/tokens. The tested branch
was not automatically updated to later main80dcb6f; Lead owns subsequent integration
and affected reruns, not an unperformed claim that these runs tested that merge.

Only worker-owned external T4/B05-cancel-jobs.md, T4/README.md and M4-T4.md statuses
were updated (backend5/5 locally), physically outside backend Git. Shared checklist,
roadmap and master protocol belong to Lead and were not edited by this worker.
Task subject: `Verify durable reminder invalidation on cancellation`.
Finding-level approval above is not full task/branch approval; those independent
reviews and the task commit remain separate. Real external delivery, M5 provider,
client, complete race schedules, combined T3–T5/T6 and BG-1 remain outside acceptance.
T105 independent read-only final task review by group_review: APPROVE after the approved pre-send recheck, real RED-to-GREEN and both existing worker regressions. Lead synchronized all five external status places: T4 backend5/5, checklist108/455, M4 backend24/28. Current main integration and independent whole-branch review remain separate.
