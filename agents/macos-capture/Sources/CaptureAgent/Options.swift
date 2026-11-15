import Foundation

/// Command line of `interline-capture-macos`. Unknown options are an error so a typo cannot silently
/// switch the agent from a fault or test mode to live capture.
struct AgentOptions {
    var protocolVersion = 1
    var logLevel = "info"
    /// Stereo WAV fed through the capture path instead of the devices (left = mic, right = system).
    var testInput: URL?
    var testSpeed = 1.0
    var simulateSilentSystem = false
    var bench = false
    var benchModel: URL?
    var benchInput: URL?

    enum ParseError: Error, CustomStringConvertible {
        case unknown(String)
        case invalid(String)
        var description: String {
            switch self {
            case .unknown(let option): return "unknown option \(option)"
            case .invalid(let detail): return detail
            }
        }
    }

    static func parse(_ arguments: [String]) throws -> AgentOptions {
        var options = AgentOptions()
        for argument in arguments {
            let parts = argument.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            let key = String(parts[0])
            let value = parts.count > 1 ? String(parts[1]) : ""
            switch key {
            case "--protocol":
                guard let version = Int(value) else { throw ParseError.invalid("--protocol needs an integer") }
                options.protocolVersion = version
            case "--log-level": options.logLevel = value
            case "--test-input": options.testInput = URL(fileURLWithPath: value)
            case "--test-speed":
                guard let speed = Double(value), speed > 0 else { throw ParseError.invalid("--test-speed must be positive") }
                options.testSpeed = speed
            case "--simulate-silent-system": options.simulateSilentSystem = true
            case "--bench": options.bench = true
            case "--model": options.benchModel = URL(fileURLWithPath: value)
            case "--input": options.benchInput = URL(fileURLWithPath: value)
            default: throw ParseError.unknown(argument)
            }
        }
        return options
    }
}

func log(_ message: String) {
    FileHandle.standardError.write(Data("interline-capture-macos: \(message)\n".utf8))
}
