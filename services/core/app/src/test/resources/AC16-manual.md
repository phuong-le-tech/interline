# AC16_manual - macOS evidence checklist

This is a manual acceptance test, not fabricated execution evidence.

1. Build the core with JDK 21 and start `pnpm tauri dev` in `apps/desktop`.
2. Verify a native window opens.
3. Press Record. Verify both Me and Them transcript lines arrive before Stop.
4. Press Stop. Verify the meeting is done in the list.
5. Quit the native application.
6. Run `pgrep -fl interline` and `pgrep -fl java`. Distinguish unrelated processes by pid and command. Verify no process belonging to this launch remains.
7. Record actual commands, output and window evidence in the PR body.

The test-writer red step does not implement a Tauri shell and cannot pass this test. `RepositoryTest.AC16_manualShellSourceStaysBelowThreeHundredRustLines` checks only the source budget. Actual PR CI success for AC15 and manual evidence for AC16 remain downstream gates.
