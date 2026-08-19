"""BLE runtime: one asyncio loop on one background thread, owning both clients.

The Tk UI never touches the loop. It calls the thread-safe methods here, and
reads glove values off `AppState` on its own timer. Callbacks out of this
module always hop back to Tk with `root.after(0, ...)` — never touch a widget
from the BLE thread.

Left and right are two entirely independent BLE sessions to two peripherals
that happen to speak the same protocol; nothing is shared but the loop.
"""

from __future__ import annotations

import asyncio
import threading
import time

from . import protocol as P
from .state import AppState, Glove

SCAN_SECONDS = 8.0

# The Unity plugin enforced a 100 ms gap between writes. It is not clear
# whether the firmware needs it or whether it was defensive, so it is honoured
# here — a tester is not throughput-bound, and a stuck channel is a much worse
# outcome than a slightly late one.
MIN_SEND_GAP_S = 0.1


class Engine:
    def __init__(self, state: AppState, on_change=None):
        self.state = state
        self.on_change = on_change          # () -> None, marshalled by caller

        self.loop: asyncio.AbstractEventLoop | None = None
        self._thread = threading.Thread(target=self._run_loop, daemon=True)
        self._ready = threading.Event()

        self._clients: dict[str, object] = {}          # hand -> BleakClient
        self._stops: dict[str, threading.Event] = {}   # hand -> stop signal
        self._locks: dict[str, asyncio.Lock] = {}
        self._last_send: dict[str, float] = {}

        self.scanning = False
        self.ble_error: str | None = None

    # -- lifecycle -----------------------------------------------------------

    def start(self):
        self._thread.start()
        self._ready.wait(3.0)

    def _run_loop(self):
        self.loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self.loop)
        self._ready.set()
        self.loop.run_forever()

    def _submit(self, coro):
        if self.loop and self.loop.is_running():
            return asyncio.run_coroutine_threadsafe(coro, self.loop)
        coro.close()
        return None

    def shutdown(self):
        """Vent every channel before going away.

        This is the most important three lines in the app: closing the window
        while a channel is inflated would leave it pressed against someone's
        finger until the battery died.
        """
        self.scanning = False
        self.send_all_off()
        for ev in self._stops.values():
            ev.set()
        if self.loop and self.loop.is_running():
            # Let the sessions notice their stop event and unwind their
            # clients, rather than killing the loop out from under them.
            self.loop.call_soon_threadsafe(
                lambda: self.loop.call_later(0.4, self.loop.stop))

    def _changed(self):
        if self.on_change:
            self.on_change()

    # -- scanning ------------------------------------------------------------

    def scan(self, on_result, on_done):
        """Stream discovered gloves to `on_result(dict)` as they arrive."""
        if self.scanning:
            return
        self.scanning = True
        self.ble_error = None
        self._submit(self._scan(on_result, on_done))

    def stop_scan(self):
        self.scanning = False

    async def _scan(self, on_result, on_done):
        seen: set[str] = set()
        try:
            from bleak import BleakScanner

            def detected(device, adv):
                name = device.name or adv.local_name or ""
                hand = P.hand_from_name(name)
                if hand is None or device.address in seen:
                    return
                seen.add(device.address)
                on_result({"address": device.address, "name": name,
                           "hand": hand, "rssi": adv.rssi})

            scanner = BleakScanner(detection_callback=detected)
            await scanner.start()
            deadline = time.monotonic() + SCAN_SECONDS
            while self.scanning and time.monotonic() < deadline:
                await asyncio.sleep(0.1)
            await scanner.stop()
        except Exception as e:
            # Adapter off, no radio, no permission. Surface it — reporting
            # "no gloves found" for a disabled Bluetooth adapter sends people
            # looking for a hardware fault that isn't there.
            self.ble_error = str(e)
        finally:
            self.scanning = False
            on_done(self.ble_error)

    # -- connections ---------------------------------------------------------

    def connect(self, hand: str, address: str, name: str = "", rssi=None):
        existing = self.state.get(hand)
        if existing and (existing.connected or existing.connecting):
            return
        glove = self.state.add_glove(hand, address, name, rssi)
        glove.connecting = True
        glove.status = "Connecting…"
        ev = threading.Event()
        self._stops[hand] = ev
        self._changed()
        self._submit(self._run_glove(glove, ev))

    def disconnect(self, hand: str):
        self.send_all_off(hand)
        ev = self._stops.get(hand)
        if ev:
            ev.set()

    def disconnect_all(self):
        for hand in list(self._stops):
            self.disconnect(hand)

    async def _run_glove(self, glove: Glove, stop: threading.Event):
        from bleak import BleakClient

        decoder = P.FrameDecoder(glove.telemetry)
        self._locks[glove.hand] = asyncio.Lock()
        try:
            async with BleakClient(glove.address) as client:
                self._clients[glove.hand] = client

                def cb(_handle, data: bytearray):
                    decoder.feed(bytes(data))
                    glove.last_rx = time.monotonic()
                    if glove.absolute_pa is None and glove.telemetry.seen:
                        glove.absolute_pa = P.looks_like_absolute_pa(
                            glove.telemetry.pressure_raw)

                # Subscribe before reporting connected, so a glove that does
                # stream is already streaming by the time the UI shows it.
                # Actuation does not depend on this — gloves drive fine having
                # never sent a byte — but the pressure readout and the QA
                # sweep do.
                await client.start_notify(P.CHAR_UUID, cb)

                glove.connected = True
                glove.connecting = False
                glove.status = "Connected"
                self._changed()

                while not stop.is_set() and client.is_connected:
                    await asyncio.sleep(0.15)

                # Vent on the way out while the link is still up.
                try:
                    await self._write(client, glove.hand, P.all_off())
                except Exception:
                    pass
                try:
                    await client.stop_notify(P.CHAR_UUID)
                except Exception:
                    pass
        except Exception as e:
            glove.status = f"Error: {e}"
        else:
            glove.status = "Not connected"
        finally:
            self._clients.pop(glove.hand, None)
            self._stops.pop(glove.hand, None)
            glove.connected = False
            glove.connecting = False
            self._changed()

    # -- sending -------------------------------------------------------------

    async def _write(self, client, hand: str, data: bytes):
        lock = self._locks.get(hand)
        if lock is None:
            lock = self._locks[hand] = asyncio.Lock()
        async with lock:
            gap = time.monotonic() - self._last_send.get(hand, 0.0)
            if gap < MIN_SEND_GAP_S:
                await asyncio.sleep(MIN_SEND_GAP_S - gap)
            # The characteristic declares WRITE without WRITE_NO_RESPONSE, so
            # a with-response write is the only legal one here.
            await client.write_gatt_char(P.CHAR_UUID, data, response=True)
            self._last_send[hand] = time.monotonic()

    def send(self, hand: str, data: bytes):
        """Thread-safe: queue a write to one glove. Silently ignored if that
        hand is not connected, so callers can fire at 'both' unconditionally."""
        client = self._clients.get(hand)
        if client is None or not data:
            return
        self._submit(self._write(client, hand, data))

    def send_hands(self, hands, data: bytes):
        for hand in hands:
            self.send(hand, data)

    def send_all_off(self, hand: str | None = None):
        hands = [hand] if hand else list(self._clients)
        for h in hands:
            self.send(h, P.all_off())
