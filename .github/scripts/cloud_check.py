#!/usr/bin/env python3
"""Checks the Rain tab's cloud parts in a uiautomator dump, for the emulator smoke test.

  cloud_check.py <ui.xml> <want>     want: "on", "off" or "fail"

Prints one line: "clouds <want> ok: ..." or "FAIL clouds <want>: ...". It checks what the person sees:
the time box says the rain time and, with clouds on, the cloud time (24 h); the Clouds switch is in the state
asked for; the credit line names NASA GIBS and Himawari (JMA); the red box shows only when the clouds failed,
and says the rain is still shown; no two of the map's boxes cover each other, and each is inside the screen.
"""
import re
import sys
import xml.etree.ElementTree as ET


def short(n):
    return n.get("resource-id", "").rsplit("/", 1)[-1]


def box(n):
    return tuple(int(v) for v in re.findall(r"\d+", n.get("bounds", "")))


def main():
    path, want = sys.argv[1], sys.argv[2]
    nodes = list(ET.parse(path).getroot().iter("node"))
    by = {}
    for n in nodes:
        by.setdefault(short(n), n)
    problems = []
    seen = []

    badge = by.get("rainBadge")
    text = badge.get("text", "") if badge is not None else ""
    seen.append(f"time box '{text}'")
    if want == "off":
        if not re.fullmatch(r"\d\d:\d\d|--", text):
            problems.append(f"time box '{text}' is not just the rain time")
    else:
        m = re.fullmatch(r"Rain (\d\d:\d\d|--)\nClouds (\d\d:\d\d|…|none)", text)
        if not m:
            problems.append(f"time box '{text}' is not 'Rain HH:mm' over 'Clouds HH:mm'")
        elif want == "on" and not re.fullmatch(r"\d\d:\d\d", m.group(2)):
            problems.append(f"no cloud time in '{text}'")
        elif want == "fail" and m.group(2) != "none":
            problems.append(f"the clouds failed, but the time box says '{m.group(2)}'")

    chip = by.get("cloudChip")
    if chip is None:
        problems.append("no Clouds switch")
    else:
        checked = chip.get("checked")
        seen.append(f"switch checked={checked}")
        if checked != ("false" if want == "off" else "true"):
            problems.append(f"the Clouds switch is checked={checked}")

    credit = by.get("cloudSource")
    if want != "off":
        ctext = credit.get("text", "") if credit is not None else ""
        if ctext != "Clouds: NASA GIBS, Himawari (JMA)":
            problems.append(f"credit line '{ctext}'")
    elif credit is not None:
        problems.append("the cloud credit shows with clouds off")

    error = by.get("cloudError")
    if want == "fail":
        etext = error.get("text", "") if error is not None else ""
        seen.append(f"red box '{etext}'")
        if "Rain is still shown" not in etext:
            problems.append(f"no red box saying the rain is still shown ('{etext}')")
    elif error is not None:
        problems.append("the red box shows, but the clouds did not fail")

    # The rain stays: Radar or Gauges is still there, and the radar did not fail.
    status = by.get("rainStatus")
    if status is not None and "Couldn" in status.get("text", ""):
        problems.append(f"the rain failed too: '{status.get('text')}'")

    screen = box(nodes[0]) if nodes else (0, 0, 0, 0)
    parts = [(k, by[k]) for k in ("rainBadge", "sourceToggle", "cloudChip", "cloudError", "rainStatus") if k in by]
    for k, n in parts:
        x1, y1, x2, y2 = box(n)
        if x1 < screen[0] or y1 < screen[1] or x2 > screen[2] or y2 > screen[3]:
            problems.append(f"{k} sticks out of the screen")
    for i, (a, na) in enumerate(parts):
        for b, nb in parts[i + 1:]:
            ax1, ay1, ax2, ay2 = box(na)
            bx1, by1, bx2, by2 = box(nb)
            if ax1 < bx2 and bx1 < ax2 and ay1 < by2 and by1 < ay2:
                problems.append(f"{a} and {b} cover each other")
    if chip is not None:
        x1, y1, x2, y2 = box(chip)
        seen.append(f"switch {y2 - y1}px tall")

    if problems:
        print(f"FAIL clouds {want}: " + "; ".join(problems) + " | " + ", ".join(seen))
    else:
        print(f"clouds {want} ok: " + ", ".join(seen))


if __name__ == "__main__":
    main()
