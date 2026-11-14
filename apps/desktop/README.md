# Desktop scaffold

React 19, TypeScript, Vite, ESLint and Chromium Playwright. The page is deliberately an empty, unstyled skeleton. Recording, API access and Tauri integration are not implemented here.

## Commands

Use Java 21 and pnpm 12.16.1.

```sh
cd apps/desktop
pnpm install --frozen-lockfile
pnpm lint
pnpm typecheck
pnpm build
pnpm exec playwright install chromium
pnpm test:e2e
```

Before E2E, build the core from the repository root:

```sh
cd services/core
mvn -DskipTests package
```

AC14_live starts the real `services/core/app/target/interline-core.jar` with the absolute MockAgent launcher path and `--agent-arg=--speed=20`. It sends a deterministic public 256-bit test token over stdin, waits for the documented JSON READY line, and launches Vite on `http://127.0.0.1:5173`. The port and token are supplied only through `VITE_CORE_PORT` and `VITE_CORE_TOKEN` in Vite's environment. No request interception, fake HTTP server or injected browser state is used. Tauri is not needed to run this test.

The test verifies that the placeholder loads, then asserts core readiness inside the test. A missing READY is an ordinary test assertion with process diagnostics, not a global setup or web server error. Once the core is implemented, AC14 will next fail on the missing Record control until UI implementation is provided. Its remaining assertions require Me and Them transcript entries before Stop, followed by a done meeting and status.

The helper uses isolated temporary data and log directories under the runtime's temporary directory. In a finally block it shuts down stdin, terminates the core/agent and Vite process groups with bounded escalation, removes temporary data, and attaches redacted process output to the test result. Keep port 5173 free; the test never reuses another dev server.
