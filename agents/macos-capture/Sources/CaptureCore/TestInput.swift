import Foundation

/// A stereo recording standing in for the devices: left = mic, right = system. It is decoded and brought to
/// 16 kHz up front, then served frame by frame through exactly the path live capture uses.
public struct TestInput {
    public let mic: [Int16]
    public let system: [Int16]

    /// Placeholder for sessions fed by the devices instead of a recording.
    public static let empty = TestInput(mic: [], system: [])

    public var frameCount: Int { (max(mic.count, system.count) + AudioConstants.frameSamples - 1) / AudioConstants.frameSamples }

    /// The 1600-sample frame at `index` of `stream`, zero padded past the end of the recording.
    public func samples(stream: UInt8, frame index: Int) -> [Int16] {
        let source = stream == StreamID.mic ? mic : system
        let start = index * AudioConstants.frameSamples
        guard start < source.count else { return [Int16](repeating: 0, count: AudioConstants.frameSamples) }
        let slice = Array(source[start..<min(start + AudioConstants.frameSamples, source.count)])
        return slice + [Int16](repeating: 0, count: AudioConstants.frameSamples - slice.count)
    }

    public func zeroingSystem() -> TestInput { TestInput(mic: mic, system: [Int16](repeating: 0, count: system.count)) }

    public static func load(_ url: URL) throws -> TestInput {
        let wav = try WavFile.read(url)
        let converter = PCMConverter()
        let channels = (0..<min(wav.channels, 2)).map { wav.channel($0) }
        let resampled = try channels.map { try converter.convert(interleaved: $0, sampleRate: wav.sampleRate, channels: 1) }
        return TestInput(mic: resampled[0], system: resampled.count > 1 ? resampled[1] : [Int16](repeating: 0, count: resampled[0].count))
    }
}

/// Just enough RIFF/WAVE: integer PCM 8/16/24/32 bit and 32-bit float, plain or extensible, any chunk order.
public struct WavFile {
    public let sampleRate: Int
    public let channels: Int
    let samples: [Float]

    func channel(_ index: Int) -> [Float] {
        stride(from: index, to: samples.count, by: channels).map { samples[$0] }
    }

    public var durationSamples16k: Int { Int((Double(samples.count / channels) * 16000 / Double(sampleRate)).rounded()) }

    /// All channels averaged into one.
    public func mixedToMono() -> [Float] {
        guard channels > 1 else { return samples }
        return stride(from: 0, to: samples.count, by: channels).map { start in
            (0..<channels).reduce(Float(0)) { $0 + samples[start + $1] } / Float(channels)
        }
    }

    public static func read(_ url: URL) throws -> WavFile {
        let data = try Data(contentsOf: url)
        let bytes = [UInt8](data)
        func fail(_ reason: String) -> ProtocolValidationError { ProtocolValidationError(reason: "\(url.lastPathComponent): \(reason)") }
        guard bytes.count >= 12, String(decoding: bytes[0..<4], as: UTF8.self) == "RIFF",
              String(decoding: bytes[8..<12], as: UTF8.self) == "WAVE" else { throw fail("not a RIFF/WAVE file") }
        var format: (tag: Int, channels: Int, rate: Int, bits: Int)?
        var offset = 12
        while offset + 8 <= bytes.count {
            let kind = String(decoding: bytes[offset..<offset + 4], as: UTF8.self)
            let declared = Int(le32(bytes, offset + 4))
            let start = offset + 8
            // A streamed or interrupted recording can declare more data than the file holds.
            let size = min(declared, bytes.count - start)
            if kind == "fmt " && size >= 16 {
                var tag = Int(le16(bytes, start))
                if tag == 0xFFFE && size >= 26 { tag = Int(le16(bytes, start + 24)) }
                format = (tag, Int(le16(bytes, start + 2)), Int(le32(bytes, start + 4)), Int(le16(bytes, start + 14)))
            } else if kind == "data" {
                guard let format, format.channels >= 1, format.rate > 0 else { throw fail("data chunk before fmt chunk") }
                let samples = try decode(bytes[start..<start + size], format: format, fail: fail)
                return WavFile(sampleRate: format.rate, channels: format.channels, samples: samples)
            }
            offset = start + size + (size & 1)
        }
        throw fail("no data chunk")
    }

    private static func decode(_ pcm: ArraySlice<UInt8>, format: (tag: Int, channels: Int, rate: Int, bits: Int),
                               fail: (String) -> ProtocolValidationError) throws -> [Float] {
        let width = format.bits / 8
        guard width >= 1, pcm.count >= 0 else { throw fail("unsupported sample width") }
        let count = pcm.count / (width * format.channels) * format.channels
        var out = [Float](repeating: 0, count: count)
        let base = pcm.startIndex
        for index in 0..<count {
            let at = base + index * width
            switch (format.tag, format.bits) {
            case (1, 8): out[index] = (Float(pcm[at]) - 128) / 128
            case (1, 16): out[index] = Float(Int16(bitPattern: UInt16(pcm[at]) | UInt16(pcm[at + 1]) << 8)) / 32768
            case (1, 24):
                let raw = Int32(pcm[at]) | Int32(pcm[at + 1]) << 8 | Int32(pcm[at + 2]) << 16
                out[index] = Float((raw << 8) >> 8) / 8_388_608
            case (1, 32):
                let raw = UInt32(pcm[at]) | UInt32(pcm[at + 1]) << 8 | UInt32(pcm[at + 2]) << 16 | UInt32(pcm[at + 3]) << 24
                out[index] = Float(Int32(bitPattern: raw)) / 2_147_483_648
            case (3, 32):
                let raw = UInt32(pcm[at]) | UInt32(pcm[at + 1]) << 8 | UInt32(pcm[at + 2]) << 16 | UInt32(pcm[at + 3]) << 24
                out[index] = Float(bitPattern: raw)
            default: throw fail("unsupported WAV encoding tag \(format.tag) with \(format.bits) bits")
            }
        }
        return out
    }

    private static func le16(_ bytes: [UInt8], _ at: Int) -> UInt16 { UInt16(bytes[at]) | UInt16(bytes[at + 1]) << 8 }
    private static func le32(_ bytes: [UInt8], _ at: Int) -> UInt32 {
        UInt32(bytes[at]) | UInt32(bytes[at + 1]) << 8 | UInt32(bytes[at + 2]) << 16 | UInt32(bytes[at + 3]) << 24
    }
}
