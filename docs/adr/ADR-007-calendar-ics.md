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

## Consequences

- The M3 exit criterion "an ICS URL gives the same result on the Linux CI (MockAgent without calendar)" is testable now.
- Without an agent there is no OS secret store, so saving the URL fails with `secret_store_unavailable` (503), like provider keys.
- A calendar change appears at the next sync; refresh on start and every 5 minutes, `calendar.changed`, EventKit, notifications, briefs and the Home "Today" UI are later slices.
- Sync is manual in this slice, so a malicious feed can only cost one bounded download and parse per user action.

## Deferred

EventKit and `calendar.query`/`calendar.changed`, periodic refresh, CalDAV, ETag/If-Modified-Since, VTIMEZONE definitions (IANA and the Windows table only), RDATE, BYSETPOS, purge of old synced events, UI.
