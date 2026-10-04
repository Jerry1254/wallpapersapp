import json
import statistics
import subprocess
import sys
from pathlib import Path

file = Path(sys.argv[1])
expected_seconds = float(sys.argv[2])
metadata = json.loads(subprocess.check_output([
    'ffprobe', '-v', 'error', '-show_streams', '-show_format', '-of', 'json', str(file),
]))
video = next(stream for stream in metadata['streams'] if stream['codec_type'] == 'video')
audio = next(stream for stream in metadata['streams'] if stream['codec_type'] == 'audio')
assert (video['width'], video['height'], video['r_frame_rate']) == (1080, 1920, '30/1')
assert audio['codec_name'] == 'aac'
assert abs(float(metadata['format']['duration']) - expected_seconds) < 0.1
assert abs(float(audio['duration']) - expected_seconds) < 0.1

# The brand/title never animate, so repeated tiles or missing paint are defects.
raw = subprocess.check_output([
    'ffmpeg', '-v', 'error', '-i', str(file),
    '-vf', 'crop=940:110:68:280,scale=94:11,format=gray',
    '-f', 'rawvideo', '-pix_fmt', 'gray', '-',
])
block = 94 * 11
frames = [raw[offset:offset + block] for offset in range(0, len(raw), block)]
assert len(frames) == round(expected_seconds * 30)
reference = bytes(round(statistics.median(frame[pixel] for frame in frames)) for pixel in range(block))
errors = [sum(abs(a - b) for a, b in zip(frame, reference)) / block for frame in frames]
bad_frames = [index for index, error in enumerate(errors) if error > 3.5]
assert not bad_frames, f'Unstable title frames: {bad_frames[:30]}'

# The phone frame stays visible across every step, including transition boundaries.
raw_phone = subprocess.check_output([
    'ffmpeg', '-v', 'error', '-i', str(file),
    '-vf', 'crop=540:1030:280:660,scale=54:103,format=gray',
    '-f', 'rawvideo', '-pix_fmt', 'gray', '-',
])
phone_block = 54 * 103
phone_density = [sum(pixel < 120 for pixel in raw_phone[offset:offset + phone_block]) / phone_block
                 for offset in range(0, len(raw_phone), phone_block)]
assert min(phone_density) > 0.06, 'Phone disappeared during a transition'
subprocess.run(['ffmpeg', '-v', 'error', '-i', str(file), '-f', 'null', '-'], check=True)
report = {'file': file.name, 'frames': len(frames), 'audio': 'AAC Chinese narration',
          'maxTitleDifference': round(max(errors), 3), 'unstableTitleFrames': len(bad_frames),
          'minimumPhoneDensity': round(min(phone_density), 3), 'decode': 'passed'}
file.with_suffix('.validation.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report))
