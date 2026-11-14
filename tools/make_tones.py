"""Writes the three bundled reminder tones as tiny Standard MIDI files (FEATURES.md F-014).

They are composed here, note by note, so they are FastKiwi's own work with no third-party licence.
Android plays MIDI with its built-in synthesiser. Usage: python tools/make_tones.py app/src/main/res/raw
"""
import os
import struct
import sys


def var_len(n):
    out = [n & 0x7F]
    n >>= 7
    while n:
        out.insert(0, (n & 0x7F) | 0x80)
        n >>= 7
    return bytes(out)


def midi(program, notes, tempo_us=500000, ticks=480):
    """notes: list of (start_beat, pitch, length_beats, velocity)."""
    events = []
    for start, pitch, length, vel in notes:
        events.append((int(start * ticks), 0x90, pitch, vel))
        events.append((int((start + length) * ticks), 0x80, pitch, 0))
    events.sort(key=lambda e: (e[0], e[1] == 0x90))
    track = bytearray()
    track += var_len(0) + b"\xFF\x51\x03" + struct.pack(">I", tempo_us)[1:]
    track += var_len(0) + bytes([0xC0, program])
    now = 0
    for t, kind, pitch, vel in events:
        track += var_len(t - now) + bytes([kind, pitch, vel])
        now = t
    track += var_len(ticks // 2) + b"\xFF\x2F\x00"
    header = b"MThd" + struct.pack(">IHHH", 6, 0, 1, ticks)
    return header + b"MTrk" + struct.pack(">I", len(track)) + bytes(track)


TONES = {
    # A rising celesta arpeggio: light, for everyday reminders.
    "chime_1.mid": midi(8, [(0, 72, 0.5, 96), (0.5, 76, 0.5, 96), (1, 79, 0.5, 100), (1.5, 84, 1.5, 104)]),
    # Two tubular-bell strokes: clear, for important reminders.
    "chime_2.mid": midi(14, [(0, 79, 1.5, 110), (1.5, 72, 2, 110)]),
    # A short marimba figure.
    "chime_3.mid": midi(12, [(0, 76, 0.25, 100), (0.25, 79, 0.25, 100), (0.5, 76, 0.25, 100), (0.75, 84, 1, 110)]),
}

if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/res/raw"
    os.makedirs(out, exist_ok=True)
    for name, data in TONES.items():
        with open(os.path.join(out, name), "wb") as f:
            f.write(data)
        print(name, len(data), "bytes")
