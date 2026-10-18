#!/usr/bin/env python3
"""Reads a uiautomator dump for the emulator smoke test.

  ui_probe.py bounds <ui.xml> <text-or-content-desc-or-id-suffix>   -> "x y" (centre of the first match)
  ui_probe.py attr   <ui.xml> <id-suffix> <attribute>               -> the attribute's value, or nothing
"""
import re
import sys
import xml.etree.ElementTree as ET


def nodes(path):
    return ET.parse(path).getroot().iter("node")


def centre(bounds):
    x1, y1, x2, y2 = (int(v) for v in re.findall(r"\d+", bounds))
    return (x1 + x2) // 2, (y1 + y2) // 2


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


if __name__ == "__main__":
    main()
