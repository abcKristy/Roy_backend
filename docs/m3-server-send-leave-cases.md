# M3 concurrent send/leave acceptance

T086 / M3.T6.B04 executed04.11.2026 on tests/chat-realtime-races, accepted preceding T085 commit12115cbd2d9fc7a0c3282f6526c7dd2c3cda9368 plus the T086 test revision. No production, migration or shared-fixture code changed. Environment: JDK17/Gradle9.5.1; owned disposable PostgreSQL18.6/PostGIS3.6.4 roy_m3_chat on loopback32795; V001–V015. Credentials and token material are excluded.

## Actual overlap evidence

ChatSendLeaveRaceIntegrationTest executes three separate fresh synthetic namespaces with actual persisted sessions, two HTTP clients, production PostgreSQL clock and real transactions. An external transaction holds the owning point row. The first HTTP command reaches the point lock while retaining the actor lock. pg_blocking_pids confirms its backend waits for the external blocker. The second HTTP command then waits for that first backend's actor lock. Both deferred HTTP commands are asserted incomplete before releasing the point transaction. This observed lock graph establishes actual overlap; these are not sequential requests labelled concurrent. Polling has bounded timeouts, not a fixed sleep used as proof.

The three safe completed traces preserve real request UUIDs, send idempotency keys, statuses, canonical bodies, backend PIDs at the observed barrier and before/after snapshots:

- [Send first](evidence/m3-send-leave-send-first.json).
- [Leave first](evidence/m3-send-leave-leave-first.json).
- [Last member leaves first](evidence/m3-send-leave-last-member.json).

Snapshots record messages, distinctSeq, lastSeq, actor rate/outcome counts and left membership count. Each trace is generated after all assertions and scoped fixture cleanup succeed. Synthetic payloads only; no Authorization/JWT/password/phone data is copied. Concrete UUIDs/PIDs identify this disposable run and are not stable inputs for another execution.

## Observed orders

Send first: send201 creates textseq3 before leave204 appends member_leftseq4. Persisted messages/distinct sequences/last sequence all equal4; actor rate/outcome counts each1 and left membership count1. Successful-key replay after leave returns the same canonical Message and changes no snapshot. Repeated leave204 is unchanged. Actor history403; peer history200 contains descending seq4..1, complete canonical member_left payload and exactly the successful Message atseq3.

Leave first: leave204 appends member_leftseq3 before overlapping send403 NOT_MEMBER. Persisted messages/distinct sequences/last sequence all equal3; actor rate/outcome counts remain0 and left membership count1. Retrying the refused send remains403 NOT_MEMBER, with no saved successful outcome or extra message. Repeated leave204 changes nothing. Actor history403; peer history200 contains descending seq3..1 with canonical member_left.

Last member leaves first: peer leaves its second point's only chat204 before the overlapping send403 NOT_MEMBER. The point/chat/messages/memberships are physically absent; message/distinctSeq/lastSeq/rate/outcome/left counts are0. Repeated send remains403; repeated leave returns404 RESOURCE_GONE. Both actor/peer history reads return403. The other fixture point/chat survives with its two original history rows, confirmed through SQL counts and an authorized GET.

## Actual checks and limits

Lead executed `./gradlew.bat integrationTest --tests com.example.acceptance.ChatSendLeaveRaceIntegrationTest --offline --no-daemon --max-workers=1`, exit0, BUILD SUCCESSFUL in1m13s. Inspected current XML tests3/skipped0/failures0/errors0, timestamp2026-11-04T08:43:01.307Z, suite9.103s. All three explicit Unit-returning JUnit tests executed. Local T086 PASS for these controlled overlapping orders. No production defect was observed, so no speculative fix or invented before-fix failure is recorded.

The controlled database barriers cover these legal serializations, not every scheduler interleaving or stress/load profile. This test application has no WS transport. No absent-event runtime proof is claimed here: code inspection of ChatWriteRoutes shows only Created publishes chat.message.created; the exercised NotMember result maps to403 without entering that publication branch. The separate WS protocol provides its own bounded transport evidence. Deleted-chat send is demonstrated not to create a Message/rate/success outcome, rather than an unobserved WS claim. Mobile, BG-1, external integrations, deployed transport and overall M3 acceptance remain open.
