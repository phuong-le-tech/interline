import CoreGraphics
import Foundation

let pid = Int(CommandLine.arguments[1])!
let began = Double(CommandLine.arguments[2])!
let deadline = Date().addingTimeInterval(10)
while Date() < deadline {
    let windows = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID) as? [[String: Any]] ?? []
    if windows.contains(where: { ($0[kCGWindowOwnerPID as String] as? Int) == pid && ($0[kCGWindowLayer as String] as? Int) == 0 }) {
        print(String(format: "%.3f", Date().timeIntervalSince1970 - began))
        exit(0)
    }
    Thread.sleep(forTimeInterval: 0.01)
}
exit(1)
