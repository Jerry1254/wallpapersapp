#!/usr/bin/env python3
"""Verify merged Android network/identity policy after Gradle manifest tasks."""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
base = root / 'apps/mobile/build/app/intermediates/merged_manifests'
android = '{http://schemas.android.com/apk/res/android}'
for variant, flavor, build_type, package, cleartext, label in [
    ('localDebug', 'local', 'debug', 'com.qingjing.bizhi.local', 'true', '倾境动态壁纸·本地'),
    ('localRelease', 'local', 'release', 'com.qingjing.bizhi.local', 'false', '倾境动态壁纸·本地'),
    ('prodRelease', 'prod', 'release', 'com.qingjing.bizhi', 'false', '倾境动态壁纸'),
    ('internalRelease', 'internal', 'release', 'com.qingjing.bizhi.internal', 'false', '倾境动态壁纸'),
    ('labRelease', 'lab', 'release', 'com.qingjing.bizhi.lab', 'false', '4D壁纸测试'),
]:
    paths = list((base / variant).glob('*/AndroidManifest.xml'))
    if len(paths) != 1:
        raise SystemExit(f'{variant}: missing or ambiguous merged manifest')
    manifest = ET.parse(paths[0]).getroot()
    app = manifest.find('application')
    assert manifest.attrib['package'] == package, variant
    values_path = root / 'apps/mobile/build/app/generated/res/resValues' / flavor / build_type / 'values/gradleResValues.xml'
    values = ET.parse(values_path).getroot()
    app_name = values.find("string[@name='app_name']")
    assert app_name is not None and app_name.text == label, variant
    assert app is not None
    assert app.attrib[android + 'usesCleartextTraffic'] == cleartext, variant
    assert app.attrib[android + 'allowBackup'] == 'false', variant
    if variant.startswith('internal'):
        assert app.attrib[android + 'networkSecurityConfig'] == '@xml/internal_network_security', variant
        assert any(m.attrib.get(android + 'name') == 'qingjing.internalDiagnostics' and m.attrib.get(android + 'value') == 'true' for m in app.findall('meta-data')), variant
        policy = ET.parse(root / 'apps/mobile/android/app/src/internal/res/xml/internal_network_security.xml').getroot()
        assert policy.find('base-config').attrib['cleartextTrafficPermitted'] == 'false'
        domains = policy.findall('domain-config')
        assert len(domains) == 1
        assert domains[0].attrib['cleartextTrafficPermitted'] == 'false'
        assert {d.text for d in domains[0].findall('domain')} == {'127.0.0.1', 'localhost'}
        assert all(d.attrib.get('includeSubdomains', 'false') == 'false' for d in domains[0].findall('domain'))
        assert not policy.findall('debug-overrides')
        assert all(c.attrib['src'] in {'system', '@raw/qingjing_internal_loopback'} for c in policy.iter('certificates'))
    else:
        assert android + 'networkSecurityConfig' not in app.attrib, variant
        assert not any(m.attrib.get(android + 'name') == 'qingjing.internalDiagnostics' for m in app.findall('meta-data')), variant
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
