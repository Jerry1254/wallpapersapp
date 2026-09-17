#!/usr/bin/env python3
"""Drive A07's native UI on a disposable, English Android emulator only."""
import argparse
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path, help="Live Flutter test output file")
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--adb", default=shutil.which("adb"))
    args = parser.parse_args()
    if not args.serial.startswith("emulator-") or not args.adb:
        parser.error("Requires an emulator serial and adb on PATH or --adb")

    def adb(*command):
        return subprocess.run(
            [args.adb, "-s", args.serial, *command],
            capture_output=True, check=True, timeout=45,
        ).stdout

    def marker(name):
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            if args.log.exists() and name in args.log.read_text(errors="replace"):
                return
            time.sleep(0.2)
        raise RuntimeError(f"Timed out waiting for {name}")

    def ui(label, tap=False):
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            adb("shell", "uiautomator", "dump", "/sdcard/a07-ui.xml")
            nodes = ET.fromstring(adb("shell", "cat", "/sdcard/a07-ui.xml"))
            for node in nodes.iter("node"):
                value = node.get("text", "")
                if value == label or (not tap and label in value):
                    if tap:
                        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
                        adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                    return
        raise RuntimeError(f"Native UI not found: {label}")

    marker("A07_HOST STATIC_PREVIEW")
    ui("静态预览")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    marker("A07_HOST VIDEO_PICKER_CANCEL")
    ui("Set wallpaper")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    marker("A07_HOST VIDEO_PICKER_ACCEPT")
    ui("Set wallpaper", tap=True)
    ui("Home screen", tap=True)
    marker("A07_HOST HOME_VISIBLE")
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    marker("A07_HOST REOPEN_APP")
    adb("shell", "am", "start", "-n", "com.qingjing.bizhi.local/com.qingjing.qingjing_wallpaper.MainActivity")
    marker("A07_HOST APP_VIDEO_PREVIEW")
    ui("视频预览")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    marker("A07_PLAYBACK_ALL_PASSED")
    print("A07 native UI workflow passed", flush=True)


if __name__ == "__main__":
    main()
