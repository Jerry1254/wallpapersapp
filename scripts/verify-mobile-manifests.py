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
    permissions = {p.attrib[android + 'name'] for p in manifest.findall('uses-permission')}
    assert 'android.permission.SET_WALLPAPER' in permissions, variant
    assert not permissions & {'android.permission.QUERY_ALL_PACKAGES', 'android.permission.READ_EXTERNAL_STORAGE', 'android.permission.MANAGE_EXTERNAL_STORAGE'}, variant
    for name in ['VideoWallpaperService', 'ParallaxWallpaperService']:
        services = [s for s in app.findall('service') if s.attrib[android + 'name'].endswith('.' + name)]
        assert len(services) == 1, variant
        service = services[0]
        assert service.attrib[android + 'exported'] == 'true', variant
        assert service.attrib[android + 'permission'] == 'android.permission.BIND_WALLPAPER', variant
        assert any(m.attrib.get(android + 'name') == 'android.service.wallpaper' for m in service.findall('meta-data')), variant
    for name in ['NativeWallpaperActivity', 'NativeTrialActivity']:
        viewers = [a for a in app.findall('activity') if a.attrib[android + 'name'].endswith('.' + name)]
        assert len(viewers) == 1 and viewers[0].attrib[android + 'exported'] == 'false', variant
    print(f'{variant}: identity, network, backup and wallpaper component policy passed')
