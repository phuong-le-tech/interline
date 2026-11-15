# M1 RED execution results

Generated from actual JUnit XML and Swift selftest output. Not a green feature claim.

## Java

| Module | Runner | Suite | Tests | Failures | Errors | Skipped |
|---|---|---|---:|---:|---:|---:|
| adapter-agent-protocol | surefire-reports | app.interline.agent.CodecTest | 17 | 0 | 0 | 0 |
| adapter-agent-protocol | surefire-reports | app.interline.agent.GoldenTest | 19 | 0 | 0 | 0 |
| adapter-agent-protocol | surefire-reports | app.interline.agent.M1GoldenTest | 9 | 7 | 0 | 0 |
| adapter-agent-protocol | surefire-reports | app.interline.agent.WatchdogTest | 6 | 0 | 0 | 0 |
| agent-conformance | surefire-reports | app.interline.conformance.M1Test | 35 | 12 | 0 | 0 |
| app | failsafe-reports | app.interline.agent.HandshakeTest | 2 | 0 | 0 | 0 |
| app | failsafe-reports | app.interline.app.BuildTest | 1 | 0 | 0 | 0 |
| app | failsafe-reports | app.interline.app.LifecycleTest | 4 | 0 | 0 | 0 |
| app | surefire-reports | app.interline.app.ArchitectureTest | 3 | 0 | 0 | 0 |
| app | surefire-reports | app.interline.app.DedupTest | 3 | 0 | 0 | 0 |
| app | surefire-reports | app.interline.app.M1Test | 25 | 16 | 0 | 2 |
| app | surefire-reports | app.interline.app.PipelineTest | 5 | 0 | 0 | 0 |
| app | surefire-reports | app.interline.app.RepositoryTest | 4 | 0 | 0 | 0 |
| app | surefire-reports | app.interline.app.SecurityTest | 100 | 0 | 0 | 0 |
| app | surefire-reports | app.interline.app.WorkflowTest | 2 | 0 | 0 | 0 |
| core-persistence | surefire-reports | app.interline.persistence.MigrationTest | 63 | 0 | 0 | 0 |

Totals (actual runner executions, including packaged-contract reruns): 298 tests, 35 failures, 0 errors, 2 skipped.

Every recorded Java failure belongs to a new M1 suite.

Existing M0 runner executions: 229, all passing.

New Java M1 invocations: 69, 35 assertion failures, 2 native opt-in skips.

### One line per failed Java invocation

