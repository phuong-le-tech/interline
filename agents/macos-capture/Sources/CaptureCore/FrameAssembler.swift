/// Re-frames arbitrarily sized 16 kHz mono chunks into the protocol's 100 ms (1600 sample) frames.
public struct FrameAssembler {
    private var pending: [Int16] = []

    public init() {}

    public var pendingSamples: Int { pending.count }

    public mutating func push(_ samples: [Int16]) -> [[Int16]] {
        pending.append(contentsOf: samples)
        var frames: [[Int16]] = []
        var start = 0
        while pending.count - start >= AudioConstants.frameSamples {
            frames.append(Array(pending[start..<start + AudioConstants.frameSamples]))
            start += AudioConstants.frameSamples
        }
        if start > 0 { pending.removeFirst(start) }
        return frames
    }
}
