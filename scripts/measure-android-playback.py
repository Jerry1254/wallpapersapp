#!/usr/bin/env python3
"""Measure the predeclared A07/A08 visible/hidden conditions on one authorized device."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--package', default='com.qingjing.qingjing_wallpaper.internal')
    parser.add_argument('--effect', choices=['video', 'parallax'], required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--hidden-only', action='store_true',
                        help='Repeat only the fixed 10-minute hidden phase after a condition interruption')
    args = parser.parse_args()
    adb = [args.adb, '-s', args.serial]
    service = ('Parallax' if args.effect == 'parallax' else 'Video') + 'WallpaperService'
    component = args.package + '/com.qingjing.wallpaper_android.playback.' + service
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    samples = []
    report = {'effect': args.effect, 'package': args.package,
              'conditionsSeconds': {'warmup': 0 if args.hidden_only else 300,
                                   'visible': 0 if args.hidden_only else 1800, 'hidden': 600},
              'samples': samples, 'completed': False}

    def run(*command):
        return subprocess.check_output(adb + list(command), stderr=subprocess.DEVNULL,
                                       text=True, timeout=25)

    def save():
        output.write_text(json.dumps(report, indent=2))
        output.chmod(0o600)

    def engines():
        dump = run('shell', 'dumpsys', 'activity', 'service', component)
        rows = [line.strip().removeprefix('QJ_INTERNAL_PLAYBACK ')
                for line in dump.splitlines() if line.strip().startswith('QJ_INTERNAL_PLAYBACK ')]
        if len(rows) != 1:
            raise RuntimeError('Read-only playback diagnostics unavailable')
        return json.loads(rows[0])

    def snapshot(phase):
        playback = engines()
        if phase == 'hidden':
            focus = run('shell', 'dumpsys', 'window')
            if not any(args.package in line for line in focus.splitlines() if 'mCurrentFocus=' in line):
                report['conditionInterrupted'] = True
                report['interruptedPlayback'] = playback
                raise RuntimeError('Hidden condition interrupted: test application is no longer foreground')
        memory = run('shell', 'dumpsys', 'meminfo', args.package)
        pss = re.search(r'TOTAL PSS:\s*(\d+)', memory)
        if pss is None:
            pss = re.search(r'^\s*TOTAL\s+(\d+)', memory, re.M)
        if pss is None:
            raise RuntimeError('PSS measurement unavailable')
        battery = run('shell', 'dumpsys', 'battery')
        battery_values = {key: re.search(r'^\s*' + re.escape(key) + r':\s*(.+)', battery, re.M).group(1)
                          for key in ['AC powered', 'USB powered', 'Wireless powered',
                                      'level', 'temperature', 'voltage', 'status']}
        cpu = run('shell', 'dumpsys', 'cpuinfo')
        own_cpu = [line.strip() for line in cpu.splitlines() if args.package in line]
        value = {'phase': phase, 'elapsed': playback['elapsed'], 'pssKiB': int(pss.group(1)),
                 'engines': playback['engines'], 'battery': battery_values, 'ownCpu': own_cpu}
        if args.effect == 'parallax' and phase in ['warmup', 'visible']:
            current = next((e for e in playback['engines'] if not e.get('preview') and e.get('visible')), None)
            if current is None or not current.get('rendering') or not current.get('sensorRegistered'):
                raise RuntimeError('Expected visible physical-sensor rendering was interrupted')
            previous = next((x for x in reversed(samples) if x['phase'] in ['warmup', 'visible']), None)
            if previous:
                old = next(e for e in previous['engines'] if not e.get('preview') and e.get('visible'))
                value['observedFramesPerSecond'] = (current['frames']-old['frames']) * 1000 / (value['elapsed']-previous['elapsed'])
        if args.effect == 'video' and phase in ['warmup', 'visible']:
            current = next((e for e in playback['engines'] if not e.get('preview') and e.get('visible') and e.get('playing')), None)
            if current is None:
                raise RuntimeError('Expected visible video playback was interrupted')
            if current.get('decodeErrors', 0) or current.get('failed'):
                raise RuntimeError('Visible video decoder reported an error')
            previous = next((x for x in reversed(samples) if x['phase'] in ['warmup', 'visible']), None)
            if previous and 'frames' in current:
                old = next(e for e in previous['engines'] if not e.get('preview') and e.get('visible'))
                if 'frames' in old and current['frames'] >= old['frames']:
                    value['observedFramesPerSecond'] = (current['frames']-old['frames']) * 1000 / (value['elapsed']-previous['elapsed'])
                if 'droppedFrames' in current and 'droppedFrames' in old:
                    value['droppedFramesDelta'] = current['droppedFrames']-old['droppedFrames']
        if phase == 'hidden':
            if any(e.get('visible') or e.get('rendering') or e.get('playing') or
                   e.get('sensorRegistered') or e.get('decodedBytes', 0) for e in playback['engines']):
                raise RuntimeError('Hidden playback resources remained active')
        samples.append(value)
        save()
        print(json.dumps({'phase': phase, 'elapsed': value['elapsed'], 'pssKiB': value['pssKiB'],
                          'fps': value.get('observedFramesPerSecond'), 'temperature': battery_values['temperature']}), flush=True)
        return value

    wallpaper = run('shell', 'dumpsys', 'wallpaper')
    if not any(args.package in line and service in line for line in wallpaper.splitlines()
               if 'mWallpaperComponent=' in line):
        raise RuntimeError('The selected test service is not the current system wallpaper')
    original_timeout = run('shell', 'settings', 'get', 'system', 'screen_off_timeout').strip()
    report['originalScreenOffTimeout'] = original_timeout
    report['brightness'] = run('shell', 'settings', 'get', 'system', 'screen_brightness').strip()
    report['brightnessMode'] = run('shell', 'settings', 'get', 'system', 'screen_brightness_mode').strip()
    report['bootCount'] = run('shell', 'settings', 'get', 'global', 'boot_count').strip()
    try:
        run('shell', 'settings', 'put', 'system', 'screen_off_timeout', '3600000')
        began = int(float(run('shell', 'cat', '/proc/uptime').split()[0])*1000)
        run('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        ready = False
        for _ in range(15):
            current = engines()
            if any(not e.get('preview') and e.get('visible') and
                   e.get('rendering') for e in current['engines']):
                report['firstVisibleReadyMs'] = current['elapsed']-began
                ready = True
                break
            time.sleep(.1)
        if not ready or report['firstVisibleReadyMs'] > 3000:
            raise RuntimeError('Visible first-frame threshold exceeded')
        phases = [('hidden', 600)] if args.hidden_only else [('warmup', 300), ('visible', 1800), ('hidden', 600)]
        for phase, duration in phases:
            if phase == 'hidden':
                began = int(float(run('shell', 'cat', '/proc/uptime').split()[0])*1000)
                run('shell', 'am', 'start', '-n', args.package + '/com.qingjing.qingjing_wallpaper.MainActivity')
                released = False
                for _ in range(15):
                    current = engines()
                    if not any(e.get('visible') or e.get('rendering') or e.get('playing') or
                               e.get('sensorRegistered') or e.get('decodedBytes', 0) for e in current['engines']):
                        report['hiddenReleaseObservedMs'] = current['elapsed']-began
                        released = True
                        break
                    time.sleep(.05)
                if not released or report['hiddenReleaseObservedMs'] > 1000:
                    raise RuntimeError('Hidden release observation exceeded 1s; repeat with precise lifecycle timestamps')
            deadline = time.monotonic()+duration
            first = snapshot(phase)
            if phase == 'visible':
                report['warmedPssKiB'] = first['pssKiB']
            while time.monotonic() < deadline:
                time.sleep(min(30, max(0, deadline-time.monotonic())))
                snapshot(phase)
            report[phase+'Completed'] = True
            save()
        if not args.hidden_only:
            measured = [sample['pssKiB'] for sample in samples if sample['phase'] == 'visible']
            report['maximumWarmedPssGrowthKiB'] = max(measured)-report['warmedPssKiB']
            report['pssThresholdPassed'] = report['maximumWarmedPssGrowthKiB'] <= 20*1024
        report['completed'] = True
    except Exception as error:
        report['failure'] = str(error)
        raise
    finally:
        if original_timeout == 'null':
            run('shell', 'settings', 'delete', 'system', 'screen_off_timeout')
        else:
            run('shell', 'settings', 'put', 'system', 'screen_off_timeout', original_timeout)
        report['screenOffTimeoutRestored'] = True
        save()


if __name__ == '__main__':
    main()
