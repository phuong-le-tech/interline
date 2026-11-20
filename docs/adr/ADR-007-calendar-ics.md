# ADR-007 - Calendar through an ICS subscription URL, parsed in the core

Status: **Accepted**, 2026-11-18 (M3 slice 1).
Related: ADR-006 (secrets through the agent), `docs/ARCHITECTURE.md` sections 12 and 13, `docs/ROADMAP.md` M3.

## Constraint

M3 needs the user's calendar before a meeting. EventKit (macOS agent) is the v1 path, but the core must also work on Linux CI and any OS without an agent calendar. An OAuth client secret cannot stay secret in an open source app, so there is no Google or Microsoft sign-in.

## Decision

1. **ICS in the core.** `IcsCalendarSource` (port `CalendarSource` in `core-domain`) reads an ICS subscription URL (Google "secret address", Outlook "published calendar", iCloud public link, any `.ics`). No OAuth, no account, no new dependency: the RFC 5545 subset is hand-written (`IcsParser`) and the fetch uses the JDK `HttpClient`.
2. **The URL is a secret.** Private calendar addresses embed a token. The URL is stored only through `SecretStore` (account `calendar.ics`, same path as provider keys in ADR-006). It is never written to SQLite, logs, problem details or API responses; `GET /api/v1/calendar/ics-source` returns `{configured, host}` only, and logs and errors may name the host. The request record redacts itself so framework request logging cannot print it.
3. **Fetch rules.** `https` only; `webcal://` is rewritten to `https://`; redirects are followed by hand (max 5) and an HTTPS-to-HTTP redirect is refused; 10 s connect, 30 s total, body streamed and capped at 10 MB. The test-only property `interline.calendar.allow-loopback-http=false` allows `http://127.0.0.1` for integration tests and QA. No private-address (SSRF) blocking: the user chooses the URL for their own machine, and the API is token-protected and bound to loopback.
4. **Supported RFC 5545 subset.** Line unfolding, TEXT unescaping, UTF-8 with replacement. `DTSTART`/`DTEND`/`DURATION` in UTC, `TZID` (IANA, plus a small table of common Windows names, otherwise the configured local zone with a warning), floating times in the local zone, all-day `VALUE=DATE`. `RRULE` FREQ DAILY/WEEKLY/MONTHLY/YEARLY with INTERVAL, COUNT, UNTIL, BYDAY (monthly with ordinal), BYMONTHDAY, BYMONTH; `EXDATE`; `RECURRENCE-ID` overrides; `STATUS:CANCELLED` dropped. An unsupported rule part keeps the first occurrence and adds a warning. Occurrence `external_id` = `UID#<occurrence start epoch ms>`.
5. **Limits (warnings, never a crash).** Body 10 MB, logical line 64 KB, 20 000 VEVENT, 500 occurrences per series, 5 000 stored per sync. A malformed VEVENT is skipped and counted.
6. **Sync window and storage.** The window is -1 day / +14 days. A sync replaces the `ics` events of the window in one transaction (upsert present, delete absent), upserts people by lower-cased email (an existing non-empty display name is kept; attendees without email stay only in the event JSON). Past events outside the window are not purged by a sync.
7. **API.** `PUT/GET/DELETE /api/v1/calendar/ics-source`, `POST /sync`, `GET /events`, behind the existing token filter, errors as problem+json (`calendar_not_configured` 409, `calendar_fetch_failed` 502).

## Behaviour decisions (review round 1)

