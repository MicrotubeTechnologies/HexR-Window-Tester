"""HEXR Window Tester — entry point and the mediator that wires everything together.

This is a hardware test and diagnostic tool. It is not the way to build with
HEXR: an application integrates the `com.microtube.hexr` Unity package or the
Python control library, and talks to the glove itself.

The App object owns the UI tick. Nothing else polls: the BLE thread mutates
AppState, and this redraws from it 20 times a second.
"""

from __future__ import annotations

import sys
import tkinter as tk

from hexr import theme as T
from hexr.engine import Engine
from hexr.screens.connect import ConnectScreen
from hexr.screens.quicktest import QuickTestScreen
from hexr.screens.test import TestScreen
from hexr.shell import Shell
from hexr.state import AppState

UI_HZ = 20


class App:
    def __init__(self, root: tk.Tk):
        self.root = root
        T.init(root)

        self.state = AppState()
        self.engine = Engine(self.state, on_change=self._engine_changed)
        self.engine.start()

        self.shell = Shell(root, on_run=self._header_clicked)
        self.shell.on_close = self.shutdown
        root.protocol("WM_DELETE_WINDOW", self.shell.close)

        self.screens = {
            "connect": ConnectScreen(self.shell.content, self),
            "test": TestScreen(self.shell.content, self),
            "quicktest": QuickTestScreen(self.shell.content, self),
        }
        for key, screen in self.screens.items():
            self.shell.register(key, screen)
        self.shell.go("connect")

        # Escape is the panic key. A haptics tester can leave a channel pressed
        # against a finger, and reaching for the mouse is the wrong thing to
        # have to do about that.
        root.bind("<Escape>", lambda e: self.all_off())

        self._tick()

    # -- glove actions -------------------------------------------------------

    def connect_glove(self, found: dict):
        self.engine.connect(found["hand"], found["address"],
                            found.get("name", ""), found.get("rssi"))

    def disconnect(self, hand: str):
        self.engine.disconnect(hand)

    def send(self, hands, data: bytes):
        self.engine.send_hands(list(hands), data)

    def all_off(self):
        self.engine.send_all_off()

    def shutdown(self):
        self.engine.shutdown()

    # -- UI tick -------------------------------------------------------------

    def _engine_changed(self):
        """Called from the BLE thread. Hop to Tk before touching a widget."""
        self.root.after(0, self._refresh_screens)

    def _refresh_screens(self):
        for screen in self.screens.values():
            if hasattr(screen, "refresh"):
                screen.refresh()

    def _tick(self):
        state, label, target = self.state.readiness()
        self.shell.set_run_state("running" if state == "ready" else "blocked",
                                 label)
        self._sync_status()
        screen = self.screens.get(self.shell.screen)
        if screen is not None and hasattr(screen, "on_tick"):
            screen.on_tick()
        self.root.after(int(1000 / UI_HZ), self._tick)

    def _sync_status(self):
        gloves = self.state.connected_gloves()
        if not gloves:
            self.shell.set_status(T.DOT_OFF, "no glove")
            return
        # A connected glove is a working glove whether or not it streams, so
        # the pill reports the connection and leaves telemetry to the places
        # that actually need it.
        hands = "+".join(sorted(g.hand[0] for g in gloves))
        batteries = [g.battery for g in gloves if g.battery is not None]
        if batteries:
            self.shell.set_status(T.SUCCESS,
                                  f"{hands}  {round(min(batteries) * 100)}%")
        else:
            self.shell.set_status(T.SUCCESS, hands)

    def _header_clicked(self):
        state, _label, target = self.state.readiness()
        if state == "ready":
            self.all_off()
        else:
            self.shell.go(target)


def main():
    root = tk.Tk()
    root.title("HEXR Window Tester")
    App(root)
    root.mainloop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
