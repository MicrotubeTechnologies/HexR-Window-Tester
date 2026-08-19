"""Byte-level tests for the HEXR wire protocol.

The reference frames are taken from the firmware's own parser and from the
shipped Unity plugin, so these pin the layout against both. If one of these
fails, the glove is being sent something it will not understand — that is a
much cheaper thing to discover here than with hardware in your hand.

Run: python -m pytest app/tests -q      (or: python app/tests/test_protocol.py)
"""

from __future__ import annotations

import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from hexr import protocol as P  # noqa: E402


def _hex(b: bytes) -> str:
    return " ".join(f"{x:02x}" for x in b)


# -- outbound ---------------------------------------------------------------

def test_pressure_reference_frame():
    """Index finger, on, full intensity, full speed.

    12 04 | 09 00000000 | 01 01 | 01 00 | 09 00004842 | 01 64
    len op   f32 freq=0   finger  state    f32 50.0kPa   speed=100
    """
    frame = P.pressure(P.Finger.Index, True, 1.0, 1.0)
    assert _hex(frame) == "12 04 09 00 00 00 00 01 01 01 00 09 00 00 48 42 01 64"
    assert len(frame) == 18
    assert frame[0] == len(frame)


def test_pressure_release_frame():
    frame = P.pressure(P.Finger.Index, False, 0, 1.0)
    assert frame[0] == 18 and frame[1] == P.Op.SET_PRESSURE
    assert frame[8] == P.Finger.Index
    assert frame[10] == P.STATE_EXIT
    assert struct.unpack_from("<f", frame, 12)[0] == 0.0


def test_every_finger_encodes_its_own_channel():
    for f in P.ALL_FINGERS:
        frame = P.pressure(f, True, 1.0, 1.0)
        assert frame[8] == int(f), f"{f.name} encoded as channel {frame[8]}"


def test_state_is_never_the_no_op_value():
    """1 means 'stay' and the firmware ignores it. Neither path may emit it."""
    for on in (True, False):
        for builder in (
            lambda: P.pressure(P.Finger.Thumb, on, 0.5, 0.5),
            lambda: P.vibration(P.Finger.Thumb, on, 10.0, 0.5),
        ):
            assert builder()[10] in (P.STATE_ENTER, P.STATE_EXIT)


def test_vibration_reference_frame():
    frame = P.vibration(P.Finger.Palm, True, 20.0, 1.0, 0.5)
    assert len(frame) == 18
    assert frame[0] == 18 and frame[1] == P.Op.SET_VIBRATION
    assert struct.unpack_from("<f", frame, 3)[0] == 20.0
    assert frame[8] == P.Finger.Palm
    assert frame[10] == P.STATE_ENTER
    assert struct.unpack_from("<f", frame, 12)[0] == 50.0
    assert frame[17] == 50            # peak ratio 0.5 -> 50%


def test_vibration_ramped_frame_length():
    frame = P.vibration_ramped(P.Finger.Ring, True, 1.5, 0.8, 0.4, 0.9, 0.3)
    assert len(frame) == 25
    assert frame[0] == 25 and frame[1] == P.Op.SET_VIB_SPEED


def test_ramped_vibration_clamps_frequency_to_two_hz():
    """This mode tops out at 2 Hz even though plain vibration allows 40."""
    frame = P.vibration_ramped(P.Finger.Index, True, 40.0, 1.0, 0.5, 1.0, 0.5)
    assert struct.unpack_from("<f", frame, 3)[0] == 2.0


# -- value mapping ----------------------------------------------------------

def test_intensity_maps_to_the_firmware_pressure_window():
    assert P.intensity_to_kpa(0) == 0.0          # exactly zero means off
    assert P.intensity_to_kpa(0.1) == P.MIN_KPA
    assert P.intensity_to_kpa(1.0) == P.MAX_KPA
    assert P.intensity_to_kpa(5.0) == P.MAX_KPA  # clamped, never over-pressurised


