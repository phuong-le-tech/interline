# EventKit calendar acceptance contract

These tests intentionally precede implementation. Existing ICS M3 tests and protocol
M0/M1/M2 goldens are unchanged. CalendarProtocolTest freezes every existing golden
file with its SHA-256, validates new messages, and uses the existing frame codec.
The calendar capability is a boolean at hello.body.capabilities.calendar.
Calendar events carry re matching the query id. All listed event fields are present;
attendees carry string name and email. Empty event arrays are valid.

## MockAgent fixture convention

Reuse the existing --script JSON argument, keeping sample_rate, samples and segments.
Add calendar: {events: [...], complete: true}. Each calendar.query reads the current
script atomically, filters overlapping events to [from_ms,to_ms), and answers with
calendar.events. A missing complete means true; complete:false is a partial snapshot
that must never delete stored events. This flag extends the reply body as well.
The script is replaced atomically by tests, never partially overwritten.

Optional calendar.changes is an array of {after_ms,events,complete}. after_ms is relative
to the first calendar.query. At each change the agent replaces its calendar snapshot
and emits calendar.changed. This lets a test prove core refresh without POST /sync
and before the five-minute poll. These are test fixture conventions, not EventKit APIs.
No WAV generation, audio session or model download is needed for calendar queries.

--deny=calendar keeps the agent alive, reports calendar:denied via permission.status,
and returns empty calendar.events correlated by re. Core publishes calendar.denied
for a denied sync, including when a client subscribes after startup. Manual meeting
creation remains available. Browser startup must show the neutral calendar banner
and the Privacy_Calendars settings link without showing an audio permission banner.
The browser test uses real MockAgent/core processes, not injected SSE.

## Core expectations

Without an ICS URL, startup imports advertised agent events with source:agent.
Changed snapshots remove absent agent events, preserve manual rows, and keep stable
row ids for surviving external ids. Partial snapshots update good rows and preserve
unmentioned rows. An explicitly configured ICS source takes precedence; the existing
ICS suite remains the regression gate. Agent calendar_name is tested on the wire;
the existing HTTP event shape need not add it.

## Native and manual boundary

CalendarAgentTest always tests MockAgent on every OS. Its native-only test follows
the existing interline.swift-agent system property gate and only sends permission.status.
It verifies calendar capability and a recognized permission state. It never sends
permission.request, calendar.query or starts capture against the real EventKit store.
The repository currently names its native profile swift-agent, not agent-macos;
the implementation task must wire any new agent-macos profile to the same property.

Real requestFullAccessToEvents, expanded recurring instances, skipped cancelled events,
and debounced EKEventStoreChanged behavior remain manual checks on macOS. The new
suite does not wait five real minutes to test the periodic refresh, nor does it add
a new scheduler or clock seam. Existing ICS transaction and generation-race tests
remain intact; extending those invariants to the agent implementation needs review.

## Recorded acceptance proof

Preparation (passed):

```sh
./services/core/mvnw -f services/core/pom.xml -DskipTests package
pnpm --dir apps/desktop install --frozen-lockfile
```

Each Java acceptance command exited 1 for missing product behavior:

```sh
./services/core/mvnw -f services/core/pom.xml -pl adapter-agent-protocol -am test -Dtest=CalendarProtocolTest -Dsurefire.failIfNoSpecifiedTests=false
./services/core/mvnw -f services/core/pom.xml -pl agent-conformance -am test -Dtest=CalendarAgentTest -Dsurefire.failIfNoSpecifiedTests=false
./services/core/mvnw -f services/core/pom.xml -pl app -am test -Dtest=AgentCalendarAcceptanceTest -Dsurefire.failIfNoSpecifiedTests=false
```

- CalendarProtocolTest: 20 run, 9 failures, 0 errors, 0 skipped. The other 11 pass.
- CalendarAgentTest: 3 run, 2 failures, 0 errors, 1 skipped native status check.
- AgentCalendarAcceptanceTest: 5 run, 1 failure (sync returns 409 without ICS),
  4 errors (Awaitility times out waiting for the absent agent calendar events).
  All compile and execute against the real core and packaged MockAgent.

Regression proof (exit 0):

```sh
./services/core/mvnw -f services/core/pom.xml -pl app -am test -Dtest=GoldenTest,M1GoldenTest,M2GoldenTest,M3CalendarApiTest,CalendarServiceRaceTest,CalendarServiceIncompleteTest -Dsurefire.failIfNoSpecifiedTests=false
pnpm --dir apps/desktop exec tsc --noEmit
pnpm --dir apps/desktop exec eslint e2e/calendar-agent.spec.ts
```

The Java regression command passes 85 tests: 19 M0 golden, 9 M1 golden, 7 M2
golden, 36 ICS API, 6 ICS generation/race, and 8 ICS incomplete-snapshot tests.

Browser acceptance proof (exit 1):

```sh
pnpm --dir apps/desktop exec playwright test e2e/calendar-agent.spec.ts
```

1 test run, 1 failed, 0 skipped. Its two soft failures are precisely the missing
calendar denial banner and its settings link. Creating a manual meeting returns
201 and opens the notes editor successfully. The final run waited for the shared
port 5173 to become free; the test stack cleaned up its own Vite/core processes.
