"""HEXR glove wire protocol — frame building and decoding. No I/O.

Everything here is pure: builders return `bytes`, the decoder consumes `bytes`.
The BLE transport lives in `engine.py`. Keeping them apart is what makes the
protocol testable without hardware, and the reference frames in `tests/` pin
the byte layout so a refactor cannot silently change what reaches the glove.

Wire format
-----------
Outbound  ``[len][opcode][TLV...]``            — no checksum, no terminator
Inbound   ``[len][opcode][TLV...][xor]``       — trailing XOR of bytes 0..len-2

`len` is the *whole* frame including itself, which is also the stride the
firmware uses to walk several frames concatenated into one write.

A TLV is a one-byte type tag followed by a little-endian value.

Two facts that are not guessable from the wire and cost real time if missed:

1. **State 1 ("stay") is a no-op.** The firmware acts on 0 (enter) and 2
   (exit) only. An earlier Python sample sent 1 to mean "on" and did nothing.

2. **Telemetry is not required for actuation.** Reading the firmware suggests
   otherwise — the 50 Hz notify timer also feeds the pressure loop — but
   gloves in hand actuate perfectly well having sent nothing back. Subscribe
   anyway, because the pressure readout and the QA sweep both need it, but do
   not treat silence as a fault.
"""

from __future__ import annotations

import struct
from enum import IntEnum

# -- identity ---------------------------------------------------------------

SERVICE_UUID = "000000ff-0000-1000-8000-00805f9b34fb"
CHAR_UUID = "0000ff01-0000-1000-8000-00805f9b34fb"  # write + notify, one handle

NAME_PREFIX = "HaptGloveAR"
NAME_LEFT = "HaptGloveAR Left"
NAME_RIGHT = "HaptGloveAR Right"


def hand_from_name(name: str | None) -> str | None:
    """'Left' | 'Right' | None. Hand is carried only by the advertised name —
    the command bytes are identical for both gloves."""
    if not name:
        return None
    n = name.strip()
    if not n.startswith(NAME_PREFIX):
        return None
    tail = n[len(NAME_PREFIX):].strip().lower()
    if tail.startswith("left"):
        return "Left"
    if tail.startswith("right"):
        return "Right"
    return None


# -- opcodes ----------------------------------------------------------------

class Op(IntEnum):
    AIR_PRESSURE = 0x01
    STABLE_PRESSURE_CTRL = 0x02   # dead on current firmware — do not send
    SET_PRESSURE_DEPRECATED = 0x03
    SET_PRESSURE = 0x04
    SET_PID = 0x05
    SET_BATTERY_LED = 0x06
    SET_VIBRATION = 0x07
    SET_PULSE = 0x08
    SET_VIB_SPEED = 0x09
    SET_PULSE_SPEED = 0x0A


class InOp(IntEnum):
    """Opcodes the glove sends back."""
    PRESSURE = 0x01
    MICROTUBE = 0x04
    CLUTCH_ACTIVATED = 0x05
    BATTERY = 0x06


class Finger(IntEnum):
    Thumb = 0
    Index = 1
    Middle = 2
    Ring = 3
    Pinky = 4
    Palm = 5


ALL_FINGERS = tuple(Finger)
FINGER_LABELS = {
    Finger.Thumb: "Thumb", Finger.Index: "Index", Finger.Middle: "Middle",
    Finger.Ring: "Ring", Finger.Pinky: "Pinky", Finger.Palm: "Palm",
}

STATE_ENTER = 0   # apply / inflate
STATE_EXIT = 2    # release / vent

# Physical limits the firmware itself enforces: below MIN_KPA a channel is
# simply switched off, and the target is capped at MAX_KPA.
MIN_KPA = 15.0
MAX_KPA = 50.0

# Vibration below this frequency is produced pneumatically by toggling the PID
# target; at or above it the PID is disabled and the channel's PWM is driven
# hard on/off. The two feel entirely different, so the UI names the boundary.
PWM_VIBRATION_HZ = 5.0


# -- encoding ---------------------------------------------------------------

TAG_U8 = 0x01
TAG_U16 = 0x02
TAG_F32 = 0x09


