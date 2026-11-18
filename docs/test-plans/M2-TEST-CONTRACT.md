# M2 test contract (written by the test-writer step)

The M2 tests fix the following shapes. The spec left them open; where the tests are strict, the code-writer implements exactly this. Nothing here changes a settled decision of the card.

## Run commands

| Layer | Command (from repo root) |
|---|---|
| core-persistence | `cd services/core && ./mvnw -B -pl core-persistence -am -Dmaven.test.failure.ignore=true test` (MigrationV3Test, NoteStoreTest) |
| core-domain | `./mvnw -B -pl core-domain test` (EnhancementValidatorTest, EnhancementParserTest, AlignmentTest) |
| core-ai | `./mvnw -B -pl core-ai -am -Dmaven.test.failure.ignore=true test` (EnhancementRunnerTest, FollowUpEmailRunnerTest, ConnectionTesterTest with WireMock, GoldenEvalTest) |
| protocol golden | `./mvnw -B -pl adapter-agent-protocol test -Dtest=M2GoldenTest` |
| agent conformance | `./mvnw -B -pl agent-conformance -am -Dtest=M2Test -Dsurefire.failIfNoSpecifiedTests=false verify` |
| app (@SpringBootTest, real core + MockAgent) | `./mvnw -B -pl app -am -Dtest='M2*Test' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.test.failure.ignore=true test` (needs `mock-agent` jar built: run `verify` once) |
| Swift | `cd agents/macos-capture && swift run CaptureSelfTest` (cases `AC19_keychain_*`) |
| Vitest | `cd apps/desktop && pnpm test` |
| Playwright | `cd apps/desktop && pnpm exec playwright test notes enhance palette` (needs `services/core/app/target/interline-core.jar`: `./mvnw -B package -DskipTests`) |

## Stub model

At the `@SpringBootTest` layer and in Playwright the "stub ChatModel" is a loopback OpenAI-compatible HTTP server (`FakeOpenAi` in Java, `FakeModel` in `e2e/support/notes-stack.ts`) behind a real `providers` row. The core must call `<base_url>/chat/completions` (so `base_url` = `http://127.0.0.1:port/v1`), read `choices[0].message.content` and `usage.prompt_tokens|completion_tokens`, and send `Authorization: Bearer <key>`. In `core-ai` unit tests the seam is `LlmPort.complete(LlmRequest) -> LlmReply(text, inputTokens, outputTokens)`.

## HTTP API under /api/v1

- `PUT /meetings/{id}/blocks/{blockId}` body `{client_rev, kind, position, content, plain_text, anchor_ms?}` -> 200 `{id, client_rev, applied}`; `applied=false` when `client_rev <=` stored; `client_rev` missing, non-numeric, 0 or negative -> 400; unknown meeting -> 404; `anchor_ms` is honoured on creation only.
- `GET /meetings/{id}/blocks` -> array ordered by `position`: `{id, kind, origin, parent_id?, position, content, plain_text, anchor_ms, client_rev, ai_state?, sources:[segmentId]}`.
- `DELETE /meetings/{id}/blocks/{blockId}`; `POST /meetings/{id}/blocks/{blockId}/accept` (user block -> 4xx). A `PUT` on an AI block sets `ai_state=edited`.
- `POST /meetings/{id}/enhance` body `{template_id?}` -> 200 `{run_id, status:"ok"|"invalid_output", message?}` (blocks until finished). No provider -> 409 problem with `code:"no_provider"`, detail containing `Configure a provider`. Unknown template -> 4xx and no model call. `invalid_output` message is exactly `The model did not respect the format`.
- `POST /meetings/{id}/enhance/undo`; `GET /meetings/{id}/actions` -> `[{id,text,owner,due_date?,status,source_segment_id}]`; `GET /meetings/{id}/ai-runs` -> `[{id,kind,provider_id,model,prompt_version,input_tokens,output_tokens,status,cost_estimate_usd?}]` (cost absent when no price); `POST /meetings/{id}/followup-email` -> `{run_id,status,subject,body,sources:[segmentId]}`; no send route.
- `GET /templates` -> `[{id,name,instructions,builtin}]` (4 built-ins: 1:1, client, interview, team meeting).
- Providers: `POST /providers` (`kind` openai_compatible|anthropic|ollama, `name`, `base_url`, `model`, optional `supports_json_schema`, `context_tokens`, `price_in_per_mtok`, `price_out_per_mtok`) -> 200 with `id`, `has_key`, `is_default` (first saved is default); incomplete or unknown kind -> 400; `GET /providers`, `GET|PUT|DELETE /providers/{id}`; `PUT /providers/{id}/key` body `{key}` -> 204; `DELETE /providers/{id}/key` -> 204; `POST /providers/{id}/test` -> 200 `{status:"ok"|"unauthorized"|"unreachable"}`. Views never carry `key`, `api_key`, `secret`, `secret_account`, `value`. Deleting a provider deletes its secret (`secret.delete`).
- `GET /search?q=` -> `{results:[{type:"block"|"segment", meeting_id, id, snippet}]}`; blank or missing q -> 400; suppressed segments are not returned.
- `GET /meetings/{id}/export?format=markdown&transcript=true|false` -> `text/markdown`, byte-equal to `fixtures/golden/meeting-1/export*.md` (heading `## `, bullet `- `, todo `- [ ] `, quote `> `, transcript lines `[mm:ss] Speaker: text`).

## core-ai / core-domain classes the tests call (skeletons committed)

`EnhancementParser.parse`, `EnhancementValidator(ValidationContext).validate -> ValidationResult(valid, violations)`, `ModeSelector.select(chars, contextTokens)`, `Alignment.windows(blocks)`, `EnhancementRunner(LlmPort).run(EnhanceInput) -> EnhanceResult`, `FollowUpEmailRunner`, `ConnectionTester.test(ProviderConfig, key)`, `GoldenEval.run(llm, dir)` (test scope). Prompt resources: `prompts/enhance/v1/{system,user,retry}.md`, `prompts/followup_email/v1/{system,user}.md`. Segment lines in prompts read `[S<id> mm:ss <speaker>] text`.

## UI contract used by Playwright and Vitest

See the header comment of `apps/desktop/e2e/notes.spec.ts`. Components: `src/notes/{CitationChip,EnhancedToggle,AiAddition}.tsx`, journal: `src/notes/journal.ts`. Palette: dialog named `Search`, combobox, options with `aria-selected`, text `No results`.

## Not covered by a test

Real providers, the 1 h latency target, the Tauri file dialog, AC21 (the local gate itself). `AC16` "no send route" and a few "unknown id -> 404" cases already pass on the skeleton and act as regression guards.
