import asyncio
import hashlib
import json
import math
import os
import subprocess
import sys
import wave
from pathlib import Path

import edge_tts

SAMPLE_RATE = 48000
VOICE = os.environ.get('TUTORIAL_VOICE', 'zh-CN-XiaoxiaoNeural')
RATE = '-5%'
LEAD_SECONDS = 0.35
TAIL_SECONDS = 0.45


def decode_audio(file):
    return subprocess.check_output([
        'ffmpeg', '-hide_banner', '-loglevel', 'error', '-i', str(file),
        '-af', 'loudnorm=I=-18:TP=-2:LRA=8', '-ar', str(SAMPLE_RATE),
        '-ac', '1', '-f', 's16le', '-c:a', 'pcm_s16le', '-',
    ])


async def main():
    plan_file, output_dir = map(Path, sys.argv[1:3])
    output_dir.mkdir(parents=True, exist_ok=True)
    clips_dir = output_dir / 'clips'
    clips_dir.mkdir(exist_ok=True)
    plan = json.loads(plan_file.read_text())
    result = []
    for tutorial in plan:
        sequence = bytearray()
        steps = []
        for step in tutorial['steps']:
            text = step['narration']
            digest = hashlib.sha256(f'{VOICE}|{RATE}|{text}'.encode()).hexdigest()
            clip = clips_dir / f'{digest}.mp3'
            if not clip.exists():
                temporary = clip.with_suffix('.partial.mp3')
                for attempt in range(3):
                    try:
                        await asyncio.wait_for(
                            edge_tts.Communicate(text, VOICE, rate=RATE).save(str(temporary)),
                            timeout=45,
                        )
                        temporary.replace(clip)
                        break
                    except Exception:
                        temporary.unlink(missing_ok=True)
                        if attempt == 2:
                            raise
            pcm = decode_audio(clip)
            spoken_seconds = len(pcm) / (SAMPLE_RATE * 2)
            seconds = max(step['seconds'], math.ceil(LEAD_SECONDS + spoken_seconds + TAIL_SECONDS))
            silence_bytes = seconds * SAMPLE_RATE * 2 - len(pcm)
            lead_bytes = round(LEAD_SECONDS * SAMPLE_RATE) * 2
            sequence.extend(bytes(lead_bytes))
            sequence.extend(pcm)
            sequence.extend(bytes(silence_bytes - lead_bytes))
            steps.append({**step, 'seconds': seconds, 'spokenSeconds': round(spoken_seconds, 3), 'voiceClip': clip.name})
        audio_file = output_dir / f"{tutorial['id']}.wav"
        with wave.open(str(audio_file), 'wb') as audio:
            audio.setnchannels(1)
            audio.setsampwidth(2)
            audio.setframerate(SAMPLE_RATE)
            audio.writeframes(sequence)
        result.append({**tutorial, 'steps': steps, 'voice': VOICE, 'audioFile': str(audio_file.resolve())})
        print(f"Chinese narration prepared: {tutorial['id']}, {sum(s['seconds'] for s in steps)} seconds", flush=True)
    (output_dir / 'timeline.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')


asyncio.run(main())
