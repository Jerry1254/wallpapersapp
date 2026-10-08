"""Offline upload-control compatibility; no platform network or publication."""
import unittest
import tempfile
from pathlib import Path
import base64
from . import test_batch_ui as fixture
from .worker import IMAGE_UPLOAD_SELECTOR, dismiss_publish_hints, fill_xhs, fill_douyin, douyin_title_selector, cover_douyin, cover_xhs, NeedsInput


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

    async def test_douyin_two_cover_steps_ignore_ai_images_and_duplicate_next_button(self):
        await self.page.set_content("""<div class="cover-recommendation"><img><span>AI封面</span></div>
          <div><div><span onclick="document.querySelector('.dy-creator-content-modal').hidden=false">选择封面</span></div>
            <div><span>竖封面3:4</span></div></div>
          <div class="dy-creator-content-modal" hidden>
            <div class="step-native" onclick="window.activeCover='portrait'"><span>设置竖封面</span></div>
            <div class="step-native" onclick="window.activeCover='landscape'"><span>设置横封面</span></div>
            <button onclick="throw Error('incorrect next button')">设置横封面</button>
            <div class="semi-upload"><span class="semi-upload-drag-area-main-text">点击上传文件或拖拽文件到这里</span>
              <input class="semi-upload-hidden-input" type="file" onchange="document.querySelector('.dy-creator-content-modal').dataset[window.activeCover]=this.files[0].name;const reader=new FileReader();reader.onload=()=>document.querySelector('#preview').src=reader.result;reader.readAsDataURL(this.files[0])"></div>
            <img id="preview"><button onclick="this.parentElement.hidden=true">完成</button></div>""")
        with tempfile.TemporaryDirectory() as root:
            data=base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==')
            portrait=Path(root)/'portrait.png';landscape=Path(root)/'landscape.png'
            portrait.write_bytes(data);landscape.write_bytes(base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNg+M/wHwAEAQH/cetH5QAAAABJRU5ErkJggg=='))
            await cover_douyin(self.page,str(portrait),str(landscape))
        self.assertEqual(await self.page.locator('.dy-creator-content-modal').get_attribute('data-portrait'),'portrait.png')
        self.assertEqual(await self.page.locator('.dy-creator-content-modal').get_attribute('data-landscape'),'landscape.png')
        self.assertFalse(await self.page.locator('.dy-creator-content-modal').is_visible())

    async def test_xhs_processed_cover_without_pointer_ignores_ai_and_hidden_placeholder(self):
        await self.page.set_content('''<style>.default{position:relative}.cover-edit-stack{display:none;position:absolute;bottom:0}.default:hover .cover-edit-stack{display:block}</style><div class="cover-plugin-preview">
          <div class="default pointer" hidden onclick="throw Error('hidden placeholder')"></div>
          <div class="default column default--ai-cover-layout" style="width:80px;height:100px"><img style="width:80px;height:100px">
            <div class="cover-edit-stack"><div class="cover-edit-entry" onclick="document.querySelector('.d-modal').hidden=false"><span>编辑封面</span></div></div></div>
          <div class="ai-cover-preview-card" onclick="throw Error('AI recommendation')">推荐封面</div></div>
          <div class="d-modal" hidden><button>上传封面</button><div class="upload-wrapper">
            <input type="file" accept="image/*" onchange="const r=new FileReader();r.onload=()=>document.querySelector('#cover-preview').src=r.result;r.readAsDataURL(this.files[0])"></div>
            <img id="cover-preview"><div class="d-modal-footer"><button onclick="this.closest('.d-modal').hidden=true">确定</button></div></div>''')
        with tempfile.TemporaryDirectory() as root:
            path=Path(root)/'cover.png'
            path.write_bytes(base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg=='))
            await cover_xhs(self.page,str(path))
        self.assertFalse(await self.page.locator('.d-modal').is_visible())
        self.assertEqual(await self.page.locator('input[type=file]').evaluate('(e)=>e.files[0].name'),'cover.png')
        await self.page.set_content('<div class="cover-plugin-preview"><div class="ai-cover-preview-card">推荐封面</div></div>')
        with self.assertRaisesRegex(NeedsInput,'主封面入口'):
            await cover_xhs(self.page,'unused.png')

    async def test_xhs_modern_cover_upload_finishes_and_updates_main_preview(self):
        await self.page.set_content('''<div class="cover-plugin-preview"><div class="default" style="width:80px;height:100px">
          <button onclick="const m=document.querySelector('.main-cover-editor-modal');m.hidden=false;setTimeout(()=>m.replaceChildren(document.querySelector('#cover-controls').content.cloneNode(true)),250)">编辑封面</button>
          </div><div class="default artistic-bg">推荐封面</div></div>
          <div class="d-modal main-cover-editor-modal" hidden>加载中</div><template id="cover-controls"><label class="upload-btn">上传
            <input type="file" accept="image/png, image/jpeg, image/*" onchange="const r=new FileReader();r.onload=()=>document.querySelector('#uploaded').src=r.result;r.readAsDataURL(this.files[0])"></label>
            <img id="uploaded"><button onclick="document.querySelector('.default:not(.artistic-bg)').style.backgroundImage='url('+document.querySelector('#uploaded').src+')';this.closest('.d-modal').hidden=true">完成</button></template>''')
        with tempfile.TemporaryDirectory() as root:
            path=Path(root)/'cover.png'
            path.write_bytes(base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg=='))
            await cover_xhs(self.page,str(path))
        self.assertFalse(await self.page.locator('.main-cover-editor-modal').is_visible())
        self.assertTrue(await self.page.locator('.default:not(.artistic-bg)').evaluate('(e)=>getComputedStyle(e).backgroundImage.includes("data:image/png")'))

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
