import CaptureCore
import EventKit
import Foundation

/// One store, used only on its serial queue. Status queries never prompt or read events.
public final class EventKitCalendar {
    public static let settingsURI = "x-apple.systempreferences:com.apple.preference.security?Privacy_Calendars"
    private let store = EKEventStore()
    private let queue = DispatchQueue(label: "agent.calendar")
    private var observer: NSObjectProtocol?
    private var changeGeneration = 0
    private var stopped = false

    public init(changed: @escaping () -> Void) {
        observer = NotificationCenter.default.addObserver(forName: .EKEventStoreChanged, object: store, queue: nil) { [weak self] _ in
            guard let self else { return }
            self.queue.async {
                self.changeGeneration += 1
                let generation = self.changeGeneration
                self.queue.asyncAfter(deadline: .now() + .milliseconds(500)) {
                    guard !self.stopped, self.changeGeneration == generation else { return }
                    changed()
                }
            }
        }
    }

    public static func permission() -> PermissionState {
        switch EKEventStore.authorizationStatus(for: .event) {
        case .fullAccess: return .granted
        case .denied, .writeOnly: return .denied
        case .restricted: return .restricted
        case .notDetermined: return .notDetermined
        @unknown default: return .unknown
        }
    }

    /// The completion stays off the protocol reader so a permission prompt cannot stall heartbeats.
    public func query(from: Int, to: Int, completion: @escaping ([[String: Any]], Bool, PermissionState, Bool) -> Void) {
        queue.async {
            let status = EKEventStore.authorizationStatus(for: .event)
            if status == .notDetermined || status == .writeOnly {
                self.store.requestFullAccessToEvents { _, error in
                    self.queue.async {
                        if error != nil && Self.permission() != .denied && Self.permission() != .restricted {
                            completion([], false, Self.permission(), true)
                        } else {
                            self.read(from: from, to: to, completion: completion)
                        }
                    }
                }
            } else {
                self.read(from: from, to: to, completion: completion)
            }
        }
    }

    public func request(completion: @escaping () -> Void) {
        queue.async { self.store.requestFullAccessToEvents { _, _ in completion() } }
    }

    private func read(from: Int, to: Int, completion: ([[String: Any]], Bool, PermissionState, Bool) -> Void) {
        guard !stopped else { return }
        let permission = Self.permission()
        guard permission == .granted else {
            completion([], true, permission, permission != .denied && permission != .restricted)
            return
        }
        let predicate = store.predicateForEvents(withStart: Date(timeIntervalSince1970: Double(from) / 1000),
                                                 end: Date(timeIntervalSince1970: Double(to) / 1000), calendars: nil)
        var events: [[String: Any]] = []
        var complete = true
        // EventKit expands recurrences within the predicate window; occurrenceDate keeps moved instances stable.
        for event in store.events(matching: predicate) {
            guard event.status != .canceled else { continue }
            guard let identifier = event.calendarItemIdentifier as String?, !identifier.isEmpty else {
                complete = false
                continue
            }
            let occurrence = event.occurrenceDate ?? event.startDate!
            let attendees = (event.attendees ?? []).map { attendee -> [String: String] in
                let address = attendee.url.absoluteString
                let email = address.lowercased().hasPrefix("mailto:") ? String(address.dropFirst(7)).removingPercentEncoding ?? "" : ""
                return ["name": attendee.name ?? "", "email": email]
            }
            events.append([
                "external_id": "\(identifier)#\(Int64(occurrence.timeIntervalSince1970 * 1000))",
                "title": event.title ?? "", "starts_at": Int64(event.startDate.timeIntervalSince1970 * 1000),
                "ends_at": Int64(event.endDate.timeIntervalSince1970 * 1000), "attendees": attendees,
                "conference_url": Self.conferenceURL(event) as Any? ?? NSNull(), "calendar_name": event.calendar.title ?? ""
            ])
        }
        completion(events, complete, permission, false)
    }

    private static func conferenceURL(_ event: EKEvent) -> String? {
        if let url = event.url, ["https", "http"].contains(url.scheme?.lowercased() ?? "") { return url.absoluteString }
        let text = [event.location, event.notes].compactMap { $0 }.joined(separator: "\n")
        guard let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) else { return nil }
        return detector.matches(in: text, range: NSRange(text.startIndex..., in: text)).compactMap(\.url)
            .first { ["https", "http"].contains($0.scheme?.lowercased() ?? "") }?.absoluteString
    }

    public func close() {
        if let observer { NotificationCenter.default.removeObserver(observer) }
        queue.sync { stopped = true; changeGeneration += 1 }
    }

    deinit { if let observer { NotificationCenter.default.removeObserver(observer) } }
}
