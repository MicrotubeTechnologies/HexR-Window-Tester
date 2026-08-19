"""Shared scaffolding for the four screens.

Every screen in the design opens the same way: a 22 px title with a 13.5 px
sub-line 6 px under it, inside a scrolling 26/28 px padded content area.
"""

from __future__ import annotations

import tkinter as tk

from .. import theme as T
from ..widgets import ScrollArea


class Screen(tk.Frame):
    """Base screen. Build your content into `self.body`."""

    title_text = ""
    sub_text = ""

    def __init__(self, parent, app=None):
        super().__init__(parent, bg=T.SURFACE)
        self.app = app
        self._area = ScrollArea(self, bg=T.SURFACE)
        self._area.pack(fill="both", expand=True)
        self.root_body = self._area.body

        self.head = tk.Frame(self.root_body, bg=T.SURFACE)
        self.head.pack(fill="x", anchor="w")
        self._title = tk.Label(self.head, text=self.title_text, bg=T.SURFACE,
                               fg=T.TEXT, font=T.font(22, 600), anchor="w",
                               justify="left")
        self._title.pack(fill="x", anchor="w")
        self._sub = tk.Label(self.head, text=self.sub_text, bg=T.SURFACE,
                             fg=T.TEXT_MUTED, font=T.font(13.5), anchor="w",
                             justify="left", wraplength=620)
        self._sub.pack(fill="x", anchor="w", pady=(6, 0))

        self.body = tk.Frame(self.root_body, bg=T.SURFACE)
        self.body.pack(fill="both", expand=True, pady=(22, 0))

    def set_heading(self, title=None, sub=None):
        if title is not None:
            self._title.configure(text=title)
        if sub is not None:
            self._sub.configure(text=sub)

    def on_show(self):
        """Called each time the screen becomes visible."""


def section_label(parent, text, bg=T.SURFACE):
    """The design's tracked uppercase micro-label."""
    return tk.Label(parent, text=T.track(text), bg=bg, fg=T.TEXT_FAINT,
                    font=T.font(10), anchor="w")
