import Foundation

/// Downmixes interleaved float PCM to mono and resamples it to 16 kHz s16le with a windowed-sinc low-pass.
/// It works on a whole buffer, so the output length is exactly `round(frames * 16000 / rate)`; the live device
/// path streams through AVAudioConverter instead (CaptureDevices) and re-frames to 100 ms itself.
public final class PCMConverter {
    /// Zero crossings of the sinc kernel on each side of the output position.
    private static let kernelLobes = 16
    /// Keep the pass band clear of the output Nyquist so the transition band does not alias.
    private static let cutoffRatio = 0.94

    public init() {}

    public func convert(interleaved: [Float], sampleRate: Int, channels: Int) throws -> [Int16] {
        guard sampleRate > 0, channels > 0, interleaved.count % channels == 0 else {
            throw ProtocolValidationError(reason: "invalid PCM layout")
        }
        let frames = interleaved.count / channels
        var mono = [Float](repeating: 0, count: frames)
        let gain = 1 / Float(channels)
        for frame in 0..<frames {
            var sum: Float = 0
            for channel in 0..<channels { sum += interleaved[frame * channels + channel] }
            mono[frame] = sum * gain
        }
        return resample(mono, from: sampleRate)
    }

    func resample(_ mono: [Float], from sampleRate: Int) -> [Int16] {
        let target = AudioConstants.sampleRate
        if sampleRate == target { return mono.map(Self.quantise) }
        let divisor = Self.gcd(sampleRate, target)
        let step = sampleRate / divisor          // input samples per `phases` output samples
        let phases = target / divisor
        let outputCount = Int((Double(mono.count) * Double(target) / Double(sampleRate)).rounded())
        let cutoff = min(1.0, Double(target) / Double(sampleRate)) * Self.cutoffRatio
        let halfWidth = Int((Double(Self.kernelLobes) / cutoff).rounded(.up))
        let kernels = (0..<phases).map { Self.kernel(fraction: Double($0) / Double(phases), halfWidth: halfWidth, cutoff: cutoff) }
        var output = [Int16](repeating: 0, count: outputCount)
        mono.withUnsafeBufferPointer { input in
            for index in 0..<outputCount {
                let scaled = index * step
                let center = scaled / phases
                let weights = kernels[scaled % phases]
                var sum: Float = 0
                for tap in 0..<weights.count {
                    let position = center - halfWidth + 1 + tap
                    if position >= 0 && position < input.count { sum += input[position] * weights[tap] }
                }
                output[index] = Self.quantise(sum)
            }
        }
        return output
    }

    /// Hann-windowed sinc for an output sample sitting `fraction` of an input sample after input position `center`.
    private static func kernel(fraction: Double, halfWidth: Int, cutoff: Double) -> [Float] {
        var weights = (0..<(2 * halfWidth)).map { tap -> Double in
            let distance = Double(tap - halfWidth + 1) - fraction
            let window = 0.5 * (1 + cos(Double.pi * distance / Double(halfWidth)))
            return abs(distance) >= Double(halfWidth) ? 0 : sinc(cutoff * distance) * cutoff * window
        }
        let total = weights.reduce(0, +)
        if total != 0 { weights = weights.map { $0 / total } }
        return weights.map { Float($0) }
    }

    private static func sinc(_ x: Double) -> Double { x == 0 ? 1 : sin(Double.pi * x) / (Double.pi * x) }

    private static func quantise(_ value: Float) -> Int16 {
        Int16(max(-32768, min(32767, (value * 32767).rounded())))
    }

    private static func gcd(_ a: Int, _ b: Int) -> Int { b == 0 ? a : gcd(b, a % b) }
}
