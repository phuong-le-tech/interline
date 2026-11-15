import Foundation

/// Append-only WAV spool, one file per contiguous run of frames, see ARCHITECTURE 5.8:
/// `<stream>-<firstSeq>-<firstSample>.wav`, 16 kHz mono s16le, at most 60 s per file.
public final class SpoolStore {
    public static let wavHeaderBytes = 44
    static let maxFrames = 600
    /// Rewriting the header once a second bounds what a crash can leave stale.
    static let headerRefreshFrames = 10
    private static let frameBytes = AudioConstants.frameSamples * 2

    struct Segment {
        let path: URL
        let firstSeq: UInt32
        let firstSample: UInt64
        var frames: Int
    }

    private final class OpenFile {
        var segment: Segment
        let handle: FileHandle
        init(segment: Segment, handle: FileHandle) { self.segment = segment; self.handle = handle }
        var nextSeq: UInt32 { segment.firstSeq + UInt32(segment.frames) }
        var nextSample: UInt64 { segment.firstSample + UInt64(segment.frames * AudioConstants.frameSamples) }
    }

    public let directory: URL
    private let lock = NSLock()
    private var current: [UInt8: OpenFile] = [:]
    private var segments: [UInt8: [Segment]] = [:]
    private var resumed: Set<UInt8> = []

    public init(directory: URL) { self.directory = directory }

    // MARK: writing

    public func append(_ frame: AudioFrame) throws {
        try lock.withLock { try appendLocked(frame) }
    }

    /// First frame of a resumed session: always a new file, flagged when the timeline does not continue.
    public func resumeLive(_ frame: AudioFrame) throws -> AudioFrame {
        try lock.withLock {
            guard !resumed.contains(frame.stream) else { try appendLocked(frame); return frame }
            resumed.insert(frame.stream)
            let history = (try? Self.list(directory: directory, stream: frame.stream)) ?? []
            let contiguous = history.last.map { segment in
                let physical = Self.physicalFrames(of: segment.path)
                return segment.firstSeq + UInt32(physical) == frame.seq
                    && segment.firstSample + UInt64(physical * AudioConstants.frameSamples) == frame.firstSample
            } ?? false
            let flagged = contiguous ? frame : frame.with(flags: frame.flags | FrameFlags.discontinuityBefore)
            try openNewFile(for: flagged)
            try appendLocked(flagged)
            return flagged
        }
    }

    public func close() throws {
        try lock.withLock {
            for open in current.values { try finish(open) }
            current.removeAll()
        }
    }

    // MARK: reading back and retention

    /// Frame `seq` as it was spooled, or nil when it was already deleted or never written.
    func read(stream: UInt8, seq: UInt32) throws -> AudioFrame? {
        try lock.withLock {
            guard let segment = segments[stream]?.last(where: { $0.firstSeq <= seq && seq < $0.firstSeq + UInt32($0.frames) }) else { return nil }
            let handle = try FileHandle(forReadingFrom: segment.path)
            defer { try? handle.close() }
            let index = Int(seq - segment.firstSeq)
            try handle.seek(toOffset: UInt64(Self.wavHeaderBytes + index * Self.frameBytes))
            guard let data = try handle.read(upToCount: Self.frameBytes), data.count == Self.frameBytes else { return nil }
            return AudioFrame(stream: stream, flags: 0, seq: seq,
                              firstSample: segment.firstSample + UInt64(index * AudioConstants.frameSamples),
                              hostTimeNS: 0, pcm: Self.samples(of: data))
        }
    }

    /// Drops every file whose frames are all acknowledged.
    func release(stream: UInt8, upToSeq acked: UInt32) throws {
        try lock.withLock {
            var kept: [Segment] = []
            for segment in segments[stream] ?? [] {
                guard segment.frames > 0, segment.firstSeq + UInt32(segment.frames) - 1 <= acked else { kept.append(segment); continue }
                if let open = current[stream], open.segment.path == segment.path {
                    try open.handle.close()
                    current[stream] = nil
                }
                try FileManager.default.removeItem(at: segment.path)
            }
            segments[stream] = kept
        }
    }

    /// Every frame of the stream that sits in `directory`, from `nextSequence` on, flagged as recovered.
    /// Sequence and sample come from the file name and the physical PCM, never from the (possibly stale) header.
    public static func recover(directory: URL, stream: UInt8, nextSequence: UInt32) throws -> [AudioFrame] {
        var frames: [AudioFrame] = []
        for segment in try list(directory: directory, stream: stream) {
            let data = try Data(contentsOf: segment.path)
            guard data.count > wavHeaderBytes else { continue }
            let pcm = data.dropFirst(wavHeaderBytes)
            let count = pcm.count / frameBytes
            for index in 0..<count {
                let seq = segment.firstSeq + UInt32(index)
                guard seq >= nextSequence else { continue }
                let start = pcm.startIndex + index * frameBytes
                frames.append(AudioFrame(stream: stream, flags: FrameFlags.recovered, seq: seq,
                                         firstSample: segment.firstSample + UInt64(index * AudioConstants.frameSamples),
                                         hostTimeNS: 0, pcm: samples(of: pcm[start..<start + frameBytes])))
            }
        }
        return frames
    }

