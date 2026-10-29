#!/usr/bin/env python3
"""Reads a uiautomator dump for the emulator smoke test.

  ui_probe.py bounds  <ui.xml> <text-or-content-desc-or-id-suffix>   -> "x y" (centre of the first match)
  ui_probe.py attr    <ui.xml> <id-suffix> <attribute>               -> the attribute's value, or nothing
  ui_probe.py all     <ui.xml> <id-suffix> <attribute>               -> the value of every match, joined by " | "
  ui_probe.py desc    <ui.xml> <text>                                -> "x y" (centre) of the first node whose description starts with the text
  ui_probe.py widgets <ui.xml>                                       -> one line per Haze Map widget on screen
  ui_probe.py frames  <ui.xml> <shot.raw>...                         -> how many shots differ inside each picture

The raw shots come from `adb exec-out screencap` without -p: a small header, then 4 bytes per pixel.
"""
import hashlib
import re
import struct
import sys
import xml.etree.ElementTree as ET

WIDGET_FIELDS = ("wClock", "wDate", "wDateSide", "wTemp", "wCondition", "wPlace", "wBadge", "wWhen", "wEventText")


def nodes(path):
    return ET.parse(path).getroot().iter("node")


def box(bounds):
    return tuple(int(v) for v in re.findall(r"\d+", bounds))


def centre(bounds):
    x1, y1, x2, y2 = box(bounds)
    return (x1 + x2) // 2, (y1 + y2) // 2


def short_id(node):
    return node.get("resource-id", "").rsplit("/", 1)[-1]


def widget_roots(path):
    return [n for n in nodes(path) if short_id(n) == "wRoot"]


def widgets(path):
    roots = widget_roots(path)
    if not roots:
        print("no widget found on screen")
    for root in roots:
        x1, y1, x2, y2 = box(root.get("bounds"))
        # wScenePartlyNight is the picture "partly_night".
        scenes = [re.sub(r"(?<!^)(?=[A-Z])", "_", short_id(n)[len("wScene"):]).lower()
                  for n in root.iter("node") if short_id(n).startswith("wScene")]
        fields = {short_id(n): n.get("text", "") for n in root.iter("node") if short_id(n) in WIDGET_FIELDS}
        text = " ".join(f"{k[1:].lower()}='{fields[k]}'" for k in WIDGET_FIELDS if k in fields)
        print(f"widget {x2 - x1}x{y2 - y1}px picture={'+'.join(scenes) or 'none'} {text}")


def frames(path, raws):
    # Each widget's picture box; every scene shares it, so one dump serves all of them.
    boxes = [(short_id(n), box(n.get("bounds"))) for n in nodes(path) if short_id(n).startswith("wScene")]
    if not boxes:
        boxes = [("whole widget", box(n.get("bounds"))) for n in widget_roots(path)]
    if not boxes:
        print("no widget found on screen")
        return
    shots = []
    for raw in raws:
        with open(raw, "rb") as fh:
            data = fh.read()
        w, h = struct.unpack_from("<II", data)
        head = len(data) - w * h * 4
        if head not in (12, 16):
            print(f"{raw}: not a 4-byte-per-pixel screenshot")
            return
        shots.append((data, w, head))
    out = []
    for name, (x1, y1, x2, y2) in boxes:
        crops = []
        for data, w, head in shots:
            rows = (data[head + (y * w + x1) * 4:head + (y * w + x2) * 4] for y in range(y1, y2))
            crops.append(memoryview(b"".join(rows)).cast("I"))
        # How far apart the shots are: real motion moves thousands of pixels, a redraw only a few.
        changed = max((sum(p != q for p, q in zip(a, b)) for a, b in zip(crops, crops[1:])), default=0)
        distinct = len({hashlib.md5(c).hexdigest() for c in crops})
        label = name if name == "whole widget" else "picture"
        out.append(f"{label} {x2 - x1}x{y2 - y1}px: {distinct} different of {len(shots)}, {changed} px changed")
    print("; ".join(out))


def main():
    mode, path = sys.argv[1], sys.argv[2]
    if mode == "bounds":
        needle = sys.argv[3]
        for n in nodes(path):
            if needle in (n.get("text"), n.get("content-desc")) or n.get("resource-id", "").endswith("/" + needle):
                print(*centre(n.get("bounds")))
                return
        sys.exit(f"no node matching {needle!r}")
    if mode == "attr":
        suffix, attr = sys.argv[3], sys.argv[4]
        for n in nodes(path):
            if n.get("resource-id", "").endswith("/" + suffix):
                print(n.get(attr, ""))
                return
    if mode == "desc":
        for n in nodes(path):
            if n.get("content-desc", "").startswith(sys.argv[3]):
                print(*centre(n.get("bounds")))
                return
        sys.exit(f"no node described {sys.argv[3]!r}")
    if mode == "all":
        suffix, attr = sys.argv[3], sys.argv[4]
        print(" | ".join(n.get(attr, "") for n in nodes(path) if n.get("resource-id", "").endswith("/" + suffix)))
    if mode == "widgets":
        widgets(path)
    if mode == "frames":
        frames(path, sys.argv[3:])


if __name__ == "__main__":
    main()
