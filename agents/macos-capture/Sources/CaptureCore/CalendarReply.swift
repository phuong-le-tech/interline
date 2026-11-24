import Foundation

/// Bounds the complete wire envelope, including the kind byte, before a calendar reply reaches the transport.
public enum CalendarReply {
    public static func make(to request: ControlMessage, events: [[String: Any]], complete: Bool,
                            nowMS: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) throws -> ControlMessage {
        var reply = ControlMessage.response(to: request, type: "calendar.events", ["events": [], "complete": false])
        var bytes = try WireCodec.encodeControl(reply).count + 1
        var kept: [[String: Any]] = []
        var complete = complete
        let nearest = events.sorted {
            abs(Double(WireCodec.integer($0["starts_at"]) ?? 0) - Double(nowMS)) <
                abs(Double(WireCodec.integer($1["starts_at"]) ?? 0) - Double(nowMS))
        }
        for event in nearest {
            if kept.count == 5000 { complete = false; break }
            let size = try JSONSerialization.data(withJSONObject: event, options: [.withoutEscapingSlashes]).count
                + (kept.isEmpty ? 0 : 1)
            if bytes + size > WireCodec.maxControlLength { complete = false; continue }
            kept.append(event)
            bytes += size
        }
        reply.body = ["events": kept, "complete": complete]
        return reply
    }
}
