#!/usr/bin/env python3
"""Builds a stereo session with SHORT utterances on the system channel only (stdlib + macOS `say`).

Spike (c) of the human sitting plays one French `say` sentence of about 3 s on the speakers; this fixture reproduces
that shape headlessly so the speech pipeline can be checked for dropped short utterances.
Left (mic) is silent, right (system) holds the sentence UTTERANCES times, GAP_SECONDS apart.
Output: the path given as first argument (48 kHz stereo 16 bit, never committed).
"""
import array
import subprocess
import sys
import tempfile
import wave

SENTENCE = "le renard brun rapide saute par-dessus le chien paresseux"
VOICE = "Thomas"
RATE = 48000
UTTERANCES = 3
LEAD_SECONDS = 4
GAP_SECONDS = 6
TAIL_SECONDS = 8


def speak(path: str) -> array.array:
    subprocess.run(["say", "-v", VOICE, "-o", path, f"--data-format=LEI16@{RATE}", SENTENCE], check=True)
    with wave.open(path, "rb") as source:
        samples = array.array("h")
        samples.frombytes(source.readframes(source.getnframes()))
        return samples[:: source.getnchannels()]


def main(target: str) -> None:
    with tempfile.TemporaryDirectory() as scratch:
        clip = speak(f"{scratch}/clip.wav")
    clip_seconds = len(clip) / RATE
    total = int((LEAD_SECONDS + UTTERANCES * (clip_seconds + GAP_SECONDS) + TAIL_SECONDS) * RATE)
    system = array.array("h", bytes(total * 2))
    for index in range(UTTERANCES):
        start = int((LEAD_SECONDS + index * (clip_seconds + GAP_SECONDS)) * RATE)
        system[start : start + len(clip)] = clip
    interleaved = array.array("h", bytes(total * 4))
    interleaved[1::2] = system
    with wave.open(target, "wb") as out:
        out.setnchannels(2)
        out.setsampwidth(2)
        out.setframerate(RATE)
        out.writeframes(interleaved.tobytes())
    print(f"wrote {target}: {UTTERANCES} utterances of {clip_seconds:.1f} s on the system channel")


if __name__ == "__main__":
    main(sys.argv[1])
