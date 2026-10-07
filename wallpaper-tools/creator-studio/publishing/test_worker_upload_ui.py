"""Offline upload-control compatibility; no platform network or publication."""
import unittest
from . import test_batch_ui as fixture
from .worker import IMAGE_UPLOAD_SELECTOR, dismiss_publish_hints, fill_xhs, fill_douyin, douyin_title_selector


@unittest.skipIf(fixture.async_playwright is None, 'Requires local browser runtime')
class UploadSelectorTests(unittest.IsolatedAsyncioTestCase):
    asyncSetUp = fixture.BatchUITests.asyncSetUp
    asyncTearDown = fixture.BatchUITests.asyncTearDown

    async def test_xhs_editor_survives_empty_placeholder_removal(self):
        await self.page.set_content('''<input placeholder="填写标题会有更多赞哦">
            <div contenteditable="true" role="textbox" class="tiptap ProseMirror" style="min-height:100px"
                 oninput="this.querySelectorAll('[data-placeholder]').forEach(e=>e.removeAttribute('data-placeholder'))">
              <p data-placeholder="输入正文描述"></p></div>''')
        title, editor = await fill_xhs(self.page, {'title':'测试标题','body':'测试正文','tags':[]})
        self.assertEqual(await title.input_value(),'测试标题')
        self.assertEqual(await editor.inner_text(),'测试正文')
        self.assertEqual(await self.page.locator('[data-placeholder]').count(),0)

    async def test_douyin_image_and_video_use_their_actual_title_fields(self):
        for kind, placeholder in [('image','添加作品标题'),('video','填写作品标题，为作品获得更多流量')]:
            await self.page.set_content('<input placeholder="'+placeholder+'">'
                '<div class="zone-container" contenteditable="true" style="min-height:100px"></div>')
            await self.page.locator(douyin_title_selector(kind)).wait_for(state='visible')
            title, editor = await fill_douyin(self.page, {'type':kind,'title':'测试标题','body':'测试正文','tags':[]})
            self.assertEqual(await title.input_value(),'测试标题')
            self.assertEqual(await editor.inner_text(),'测试正文')

    async def test_cover_onboarding_is_dismissed_before_editing(self):
        await self.page.set_content('<input placeholder="填写标题">'
            '<div id="notice" style="position:fixed;inset:0;background:white;z-index:5">'
            '<p>视频可以PK封面啦</p><button class="pk-cover-guide-confirm" onclick="this.parentElement.remove()">我知道了</button></div>'
            '<button id="unrelated" onclick="throw Error(\'unexpected acknowledgement\')">我知道了</button>')
        await dismiss_publish_hints(self.page)
        await self.page.locator('input').fill('测试标题')
        self.assertEqual(await self.page.locator('#notice').count(),0)
        self.assertEqual(await self.page.locator('#unrelated').count(),1)
        self.assertEqual(await self.page.locator('input').input_value(),'测试标题')

    async def test_extension_only_image_input_preserves_order_and_skips_video(self):
        for accept in ('.jpg,.jpeg,.png', '.JPG,.PNG', 'image/*'):
            await self.page.set_content('<input id="video" type="file" accept=".mp4,.mov">'
                '<input id="pictures" style="display:none" type="file" multiple accept="'+accept+'">')
            selected=self.page.locator(IMAGE_UPLOAD_SELECTOR)
            self.assertEqual(await selected.count(),1)
            await selected.set_input_files([
                {'name':'first.png','mimeType':'image/png','buffer':b'test-first'},
                {'name':'second.png','mimeType':'image/png','buffer':b'test-second'}])
            self.assertEqual(await self.page.locator('#pictures').evaluate('(e)=>Array.from(e.files,f=>f.name)'),
                             ['first.png','second.png'])
            self.assertEqual(await self.page.locator('#video').evaluate('(e)=>e.files.length'),0)


if __name__ == '__main__':unittest.main()
