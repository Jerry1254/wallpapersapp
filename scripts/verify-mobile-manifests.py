#!/usr/bin/env python3
"""Verify merged Android network/identity policy after Gradle manifest tasks."""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
base = root / 'apps/mobile/build/app/intermediates/merged_manifests'
android = '{http://schemas.android.com/apk/res/android}'
for variant, package, cleartext in [
    ('localDebug', 'com.qingjing.qingjing_wallpaper.local', 'true'),
    ('localRelease', 'com.qingjing.qingjing_wallpaper.local', 'false'),
    ('prodRelease', 'com.qingjing.qingjing_wallpaper', 'false'),
]:
    paths = list((base / variant).glob('*/AndroidManifest.xml'))
    if len(paths) != 1:
        raise SystemExit(f'{variant}: missing or ambiguous merged manifest')
    manifest = ET.parse(paths[0]).getroot()
    app = manifest.find('application')
    assert manifest.attrib['package'] == package, variant
    assert app is not None
    assert app.attrib[android + 'usesCleartextTraffic'] == cleartext, variant
    assert app.attrib[android + 'allowBackup'] == 'false', variant
    if variant.endswith('Release'):
        assert app.attrib.get(android + 'debuggable', 'false') == 'false', variant
    print(f'{variant}: identity, network and backup policy passed')
