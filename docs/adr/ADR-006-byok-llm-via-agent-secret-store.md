# ADR-006 - BYOK LLM via Spring AI, keys in the OS secret store via the agent

Status: **Accepted**, 2026-11-17.
Related: ADR-001 (hybrid stack), ADR-002 (agent protocol), ADR-005 (note blocks), `docs/ARCHITECTURE.md` sections 4, 10, 13 and 17, `docs/ROADMAP.md` M2, `docs/SPEC.md` E3/E4/US-6.1.

## Constraint

M2 is the first milestone with outbound network calls to LLM providers. The user brings their own API key. The key must never be stored in the database, never appear in logs, never be returned by the API and never be present in a test fixture except with the `sk-test` prefix.

## Decision

1. **Provider row stores no key.** The `providers` table holds only `secret_account`, an opaque account name used to look up the key in the OS secret store. The schema comment is: "Account name in the OS secret store. The API key itself is never stored here." Additional columns `context_tokens`, `price_in_per_mtok` and `price_out_per_mtok` are nullable.

2. **Keys live in the OS secret store, reached through the agent.** Agent Protocol v1 gains `secret.get {account}`, `secret.put {account, value}` and `secret.delete {account}`. The macOS agent uses the Keychain as a generic password under service `app.interline`. The `core-domain` port `SecretStore` and the `adapter-agent-protocol` implementation `AgentSecretStore` fetch the key per call and cache nothing.

3. **Spring AI without starters.** The `core-ai` module depends only on the Spring AI model libraries (`spring-ai-openai`, `spring-ai-anthropic`, `spring-ai-ollama`). The starter artifacts are excluded so Spring AI auto-configuration never runs and cannot make an outbound call at startup. Each `ChatModel` is built programmatically from the `providers` row. The API key is read from `secret.get` on every call and is not cached by `SpringAiLlm`.

4. **Provider kinds.** `openai_compatible` (any OpenAI-compatible base URL), `anthropic` and `ollama`. Connection test is on demand only; there is no default provider. Enhance is disabled with "Configure a provider" until one is saved.

5. **Security boundaries.**
   - `PUT /providers/{id}/key` is write-only.
   - Key values are never written to the database, WAL, logs, API responses or exception messages.
   - `AgentSecretStore` failure messages never carry the value.
   - Test keys use the `sk-test` prefix only.

## Consequences

- The core never stores a provider key longer than one HTTP call.
- Adding a new provider kind means a new Spring AI model library and a new builder branch in `SpringAiLlm`; no schema change is needed.
- Ollama uses HTTP/1.1 with explicit connect and call timeouts because the JDK client's cleartext HTTP/2 upgrade stalled against the local server during testing.
- If the agent is not running, `secret.get` returns an error and the enhancement call fails cleanly; the UI shows the provider configuration path.
