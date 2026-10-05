"""Content frame encoding verifies a playable MP4 with an actual music track."""
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('content_server', Path(__file__).with_name('render-server.py'))
server = importlib.util.module_from_spec(spec)
spec.loader.exec_module(server)


class ContentRenderTests(unittest.TestCase):
    def test_frames_and_trimmed_faded_music_are_encoded(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            image, music = folder / 'frame.jpg', folder / 'music.wav'
            server.run([server.executable('ffmpeg'), '-v', 'error', '-y', '-f', 'lavfi', '-i', 'color=red:s=160x240', '-frames:v', '1', str(image)])
            server.run([server.executable('ffmpeg'), '-v', 'error', '-y', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=1', str(music)])
            job = {'version': 1, 'fps': 30, 'width': 160, 'height': 240, 'frames': 30,
                   'audio': [{'path': 'audio/0.wav', 'start': 0, 'duration': 1, 'sourceIn': .2, 'speed': 2,
                              'volume': .5, 'fadeIn': .1, 'fadeOut': .2, 'fill': 'loop'}]}
            payload = io.BytesIO()
            with zipfile.ZipFile(payload, 'w') as archive:
                archive.writestr('content.json', json.dumps(job))
                for i in range(30):
                    archive.write(image, f'frames/{i:05d}.jpg')
                archive.write(music, 'audio/0.wav')
            output = server.render_content(payload.getvalue(), folder)
            info = json.loads(server.run([server.executable('ffprobe'), '-v', 'error', '-count_frames', '-show_streams', '-of', 'json', str(output)]))
            video, audio = info['streams']
            self.assertEqual((video['width'], video['height'], int(video['nb_read_frames'])), (160, 240, 30))
            self.assertEqual(audio['codec_name'], 'aac')
            self.assertAlmostEqual(float(video['duration']), 1, places=4)

    def test_missing_frames_and_invalid_audio_paths_reject(self):
        with tempfile.TemporaryDirectory() as temporary:
            payload = io.BytesIO()
            with zipfile.ZipFile(payload, 'w') as archive:
                archive.writestr('content.json', json.dumps({'version': 1, 'fps': 30, 'width': 160, 'height': 240, 'frames': 1}))
            with self.assertRaises(ValueError):
                server.render_content(payload.getvalue(), Path(temporary))


if __name__ == '__main__':
    unittest.main()