- **Not a calendar is a failure.** A 200 body without a complete `BEGIN:VCALENDAR` ... `END:VCALENDAR` (sign-in HTML page, blank, truncated) is a sync failure (502 `calendar_fetch_failed`) and leaves the stored events alone. A valid but empty VCALENDAR still clears the window; a malformed single VEVENT is skipped and counted.
- **Source changes versus a running sync.** Setting or removing the address bumps a source generation. A sync checks it under the same lock before writing and answers 409 `calendar_source_changed` instead of bringing back events of a dropped source. Fetching itself holds no lock, so removing the address never waits for a slow download.
- **A cut snapshot never deletes.** When parsing stops early (step budget, 10 s deadline, VEVENT cap, 5 000 stored cap, per-series cap, oversize body, or a logical line over 64 KB that was discarded) the snapshot is marked `complete: false`. The sync then only adds and updates; it removes nothing, because an event missing from a partial read may simply not have been read. The response carries `complete` and a warning containing "incomplete".
- **Framing is checked through the end of the body.** Every `BEGIN:VCALENDAR` needs its `END:VCALENDAR` (several calendars in one body are fine); a nested or stray marker, or a truncated trailing calendar, makes the whole sync fail with 502 and leaves the store untouched.
- **Timed DTEND is exact.** For date-time DTSTART and DTEND the elapsed length (end instant minus start instant) is kept for every occurrence, so a 2 h series stays 2 h across a DST change; all-day events stay nominal calendar days.
- **Rule lists are deduplicated before validation.** DTSTART always opens a series (RFC 5545); a genuinely out-of-domain value keeps that first occurrence and warns, so an event never disappears silently.
- **Moved instances inherit.** A RECURRENCE-ID override with no ATTENDEE, ORGANIZER or conference URL inherits attendees and conference URL from its master; one that has its own keeps its own. Overrides are placed by their own DTSTART, not by the original RECURRENCE-ID.
- **Durations.** Timed parts (H, M, S) are elapsed time; day and week parts are nominal calendar days in the event zone (RFC 5545 3.3.6).
- **Recurrence.** WKST is honoured for WEEKLY with BYDAY; YEARLY with BYMONTHDAY and no BYMONTH applies to every month.
- **Field caps (warnings).** 200 attendees per event, title 500, attendee name 200, email 320 (the attendee is kept without the email), conference URL 2048 (dropped).
- **Redirect hops are checked, the typed address is not.** A redirect may not go to `localhost`, `*.localhost`, `*.local`, `*.internal`, or to loopback, link-local (including 169.254.169.254), private, unique-local, shared-address (100.64.0.0/10), benchmarking (198.18.0.0/15), 192.0.0.0/24, multicast, NAT64 (64:ff9b::/96) and IPv4-compatible IP literals, or to integer, octal and hex spellings of an IPv4 address. With the test-only loopback flag only `http://127.0.0.1` stays allowed. DNS rebinding (a public name that resolves to a private address) is out of scope: the fetcher does not resolve names itself.
- **People outlive the source.** Removing the ICS source deletes the `ics` events but keeps the `people` rows: participants and briefs may reference them later.

## Consequences

- The M3 exit criterion "an ICS URL gives the same result on the Linux CI (MockAgent without calendar)" is testable now.
- Without an agent there is no OS secret store, so saving the URL fails with `secret_store_unavailable` (503), like provider keys.
- A calendar change appears at the next sync; refresh on start and every 5 minutes, `calendar.changed`, EventKit, notifications, briefs and the Home "Today" UI are later slices.
- Sync is manual in this slice, but a hostile feed is not free: it costs one bounded download (10 MB, 30 s) and a parse bounded by a parse-wide step budget (2 000 000 recurrence periods), a 10 s wall-clock deadline, a per-series cap, deduplicated rule lists (BYDAY, BYMONTHDAY, BYMONTH) and arithmetic jumping to the window instead of walking from DTSTART. When a limit is hit the parser stops expanding, keeps what it found and adds a warning.

## Deferred

EventKit and `calendar.query`/`calendar.changed`, periodic refresh, CalDAV, ETag/If-Modified-Since, VTIMEZONE definitions (IANA and the Windows table only), RDATE, BYSETPOS, purge of old synced events, UI.

## M3 EventKit addendum

When no ICS URL is configured and the agent advertises `capabilities.calendar:true`, `AgentCalendarSource` reads EventKit through the existing private protocol connection. A configured ICS URL retains priority and its existing manual-sync behavior. The core refreshes the agent calendar on connection, every 5 minutes, and on `calendar.changed`; the agent debounces OS store changes for 500 ms. No network call is introduced.

The macOS agent uses `requestFullAccessToEvents` and the embedded `NSCalendarsFullAccessUsageDescription`. EventKit expands recurring instances in the window; cancelled instances are omitted. Occurrence identity uses the calendar item identifier plus the original occurrence timestamp. The reply carries the calendar name for protocol clients; the existing domain and storage retain the same minimal event fields as ICS.

Agent snapshots use `source='agent'` with the same window replacement transaction and source-generation guard as ICS. An incomplete snapshot only upserts. Malformed replies fail without modifying storage. Changing to an ICS URL removes agent events, while removing the URL queues an agent refresh. People remain available independently of source removal.

Calendar denial clears agent events, emits replayable `calendar.denied` SSE, and shows the neutral banner "Calendar access denied - using manual meetings" with the existing Open System Settings action. Recording and manual meetings remain usable. A successful later refresh emits `calendar.granted` to dismiss the banner.

MockAgent advertises calendar support only with a calendar script or `--deny=calendar`, preserving existing manual and ICS-only fixtures. It supports `--deny=calendar` and script JSON `calendar:{events,complete,changes:[{after_ms,events,complete}]}`; timed changes begin at the first query. Portable conformance tests exercise this contract. Native status and test-input queries run with the existing `interline.swift-agent` property; a real EventKit read is separately gated by `interline.calendar.native=true` because it needs the user's permission and calendar data. Real account reads and permission-prompt behavior remain manual checks.
