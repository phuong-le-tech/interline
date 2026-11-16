import AVFoundation
import CaptureCore

/// Brings device audio of any format to the protocol format (16 kHz mono s16le, 100 ms frames).
/// Streaming: one converter per source, so filter state carries across buffers.
final class StreamNormalizer {
    private static let slack: AVAudioFrameCount = 64
    private let converter: AVAudioConverter
    private let target: AVAudioFormat
    private let ratio: Double
    private var assembler = FrameAssembler()

    init?(input: AVAudioFormat) {
        guard let target = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: Double(AudioConstants.sampleRate),
                                         channels: 1, interleaved: true),
              let converter = AVAudioConverter(from: input, to: target) else { return nil }
        self.target = target
        self.converter = converter
        self.ratio = target.sampleRate / input.sampleRate
    }

    /// Complete 100 ms frames made available by this buffer; the remainder waits for the next one.
    func push(_ buffer: AVAudioPCMBuffer) -> [[Int16]] {
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + Self.slack
        guard let output = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: capacity) else { return [] }
        var supplied = false
        var failure: NSError?
        let status = converter.convert(to: output, error: &failure) { _, outStatus in
            if supplied {
                outStatus.pointee = .noDataNow
                return nil
            }
            supplied = true
            outStatus.pointee = .haveData
            return buffer
        }
        guard status != .error, failure == nil, let channel = output.int16ChannelData else { return [] }
        return assembler.push(Array(UnsafeBufferPointer(start: channel[0], count: Int(output.frameLength))))
    }
}