def test_speed_and_peak_ratio_byte_ranges():
    assert P.speed_to_byte(0.1) == 10
    assert P.speed_to_byte(1.0) == 100
    assert P.speed_to_byte(9.0) == 100
    assert P.peak_ratio_to_byte(0.2) == 20
    assert P.peak_ratio_to_byte(0.8) == 80
    assert P.peak_ratio_to_byte(0.01) == 20


# -- batching ---------------------------------------------------------------

def test_all_off_is_six_walkable_frames():
    data = P.all_off()
    assert len(data) == 18 * 6
    # The firmware walks concatenated frames using byte 0 as the stride.
    offset, seen = 0, []
    while offset < len(data):
        length = data[offset]
        assert length == 18
        seen.append(data[offset + 8])
        offset += length
    assert seen == [int(f) for f in P.ALL_FINGERS]


# -- names ------------------------------------------------------------------

def test_hand_is_read_from_the_advertised_name():
    assert P.hand_from_name("HaptGloveAR Left") == "Left"
    assert P.hand_from_name("HaptGloveAR Right") == "Right"
    assert P.hand_from_name("HaptGloveAR") is None
    assert P.hand_from_name("Something Else") is None
    assert P.hand_from_name(None) is None


# -- inbound ----------------------------------------------------------------

def _inbound(op: int, payload: bytes) -> bytes:
    """Build a device->host frame, including its trailing XOR checksum."""
    body = bytes((len(payload) + 3, op)) + payload
    checksum = 0
    for b in body:
        checksum ^= b
    return body + bytes((checksum,))


def _pressure_frame(values) -> bytes:
    return _inbound(P.InOp.PRESSURE,
                    b"".join(bytes((0x09,)) + struct.pack("<f", v) for v in values))


def _battery_frame(level: float) -> bytes:
    return _inbound(P.InOp.BATTERY, bytes((0x09,)) + struct.pack("<f", level))


def test_decodes_pressure_and_battery():
    d = P.FrameDecoder()
    values = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0]
    t = d.feed(_pressure_frame(values) + _battery_frame(0.75))
    assert [round(v, 3) for v in t.pressure_raw] == values
    assert round(t.battery, 3) == 0.75
    assert d.bad_frames == 0


def test_frames_split_across_notifications_still_decode():
    """Notifications are not frame-aligned; the parser must hold state."""
    data = _pressure_frame([9.0] * 7)
    d = P.FrameDecoder()
    d.feed(data[:5])
    assert d.telemetry.pressure_raw[0] == 0.0   # nothing consumed yet
    t = d.feed(data[5:])
    assert t.pressure_raw[0] == 9.0


def test_a_corrupt_frame_does_not_swallow_the_next_good_one():
    bad = bytearray(_battery_frame(0.5))
    bad[-1] ^= 0xFF                              # break the checksum
    d = P.FrameDecoder()
    t = d.feed(bytes(bad) + _battery_frame(0.25))
    assert d.bad_frames >= 1
    assert round(t.battery, 3) == 0.25


def test_leading_garbage_is_resynchronised():
    d = P.FrameDecoder()
    t = d.feed(b"\x00\x00\xff\x07" + _battery_frame(0.5))
    assert round(t.battery, 3) == 0.5


def test_decoder_starts_from_an_empty_buffer():
    """A pre-sized bytearray would be full of NULs the parser has to chew."""
    assert len(P.FrameDecoder()._buf) == 0


# -- units ------------------------------------------------------------------

def test_raw_to_kpa_handles_both_firmware_conventions():
    assert P.raw_to_kpa(42.0) == 42.0             # already gauge kPa
    assert P.raw_to_kpa(142000.0) == 42.0         # absolute pascals
    assert P.looks_like_absolute_pa([100500.0]) is True
    assert P.looks_like_absolute_pa([0.5, 1.0]) is False


if __name__ == "__main__":
    failures = 0
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn()
                print(f"  PASS  {name}")
            except AssertionError as e:
                failures += 1
                print(f"  FAIL  {name}: {e}")
    print(f"\n{'FAILED' if failures else 'All protocol tests passed'}"
          f"{f' ({failures} failing)' if failures else ''}")
    sys.exit(1 if failures else 0)