    // MARK: internals

    private func appendLocked(_ frame: AudioFrame) throws {
        if let open = current[frame.stream],
           open.nextSeq != frame.seq || open.nextSample != frame.firstSample || open.segment.frames >= Self.maxFrames {
            try finish(open)
            current[frame.stream] = nil
        }
        if current[frame.stream] == nil { try openNewFile(for: frame) }
        guard let open = current[frame.stream] else { return }
        var data = Data(capacity: frame.pcm.count * 2)
        for sample in frame.pcm { data.appendLittleEndian(sample) }
        try open.handle.seekToEnd()
        try open.handle.write(contentsOf: data)
        open.segment.frames += 1
        if var list = segments[frame.stream], !list.isEmpty {
            list[list.count - 1].frames = open.segment.frames
            segments[frame.stream] = list
        }
        if open.segment.frames % Self.headerRefreshFrames == 0 { try writeHeader(open) }
    }

    private func openNewFile(for frame: AudioFrame) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let name = "\(StreamID.name(frame.stream))-\(frame.seq)-\(frame.firstSample).wav"
        let path = directory.appendingPathComponent(name)
        guard FileManager.default.createFile(atPath: path.path, contents: Self.header(dataBytes: 0)) else {
            throw CocoaError(.fileWriteUnknown)
        }
        let open = OpenFile(segment: Segment(path: path, firstSeq: frame.seq, firstSample: frame.firstSample, frames: 0),
                            handle: try FileHandle(forWritingTo: path))
        current[frame.stream] = open
        segments[frame.stream, default: []].append(open.segment)
    }

    private func finish(_ open: OpenFile) throws {
        try writeHeader(open)
        try open.handle.close()
    }

    private func writeHeader(_ open: OpenFile) throws {
        let end = try open.handle.offset()
        try open.handle.seek(toOffset: 0)
        try open.handle.write(contentsOf: Self.header(dataBytes: UInt32(open.segment.frames * Self.frameBytes)))
        try open.handle.seek(toOffset: end)
    }

    static func header(dataBytes: UInt32) -> Data {
        var data = Data()
        data.append(contentsOf: Array("RIFF".utf8)); data.appendLittleEndian(36 + dataBytes)
        data.append(contentsOf: Array("WAVEfmt ".utf8)); data.appendLittleEndian(UInt32(16))
        data.appendLittleEndian(UInt16(1)); data.appendLittleEndian(UInt16(1))
        data.appendLittleEndian(UInt32(AudioConstants.sampleRate)); data.appendLittleEndian(UInt32(AudioConstants.sampleRate * 2))
        data.appendLittleEndian(UInt16(2)); data.appendLittleEndian(UInt16(16))
        data.append(contentsOf: Array("data".utf8)); data.appendLittleEndian(dataBytes)
        return data
    }

    private static func physicalFrames(of path: URL) -> Int {
        let size = (try? FileManager.default.attributesOfItem(atPath: path.path)[.size] as? Int) ?? 0
        return max(0, size - wavHeaderBytes) / frameBytes
    }

    private static func samples(of data: Data) -> [Int16] {
        let bytes = [UInt8](data)
        return stride(from: 0, to: bytes.count - 1, by: 2).map { Int16(bitPattern: UInt16(bytes[$0]) | UInt16(bytes[$0 + 1]) << 8) }
    }

    /// Spool files of one stream, ordered by first sequence, parsed from `<stream>-<seq>-<sample>.wav`.
    static func list(directory: URL, stream: UInt8) throws -> [Segment] {
        let prefix = StreamID.name(stream) + "-"
        let names = try FileManager.default.contentsOfDirectory(atPath: directory.path)
        return names.compactMap { name -> Segment? in
            guard name.hasPrefix(prefix), name.hasSuffix(".wav") else { return nil }
            let parts = name.dropFirst(prefix.count).dropLast(4).split(separator: "-")
            guard parts.count == 2, let seq = UInt32(parts[0]), let sample = UInt64(parts[1]) else { return nil }
            let path = directory.appendingPathComponent(name)
            return Segment(path: path, firstSeq: seq, firstSample: sample, frames: physicalFrames(of: path))
        }.sorted { $0.firstSeq < $1.firstSeq }
    }
}