- `app.interline.agent.M1GoldenTest.AC3_goldensEveryMessageValidatesAndDecodesWithoutLosingFields(String)[2]`: [Required m1 protocol corpus]
- `app.interline.agent.M1GoldenTest.AC3_goldensM1CorpusCoversEveryAdditiveMessage`: [Required m1 protocol corpus]
- `app.interline.agent.M1GoldenTest.AC3_goldensRejectMissingRequiredBodyAndIncorrectType(String)[1]`: [Required m1 protocol corpus]
- `app.interline.agent.M1GoldenTest.AC3_goldensRejectMissingRequiredBodyAndIncorrectType(String)[2]`: [Required m1 protocol corpus]
- `app.interline.agent.M1GoldenTest.AC3_goldensRejectMissingRequiredBodyAndIncorrectType(String)[3]`: [Required m1 protocol corpus]
- `app.interline.agent.M1GoldenTest.AC3_goldensRejectMissingRequiredBodyAndIncorrectType(String)[4]`: [Required m1 protocol corpus]
- `app.interline.agent.M1GoldenTest.AC3_goldensRejectMissingRequiredBodyAndIncorrectType(String)[5]`: [Required m1 protocol corpus]
- `app.interline.conformance.M1Test.AC4_contract_windowBoundsAndReopensWithoutLoss(int)[1]`: [exactly the negotiated unacked window per stream]
- `app.interline.conformance.M1Test.AC4_contract_levelsAreTenHzPerStreamAndFiniteRms`: [10 Hz level events for mic over 2.2 s]
- `app.interline.conformance.M1Test.AC4_contract_transcribeFileEmitsCorrelatedFinalsWithoutLiveAudio(int)[1]`: [file import must transcribe, not return unsupported_message]
- `app.interline.conformance.M1Test.AC4_contract_transcribeFileEmitsCorrelatedFinalsWithoutLiveAudio(int)[2]`: [file import must transcribe, not return unsupported_message]
- `app.interline.conformance.M1Test.AC4_contract_transcribeFileEmitsCorrelatedFinalsWithoutLiveAudio(int)[3]`: [file import must transcribe, not return unsupported_message]
- `app.interline.conformance.M1Test.AC4_contract_transcribeFileEmitsCorrelatedFinalsWithoutLiveAudio(int)[4]`: [file import must transcribe, not return unsupported_message]
- `app.interline.conformance.M1Test.AC4_contract_transcribeFileEmitsCorrelatedFinalsWithoutLiveAudio(int)[5]`: [file import must transcribe, not return unsupported_message]
- `app.interline.conformance.M1Test.AC4_contract_transcribeFileEmitsCorrelatedFinalsWithoutLiveAudio(int)[6]`: [file import must transcribe, not return unsupported_message]
- `app.interline.conformance.M1Test.AC4_contract_permissionStatusReturnsBothStatesWithoutPromptAndStaysAlive(String)[1]`: expected: "permission.status"
- `app.interline.conformance.M1Test.AC4_contract_permissionStatusReturnsBothStatesWithoutPromptAndStaysAlive(String)[2]`: expected: "permission.request"
- `app.interline.conformance.M1Test.AC4_contract_mockDeviceChangeIsAnObservableSessionEvent`: [agent must emit a protocol frame, not close stdout; stderr: mock-agent: unknown option --device-change-at=1s
- `app.interline.conformance.M1Test.AC4_contract_partialsHaveIncreasingRevisionsAndAreReplacedByMatchingFinal`: [partials requested explicitly, not silently skipped on missing capability]
- `app.interline.app.M1Test.AC9_publicTranscriptHidesPersistedSuppressedRowsWithoutDeletingThem`: Expecting actual:
- `app.interline.app.M1Test.AC5_restartAfterRealSigkillReplaysFramesAndPersistsEveryOutsideGapUtteranceOnce`: [Capture restart gap target]
- `app.interline.app.M1Test.AC6_silentSystemSurfacesPermissionBannerEventWithinTenSeconds`: [Required integration behavior within 10 seconds]
- `app.interline.app.M1Test.AC7_downloadResumesRangeAndOnlyRenamesAfterSha256Verification`: [Real downloader must resume a user-requested local HTTP download]
- `app.interline.app.M1Test.AC7_checksumMismatchDeletesPartAndNeverPublishesFinalModel`: [A checksum failure, not a missing implementation]
- `app.interline.app.M1Test.AC10_startupImportsStaleHeaderSpoolAcrossHoleExactlyOnce(String)[1]`: [Startup imports orphan spool payload despite its stale RIFF length]
- `app.interline.app.M1Test.AC10_startupImportsStaleHeaderSpoolAcrossHoleExactlyOnce(String)[2]`: [Startup imports orphan spool payload despite its stale RIFF length]
- `app.interline.app.M1Test.AC7_explicitDownloadEndpointIsTheOnlyUserInitiatedFetchPath`: [Explicit model-download route must exist: {"detail":"No static resource api/v1/models/tiny/download.","instance":"/api/v1/models/tiny/download","status":404,"title":"Not Found"}]
- `app.interline.app.M1Test.AC10_recoveryQueuesUntilNextAgentThenPersistsFinalsWithoutDuplicates(String)[1]`: [Startup marks abandoned meetings recovered without requiring an agent]
- `app.interline.app.M1Test.AC10_recoveryQueuesUntilNextAgentThenPersistsFinalsWithoutDuplicates(String)[2]`: [Startup marks abandoned meetings recovered without requiring an agent]
- `app.interline.app.M1Test.AC9_echoRetainsSqliteRowsButHidesOnlySuppressedMicFinals(boolean, int, int, int, boolean, String)[2]`: Multiple Failures (2 failures)
- `app.interline.app.M1Test.AC9_echoRetainsSqliteRowsButHidesOnlySuppressedMicFinals(boolean, int, int, int, boolean, String)[3]`: Multiple Failures (2 failures)
- `app.interline.app.M1Test.AC9_echoRetainsSqliteRowsButHidesOnlySuppressedMicFinals(boolean, int, int, int, boolean, String)[4]`: Multiple Failures (2 failures)
- `app.interline.app.M1Test.AC9_echoRetainsSqliteRowsButHidesOnlySuppressedMicFinals(boolean, int, int, int, boolean, String)[8]`: Multiple Failures (2 failures)
- `app.interline.app.M1Test.AC9_echoRetainsSqliteRowsButHidesOnlySuppressedMicFinals(boolean, int, int, int, boolean, String)[9]`: Multiple Failures (2 failures)
- `app.interline.app.M1Test.AC7_interruptedHttpBodyLeavesPartThenResumesExactOffset`: [A real truncated HTTP body must surface an IO error]

## Swift

CaptureSelfTest: 198 cases, 197 failures

- FAIL AC2_codec_binary_golden_roundtrip: not implemented: WireCodec.decodeAudio
- FAIL AC2_codec_reject_truncated_wire_0: not implemented: WireCodec.decodeAudio
- FAIL AC2_codec_reject_truncated_wire_1: not implemented: WireCodec.decodeAudio
- FAIL AC2_codec_reject_truncated_wire_2: not implemented: WireCodec.decodeAudio
- FAIL AC2_codec_reject_truncated_wire_3: not implemented: WireCodec.decodeAudio
- FAIL AC2_codec_reject_truncated_wire_4: not implemented: WireCodec.decodeAudio
- FAIL AC2_codec_reject_truncated_wire_5: not implemented: WireCodec.decodeAudio
- FAIL AC2_codec_reject_truncated_wire_28: not implemented: WireCodec.decodeAudio
- FAIL AC2_codec_reject_truncated_wire_29: not implemented: WireCodec.decodeAudio
- FAIL AC2_codec_reject_truncated_wire_36: not implemented: WireCodec.decodeAudio
- FAIL AC2_codec_largest_even_audio_payload_roundtrip: not implemented: WireCodec.encodeAudio
- FAIL AC2_codec_reject_audio_over_limit_encode: not implemented: WireCodec.encodeAudio
- FAIL AC2_codec_reject_zero_length: not implemented: WireCodec.decodeAudio
- FAIL AC2_codec_reject_unknown_kind: not implemented: WireCodec.decodeAudio
- FAIL AC2_codec_reject_wrong_control_kind: not implemented: WireCodec.decodeAudio
- FAIL AC2_codec_reject_short_header: not implemented: WireCodec.decodeAudio
- FAIL AC2_codec_reject_odd_pcm: not implemented: WireCodec.decodeAudio
- FAIL AC2_codec_reject_over_limit: not implemented: WireCodec.decodeAudio
- FAIL AC2_codec_reject_hostile_length: not implemented: WireCodec.decodeAudio
- FAIL AC2_codec_reject_invalid_stream: not implemented: WireCodec.decodeAudio
- FAIL AC2_codec_reject_invalid_flags: not implemented: WireCodec.decodeAudio
- FAIL AC2_codec_reject_invalid_reserved: not implemented: WireCodec.decodeAudio
- FAIL AC2_resample_44100_stereo_to_16k_mono_100ms: not implemented: PCMConverter.convert
- FAIL AC2_spool_next_sequence_filters_without_renumbering: not implemented: SpoolStore.recover
- FAIL AC2_resample_48k_stereo_to_16k_mono_100ms: not implemented: PCMConverter.convert
- FAIL AC2_spool_stale_header_uses_physical_pcm_length: not implemented: SpoolStore.recover
- FAIL AC2_spool_names_rotate_exactly_at_60s: not implemented: SpoolStore.append
- FAIL AC2_spool_recovery_then_live_discontinuity_preserves_timeline: not implemented: SpoolStore.recover
- FAIL AC2_window_overflow_spools_and_replays_in_order: not implemented: DeliveryWindow.offer
- FAIL AC2_window_1_bounded_replay_duplicate_stale_acks: not implemented: DeliveryWindow.offer
- FAIL AC2_window_50_bounded_replay_duplicate_stale_acks: not implemented: DeliveryWindow.offer
- FAIL AC2_window_zero_ack_rejected_without_mutation: not implemented: DeliveryWindow.offer
- FAIL AC2_ack_stream_isolation_and_spool_retention: not implemented: DeliveryWindow.offer
- FAIL AC2_backpressure_warns_only_above_60s_without_loss: not implemented: DeliveryWindow.offer
- FAIL AC2_silent_detector_exact_5s_once: not implemented: SilentTapDetector.observe
- FAIL AC2_silent_detector_requires_exact_consecutive_zeros_and_output: not implemented: SilentTapDetector.observe
- FAIL AC2_chunk_policy_elapsed_4999_silence_500: not implemented: ChunkPolicy.init
- FAIL AC2_chunk_policy_elapsed_5000_silence_499: not implemented: ChunkPolicy.init
- FAIL AC2_chunk_policy_elapsed_5000_silence_500: not implemented: ChunkPolicy.init
- FAIL AC2_chunk_policy_elapsed_14999_silence_0: not implemented: ChunkPolicy.init
- FAIL AC2_chunk_policy_elapsed_15000_silence_500: not implemented: ChunkPolicy.init
- FAIL AC2_chunk_policy_elapsed_15001_silence_0: not implemented: ChunkPolicy.init
- FAIL AC2_chunk_policy_elapsed_29999_silence_0: not implemented: ChunkPolicy.init
- FAIL AC2_chunk_policy_elapsed_30000_silence_0: not implemented: ChunkPolicy.init
- FAIL AC2_chunk_policy_elapsed_30001_silence_0: not implemented: ChunkPolicy.init
- FAIL AC2_chunk_policy_overlap_500ms: not implemented: ChunkPolicy.init
- FAIL AC2_chunk_policy_reject_negative_silence: not implemented: ChunkPolicy.init
- FAIL AC2_chunk_policy_reject_zero_target: not implemented: ChunkPolicy.init
- FAIL AC2_chunk_policy_reject_negative_target: not implemented: ChunkPolicy.init
- FAIL AC2_chunk_policy_reject_max_below_target: not implemented: ChunkPolicy.init
- FAIL AC2_chunk_policy_reject_zero_max: not implemented: ChunkPolicy.init
- FAIL AC2_chunk_policy_reject_negative_overlap: not implemented: ChunkPolicy.init
- FAIL AC2_chunk_policy_reject_overlap_equals_min_chunk: not implemented: ChunkPolicy.init
- FAIL AC2_chunk_policy_reject_overlap_exceeds_min_chunk: not implemented: ChunkPolicy.init
- FAIL AC3_m1.ndjson_corpus_present_nonempty: missing shared golden m1.ndjson
- FAIL AC3_decode_m0.ndjson_1_hello: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_v_m0.ndjson_1: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_v_m0.ndjson_1: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_t_m0.ndjson_1: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_t_m0.ndjson_1: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_ts_m0.ndjson_1: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_ts_m0.ndjson_1: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_body_m0.ndjson_1: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_body_m0.ndjson_1: not implemented: WireCodec.decodeControl
- FAIL AC3_decode_m0.ndjson_2_hello.ack: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_v_m0.ndjson_2: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_v_m0.ndjson_2: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_t_m0.ndjson_2: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_t_m0.ndjson_2: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_ts_m0.ndjson_2: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_ts_m0.ndjson_2: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_body_m0.ndjson_2: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_body_m0.ndjson_2: not implemented: WireCodec.decodeControl
- FAIL AC3_decode_m0.ndjson_3_hello.reject: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_v_m0.ndjson_3: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_v_m0.ndjson_3: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_t_m0.ndjson_3: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_t_m0.ndjson_3: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_ts_m0.ndjson_3: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_ts_m0.ndjson_3: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_body_m0.ndjson_3: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_body_m0.ndjson_3: not implemented: WireCodec.decodeControl
- FAIL AC3_decode_m0.ndjson_4_heartbeat: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_v_m0.ndjson_4: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_v_m0.ndjson_4: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_t_m0.ndjson_4: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_t_m0.ndjson_4: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_ts_m0.ndjson_4: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_ts_m0.ndjson_4: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_body_m0.ndjson_4: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_body_m0.ndjson_4: not implemented: WireCodec.decodeControl
- FAIL AC3_decode_m0.ndjson_5_session.start: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_v_m0.ndjson_5: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_v_m0.ndjson_5: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_t_m0.ndjson_5: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_t_m0.ndjson_5: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_ts_m0.ndjson_5: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_ts_m0.ndjson_5: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_body_m0.ndjson_5: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_body_m0.ndjson_5: not implemented: WireCodec.decodeControl
- FAIL AC3_decode_m0.ndjson_6_session.started: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_v_m0.ndjson_6: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_v_m0.ndjson_6: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_t_m0.ndjson_6: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_t_m0.ndjson_6: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_ts_m0.ndjson_6: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_ts_m0.ndjson_6: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_body_m0.ndjson_6: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_body_m0.ndjson_6: not implemented: WireCodec.decodeControl
- FAIL AC3_decode_m0.ndjson_7_session.stop: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_v_m0.ndjson_7: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_v_m0.ndjson_7: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_t_m0.ndjson_7: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_t_m0.ndjson_7: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_ts_m0.ndjson_7: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_ts_m0.ndjson_7: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_body_m0.ndjson_7: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_body_m0.ndjson_7: not implemented: WireCodec.decodeControl
- FAIL AC3_decode_m0.ndjson_8_session.stopped: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_v_m0.ndjson_8: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_v_m0.ndjson_8: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_t_m0.ndjson_8: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_t_m0.ndjson_8: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_ts_m0.ndjson_8: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_ts_m0.ndjson_8: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_body_m0.ndjson_8: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_body_m0.ndjson_8: not implemented: WireCodec.decodeControl
- FAIL AC3_decode_m0.ndjson_9_audio.chunk: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_v_m0.ndjson_9: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_v_m0.ndjson_9: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_t_m0.ndjson_9: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_t_m0.ndjson_9: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_ts_m0.ndjson_9: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_ts_m0.ndjson_9: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_body_m0.ndjson_9: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_body_m0.ndjson_9: not implemented: WireCodec.decodeControl
- FAIL AC3_decode_m0.ndjson_10_audio.ack: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_v_m0.ndjson_10: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_v_m0.ndjson_10: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_t_m0.ndjson_10: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_t_m0.ndjson_10: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_ts_m0.ndjson_10: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_ts_m0.ndjson_10: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_body_m0.ndjson_10: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_body_m0.ndjson_10: not implemented: WireCodec.decodeControl
- FAIL AC3_decode_m0.ndjson_11_vad.segment: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_v_m0.ndjson_11: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_v_m0.ndjson_11: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_t_m0.ndjson_11: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_t_m0.ndjson_11: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_ts_m0.ndjson_11: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_ts_m0.ndjson_11: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_body_m0.ndjson_11: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_body_m0.ndjson_11: not implemented: WireCodec.decodeControl
- FAIL AC3_decode_m0.ndjson_12_stt.final: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_v_m0.ndjson_12: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_v_m0.ndjson_12: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_t_m0.ndjson_12: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_t_m0.ndjson_12: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_ts_m0.ndjson_12: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_ts_m0.ndjson_12: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_body_m0.ndjson_12: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_body_m0.ndjson_12: not implemented: WireCodec.decodeControl
- FAIL AC3_decode_m0.ndjson_13_error: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_v_m0.ndjson_13: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_v_m0.ndjson_13: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_t_m0.ndjson_13: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_t_m0.ndjson_13: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_ts_m0.ndjson_13: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_ts_m0.ndjson_13: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_body_m0.ndjson_13: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_body_m0.ndjson_13: not implemented: WireCodec.decodeControl
- FAIL AC3_decode_m0.ndjson_14_shutdown: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_v_m0.ndjson_14: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_v_m0.ndjson_14: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_t_m0.ndjson_14: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_t_m0.ndjson_14: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_ts_m0.ndjson_14: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_ts_m0.ndjson_14: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_body_m0.ndjson_14: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_body_m0.ndjson_14: not implemented: WireCodec.decodeControl
- FAIL AC3_decode_m0.ndjson_15_shutdown.ok: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_v_m0.ndjson_15: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_v_m0.ndjson_15: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_t_m0.ndjson_15: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_t_m0.ndjson_15: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_ts_m0.ndjson_15: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_ts_m0.ndjson_15: not implemented: WireCodec.decodeControl
- FAIL AC3_missing_body_m0.ndjson_15: not implemented: WireCodec.decodeControl
- FAIL AC3_invalid_type_body_m0.ndjson_15: not implemented: WireCodec.decodeControl
- FAIL AC2_codec_reject_trailing_bytes: not implemented: WireCodec.decodeAudio
- FAIL AC3_control_reject_truncated_json: not implemented: WireCodec.decodeControl
- FAIL AC3_control_reject_invalid_utf8: not implemented: WireCodec.decodeControl
- FAIL AC3_control_reject_over_1MiB: not implemented: WireCodec.decodeControl
- FAIL AC3_reject_missing_required_audio_ack_seq: not implemented: WireCodec.decodeControl
- FAIL AC3_reject_invalid_body_audio_ack_seq_type: not implemented: WireCodec.decodeControl
- FAIL AC3_unknown_request_reaches_dispatch_for_unsupported_response: not implemented: WireCodec.decodeControl

## Browser and evidence scripts

See M1-RED-HANDOFF.md for the separately executed browser suite, native profile and evidence-script results.
