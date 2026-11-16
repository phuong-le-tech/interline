#!/usr/bin/env python3
"""Builds the 10 minute stereo session used by the latency run (stdlib only).

Left (mic) and right (system) each carry the first 24 s of an English fixture every 25 s, the right one 12 s later, so both
streams produce a steady flow of finals. Needs fixtures/speech/en-{1,2,3}.wav from gen-speech.sh.
Output: fixtures/speech/session-10min.wav (48 kHz stereo 16 bit, never committed).
"""
import array
import pathlib
import wave

FIXTURES = pathlib.Path(__file__).resolve().parents[2] / "fixtures" / "speech"
SESSION_SECONDS = 610
PERIOD_SECONDS = 25
CLIP_SECONDS = 24
MIC_OFFSET_SECONDS = 2
SYSTEM_OFFSET_SECONDS = 14
CLIPS = ["en-1", "en-2", "en-3"]


def read_mono(name: str) -> tuple[array.array, int]:
    with wave.open(str(FIXTURES / f"{name}.wav"), "rb") as source:
        assert source.getsampwidth() == 2 and source.getnchannels() == 2, "expected 16 bit stereo"
        samples = array.array("h")
        samples.frombytes(source.readframes(source.getnframes()))
        return samples[0::2], source.getframerate()


def place(track: array.array, clip: array.array, start: int, rate: int) -> None:
    clip = clip[: CLIP_SECONDS * rate]
    end = min(len(track), start + len(clip))
    track[start:end] = clip[: end - start]


def main() -> None:
    clips = {name: read_mono(name) for name in CLIPS}
    rate = clips[CLIPS[0]][1]
    assert all(r == rate for _, r in clips.values()), "fixtures must share one sample rate"
    total = SESSION_SECONDS * rate
    mic = array.array("h", bytes(total * 2))
    system = array.array("h", bytes(total * 2))
    for index in range(SESSION_SECONDS // PERIOD_SECONDS):
        place(mic, clips[CLIPS[index % 3]][0], (MIC_OFFSET_SECONDS + index * PERIOD_SECONDS) * rate, rate)
        place(system, clips[CLIPS[(index + 1) % 3]][0], (SYSTEM_OFFSET_SECONDS + index * PERIOD_SECONDS) * rate, rate)
    interleaved = array.array("h", bytes(total * 4))
    interleaved[0::2] = mic
    interleaved[1::2] = system
    target = FIXTURES / "session-10min.wav"
    with wave.open(str(target), "wb") as out:
        out.setnchannels(2)
        out.setsampwidth(2)
        out.setframerate(rate)
        out.writeframes(interleaved.tobytes())
    print(f"wrote {target} ({SESSION_SECONDS} s)")


if __name__ == "__main__":
    main()