def _u8(v: int) -> bytes:
    return bytes((TAG_U8, v & 0xFF))


def _u16(v: int) -> bytes:
    return bytes((TAG_U16, v & 0xFF, (v >> 8) & 0xFF))


def _f32(v: float) -> bytes:
    return bytes((TAG_F32,)) + struct.pack("<f", float(v))


def _frame(op: int, payload: bytes) -> bytes:
    """Prepend the length and opcode. Length counts its own two bytes."""
    return bytes((len(payload) + 2, int(op))) + payload


def clamp(lo: float, hi: float, v: float) -> float:
    return lo if v < lo else hi if v > hi else v


def _map(in_lo: float, in_hi: float, v: float, out_lo: float, out_hi: float) -> float:
    if in_hi == in_lo:
        return 0.0
    return (out_hi - out_lo) * (v - in_lo) / (in_hi - in_lo) + out_lo


def intensity_to_kpa(intensity: float) -> float:
    """0.1–1.0 → 15–50 kPa. Exactly 0 means off, not 'lightest touch'."""
    if intensity == 0:
        return 0.0
    return _map(0.1, 1.0, clamp(0.1, 1.0, intensity), MIN_KPA, MAX_KPA)


def speed_to_byte(speed: float) -> int:
    """0.1–1.0 → 10–100. This is a ramp *rate*, roughly 60 ms per 10 kPa at 1.0."""
    return int(clamp(0.1, 1.0, speed) * 100)


def peak_ratio_to_byte(peak_ratio: float) -> int:
    """0.2–0.8 → 20–80, the duty cycle of the vibration waveform in percent."""
    return int(clamp(0.2, 0.8, peak_ratio) * 100)


# -- outbound commands ------------------------------------------------------

def pressure(finger: Finger, on: bool, intensity: float, speed: float) -> bytes:
    """Steady closed-loop pressure on one channel. 18 bytes."""
    payload = (
        _f32(0.0)                                   # frequency 0 = no vibration
        + _u8(int(finger))
        + _u8(STATE_ENTER if on else STATE_EXIT)
        + _f32(intensity_to_kpa(intensity if on else 0))
        + _u8(speed_to_byte(speed))
    )
    return _frame(Op.SET_PRESSURE, payload)


def vibration(finger: Finger, on: bool, frequency: float, intensity: float,
              peak_ratio: float = 0.5) -> bytes:
    """Vibration on one channel. 18 bytes.

    Frequency is clamped to 0.1–40 Hz. Below `PWM_VIBRATION_HZ` this is
    pneumatic; at or above it the channel's motor is driven directly.
    """
    payload = (
        _f32(clamp(0.1, 40.0, frequency))
        + _u8(int(finger))
        + _u8(STATE_ENTER if on else STATE_EXIT)
        + _f32(intensity_to_kpa(intensity if on else 0))
        + _u8(peak_ratio_to_byte(peak_ratio))
    )
    return _frame(Op.SET_VIBRATION, payload)


def vibration_ramped(finger: Finger, on: bool, frequency: float, intensity: float,
                     peak_ratio: float, speed: float, end_intensity: float) -> bytes:
    """Ramped vibration (FI_SET_VIB_SPEED). 25 bytes.

    Note the tighter frequency clamp: this mode tops out at 2 Hz, not 40.
    """
    end_kpa = 0.0 if end_intensity <= 0.1 else intensity_to_kpa(end_intensity)
    payload = (
        _f32(clamp(0.1, 2.0, frequency))
        + _u8(int(finger))
        + _u8(STATE_ENTER if on else STATE_EXIT)
        + _f32(intensity_to_kpa(intensity if on else 0))
        + _u8(peak_ratio_to_byte(peak_ratio))
        + _u8(speed_to_byte(speed))
        + _f32(end_kpa)
    )
    return _frame(Op.SET_VIB_SPEED, payload)


def batch(frames) -> bytes:
    """Concatenate frames for a single write. The firmware walks them by length."""
    return b"".join(frames)


