import Foundation
import Darwin
import CaptureCore
import WhisperSTT

// Nothing but protocol frames may reach stdout, and a vanished core must not kill us with SIGPIPE
// before the spool is finalised.
signal(SIGPIPE, SIG_IGN)

let options: AgentOptions
do {
    options = try AgentOptions.parse(Array(CommandLine.arguments.dropFirst()))
} catch {
    log("\(error)")
    exit(ExitCode.badArguments)
}
let transcriber = WhisperTranscriber()
if options.bench { exit(Bench.run(options, transcriber: transcriber)) }
// EventKit posts changes on the main queue; keep it running while stdio blocks on the worker.
DispatchQueue.global(qos: .userInitiated).async {
    exit(Agent(options: options, transcriber: transcriber).run())
}
dispatchMain()
