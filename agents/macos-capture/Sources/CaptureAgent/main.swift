import Foundation
import Darwin
import CaptureCore

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
exit(Agent(options: options, makeTranscriber: { NullTranscriber() }).run())
