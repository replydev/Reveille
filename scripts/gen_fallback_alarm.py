"""Writes a 1-second looping 880 Hz beep pattern as 16-bit mono WAV."""
import math
import struct
import sys
import wave

RATE = 22050
frames = bytearray()
for i in range(RATE):
    t = i / RATE
    on = (t % 0.5) < 0.25
    value = int(32767 * 0.8 * math.sin(2 * math.pi * 880 * t)) if on else 0
    frames += struct.pack("<h", value)

with wave.open(sys.argv[1], "wb") as out:
    out.setnchannels(1)
    out.setsampwidth(2)
    out.setframerate(RATE)
    out.writeframes(bytes(frames))
