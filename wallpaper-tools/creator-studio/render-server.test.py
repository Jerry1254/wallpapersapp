"""Real MP4 checks: source ranges, speed, image frames, crop, and HTTP delivery."""
import copy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
import zipfile

spec = importlib.util.spec_from_file_location('creator_render', Path(__file__).with_name('render-server.py'))
server = importlib.util.module_from_spec(spec)
spec.loader.exec_module(server)


def payload(job, files):
    data = io.BytesIO()
    with zipfile.ZipFile(data, 'w') as archive:
        archive.writestr('timeline.json', json.dumps(job))
        for name, path in files.items():
            archive.write(path, name)
    return data.getvalue()


class RendererTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='creator-render-test-')
        cls.folder = Path(cls.temporary.name)
        cls.video = cls.folder / 'source.mp4'
        cls.image = cls.folder / 'still.png'
        server.run([server.executable('ffmpeg'), '-loglevel', 'error', '-y', '-f', 'lavfi', '-i', 'color=red:s=256x384:r=30:d=1',
                    '-f', 'lavfi', '-i', 'color=blue:s=256x384:r=30:d=1', '-f', 'lavfi', '-i', 'color=green:s=256x384:r=30:d=1',
                    '-filter_complex', '[0:v][1:v][2:v]concat=n=3:v=1:a=0', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', str(cls.video)])
        server.run([server.executable('ffmpeg'), '-loglevel', 'error', '-y', '-f', 'lavfi', '-i', 'color=white:s=256x384',
                    '-frames:v', '1', str(cls.image)])
        cls.job = {'version': 1, 'fps': 30, 'profile': {'width': 160, 'height': 240, 'scale': 1, 'x': 0, 'y': 0},
                   'sources': [{'path': 'media/0.png'}, {'path': 'media/1.mp4'}],
                   'clips': [{'source': 0, 'kind': 'image', 'start': 0, 'end': 10/30, 'speed': 1},
                             {'source': 1, 'kind': 'video', 'start': 1, 'end': 3, 'speed': 2}]}

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def inspect(self, output):
        return json.loads(server.run([server.executable('ffprobe'), '-v', 'error', '-count_frames', '-select_streams', 'v:0',
                                     '-show_entries', 'stream=width,height,r_frame_rate,nb_read_frames,duration,codec_name', '-of', 'json', str(output)]))['streams'][0]

    def rgb_frames(self, output):
        return server.run([server.executable('ffmpeg'), '-v', 'error', '-i', str(output), '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'])

    def test_image_ten_frames_then_trimmed_fast_video_and_real_mp4(self):
        data = payload(self.job, {'media/0.png': self.image, 'media/1.mp4': self.video})
        with tempfile.TemporaryDirectory(dir=self.folder) as temporary:
            output = server.render(data, Path(temporary))
            info = self.inspect(output)
            self.assertEqual((info['width'], info['height'], info['r_frame_rate'], info['codec_name']), (160, 240, '30/1', 'h264'))
            self.assertEqual(int(info['nb_read_frames']), 40)
            self.assertAlmostEqual(float(info['duration']), 40/30, places=4)
            raw = self.rgb_frames(output)
            def pixel(frame):
                offset = (frame * 160 * 240 + 120 * 160 + 80) * 3
                return tuple(raw[offset:offset+3])
            self.assertTrue(min(pixel(0)) > 245, pixel(0))
            self.assertTrue(pixel(10)[2] > 240 and pixel(10)[0] < 10, pixel(10))
            self.assertTrue(pixel(25)[1] > 110 and pixel(25)[0] < 10 and pixel(25)[2] < 10, pixel(25))

    def test_image_and_video_parts_keep_one_color_range_after_concat(self):
        colorful = self.folder / 'colorful.mp4'
        server.run([server.executable('ffmpeg'), '-v', 'error', '-y', '-f', 'lavfi', '-i',
                    'color=yellow:size=256x144:rate=30:duration=1', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', str(colorful)])
        jpeg = self.folder / 'full-range-still.jpg'
        server.run([server.executable('ffmpeg'), '-v', 'error', '-y', '-i', str(self.image), '-frames:v', '1', str(jpeg)])
        mixed = copy.deepcopy(self.job)
        mixed['sources'][0]['path'] = 'media/0.jpg'
        mixed['profile']['scale'] = 2
        mixed['clips'] = [{'source': 0, 'kind': 'image', 'start': 0, 'end': 1, 'speed': 1},
                          {'source': 1, 'kind': 'video', 'start': 0, 'end': 1, 'speed': 1}]
        files = {'media/0.jpg': jpeg, 'media/1.mp4': colorful}
        with tempfile.TemporaryDirectory(dir=self.folder) as temporary:
            together = server.render(payload(mixed, files), Path(temporary))
            frame_size = 160 * 240 * 3
            actual = self.rgb_frames(together)[45 * frame_size:46 * frame_size]
            reference = server.run([server.executable('ffmpeg'), '-v', 'error', '-i', str(colorful),
                                    '-vf', 'scale=854:480,crop=160:240', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'])
            expected = reference[15 * frame_size:16 * frame_size]
            differences = [sum(abs(a-b) for a, b in zip(actual[channel::3], expected[channel::3])) / (160 * 240)
                           for channel in range(3)]
            self.assertLess(max(differences), 3, differences)

    def test_crop_zoom_and_pan_match_the_selected_viewport(self):
        image = self.folder / 'half.png'
        server.run([server.executable('ffmpeg'), '-v', 'error', '-y', '-f', 'lavfi', '-i', 'color=red:s=256x128',
                    '-vf', 'drawbox=x=128:y=0:w=128:h=128:color=blue:t=fill', '-frames:v', '1', str(image)])
        job = copy.deepcopy(self.job)
        job['sources'] = [{'path': 'media/0.png'}]
        job['clips'] = job['clips'][:1]
        job['profile'].update(scale=2, x=50)
        with tempfile.TemporaryDirectory(dir=self.folder) as temporary:
            output = server.render(payload(job, {'media/0.png': image}), Path(temporary))
            raw = self.rgb_frames(output)
            offset = (120 * 160 + 80) * 3
            r, g, b = raw[offset:offset+3]
            self.assertTrue(r > 240 and g < 10 and b < 10, (r, g, b))

    def test_demo_motion_renders_as_a_real_video(self):
        job = copy.deepcopy(self.job)
        job['sources'] = [{'demo': True}]
        job['clips'] = [{'source': 0, 'kind': 'video', 'start': 1, 'end': 1.1, 'speed': 1}]
        with tempfile.TemporaryDirectory(dir=self.folder) as temporary:
            output = server.render(payload(job, {}), Path(temporary))
            self.assertEqual(int(self.inspect(output)['nb_read_frames']), 3)

    def test_rejects_invalid_source_paths_and_empty_timelines(self):
        for change in [lambda j: j.update(clips=[]), lambda j: j['sources'][0].update(path='../../secret.png')]:
            job = copy.deepcopy(self.job)
            change(job)
            with tempfile.TemporaryDirectory(dir=self.folder) as temporary:
                with self.assertRaises(ValueError):
                    server.read_job(payload(job, {}), Path(temporary))

    def test_endpoint_delivers_mp4_and_rejects_foreign_origin(self):
        http = server.ThreadingHTTPServer(('127.0.0.1', 0), server.Handler)
        thread = threading.Thread(target=http.serve_forever, daemon=True)
        thread.start()
        try:
            origin = f'http://127.0.0.1:{http.server_port}'
            job = copy.deepcopy(self.job)
            job['sources'] = job['sources'][:1]
            job['clips'] = job['clips'][:1]
            data = payload(job, {'media/0.png': self.image})
            request = urllib.request.Request(origin + '/creator-studio/api/render', data=data,
                       headers={'Origin': origin, 'X-Creator-Export': '1', 'Content-Type': 'application/zip'})
            with urllib.request.urlopen(request, timeout=30) as response:
                self.assertEqual(response.headers['Content-Type'], 'video/mp4')
                result = response.read()
                self.assertEqual(result[4:8], b'ftyp')
            request.add_header('Origin', 'https://example.com')
            with self.assertRaises(urllib.error.HTTPError) as rejected:
                urllib.request.urlopen(request)
            self.assertEqual(rejected.exception.code, 403)
            rejected.exception.close()
        finally:
            http.shutdown()
            http.server_close()
            thread.join()


if __name__ == '__main__':
    unittest.main()