def all_off(fingers=ALL_FINGERS) -> bytes:
    """Release every channel. Sent on disconnect, on close, and on demand."""
    return batch(pressure(f, False, 0, 1.0) for f in fingers)


# -- inbound decoding -------------------------------------------------------

class Telemetry:
    """Latest known state of one glove, updated in place by `FrameDecoder`."""

    __slots__ = ("pressure_raw", "finger_position", "battery", "seen")

    def __init__(self):
        # 7 channels: Thumb..Palm then the source/tank reading.
        self.pressure_raw: list[float] = [0.0] * 7
        self.finger_position: list[int] = [0] * 5
        self.battery: float | None = None   # 0.0–1.0 fraction, not a percentage
        self.seen = False


class FrameDecoder:
    """Resynchronising stream parser for the notify characteristic.

    Notifications are not frame-aligned in general, so this buffers, validates
    the length, verifies the XOR, and only then consumes. A frame that fails
    validation costs one byte, not the whole buffer — dropping the buffer on a
    bad byte would resynchronise far more slowly.
    """

    MAX_BUFFER = 74 * 16

    def __init__(self, telemetry: Telemetry | None = None):
        # Empty, not pre-sized. A pre-sized bytearray is filled with NUL bytes
        # that the parser then has to chew through forever.
        self._buf = bytearray()
        self.telemetry = telemetry or Telemetry()
        self.bad_frames = 0

    def feed(self, data: bytes) -> Telemetry:
        self._buf.extend(data)
        if len(self._buf) > self.MAX_BUFFER:
            del self._buf[:-self.MAX_BUFFER]

        while len(self._buf) >= 3:
            length = self._buf[0]
            op = self._buf[1]
            if length < 3 or op not in _INBOUND_OPS:
                del self._buf[0]
                continue
            if len(self._buf) < length:
                break
            frame = bytes(self._buf[:length])
            checksum = 0
            for b in frame[:length - 1]:
                checksum ^= b
            if checksum != frame[length - 1]:
                self.bad_frames += 1
                del self._buf[0]
                continue
            del self._buf[:length]
            self._apply(frame)
        return self.telemetry

    def _apply(self, frame: bytes):
        op = frame[1]
        t = self.telemetry
        try:
            if op == InOp.PRESSURE:
                # 7 × f32, stride 5 (one tag byte + four data bytes).
                for i in range(7):
                    off = 3 + i * 5
                    t.pressure_raw[i] = struct.unpack_from("<f", frame, off)[0]
                t.seen = True
            elif op in (InOp.MICROTUBE, InOp.CLUTCH_ACTIVATED):
                for i in range(5):
                    off = 3 + i * 5
                    t.finger_position[i] = struct.unpack_from("<i", frame, off)[0]
                t.seen = True
            elif op == InOp.BATTERY:
                t.battery = struct.unpack_from("<f", frame, 3)[0]
                t.seen = True
        except struct.error:
            # A frame that passed its checksum but is shorter than its opcode
            # implies. Count it rather than crashing the BLE thread.
            self.bad_frames += 1


_INBOUND_OPS = {int(o) for o in InOp}


# -- pressure units ---------------------------------------------------------
#
# Unresolved, and the two existing Microtube apps disagree: the firmware
# comments its value as kPa gauge, the Unity editor tooling converts it with
# (raw - 100000) / 1000 as though it were absolute pascals, and the Unity test
# app prints the raw number as "kPa" with no conversion at all.
#
# Rather than pick one and be silently wrong, detect it: a glove at rest reads
# near 0 if the value is gauge kPa, and near 100000 if it is absolute pascals.
# The threshold sits far from both.

_ABSOLUTE_PA_THRESHOLD = 10000.0


def raw_to_kpa(raw: float) -> float:
    """Gauge kPa, whichever way the firmware reports it."""
    if raw > _ABSOLUTE_PA_THRESHOLD:
        return (raw - 100000.0) / 1000.0
    return raw


def looks_like_absolute_pa(raw_values) -> bool:
    """True if this glove reports absolute pascals. Logged once on connect so
    the ambiguity gets settled by observation rather than argument."""
    return any(v > _ABSOLUTE_PA_THRESHOLD for v in raw_values)
