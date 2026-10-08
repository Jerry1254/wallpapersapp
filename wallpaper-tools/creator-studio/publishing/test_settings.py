"""Offline browser fixtures: no platform login, upload, or publish requests."""
import unittest
from settings import SettingsError, apply_settings, validate_settings


class ContractTests(unittest.TestCase):
    def test_platform_and_media_capabilities(self):
        for platform in ('douyin', 'xhs'):
            for kind in ('image', 'video'):
                post = {'type': kind, 'visibility': 'private', 'declaration': '虚构演绎，仅供娱乐'}
                if platform == 'xhs':
                    post['originality'] = 'original'
                if platform == 'douyin' and kind == 'video':
                    post['downloadPermission'] = 'deny'
                validate_settings(platform, post)
        for platform, post in (
            ('xhs', {'type': 'video', 'declaration': '内容由AI生成'}),
            ('douyin', {'type': 'image', 'downloadPermission': 'allow'}),
            ('douyin', {'type': 'video', 'originality': 'original'}),
            ('xhs', {'type': 'image', 'visibility': 'draft'}),
            ('xhs', {'type': 'video', 'originality': True}),
            ('xhs', {'type': 'image', 'saveAsDraft': True}),
        ):
            with self.assertRaises(SettingsError):
                validate_settings(platform, post)


try:
    from patchright.async_api import async_playwright
except ImportError:
    async_playwright = None


VISIBILITY = '''<fieldset><legend>谁可以看</legend>
  <label><input type="radio" name="v" value="public" checked>公开</label>
  <label><input type="radio" name="v" value="private">仅自己可见</label>
  <label><input type="radio" name="v" value="friends">好友可见</label>
</fieldset>'''


@unittest.skipIf(async_playwright is None, 'Run with the local publishing assistant Python environment')
class BrowserTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.runtime = await async_playwright().start()
        self.browser = await self.runtime.chromium.launch(headless=True, channel='chromium')
        self.page = await self.browser.new_page()
        self.page.set_default_timeout(5000)
        await self.page.route('**/*', lambda route: route.abort())

    async def asyncTearDown(self):
        await self.browser.close()
        await self.runtime.stop()

    async def test_four_forms_apply_choices_and_recheck_before_submit(self):
        for platform in ('douyin', 'xhs'):
            for kind in ('image', 'video'):
                await self.page.set_content(VISIBILITY + '''
                    <label><input id="original" type="checkbox">声明原创</label>
                    <label><input id="download" type="checkbox" checked>允许保存视频</label>''')
                post = {'type': kind, 'visibility': 'private'}
                if platform == 'xhs':
                    post['originality'] = 'original'
                if platform == 'douyin' and kind == 'video':
                    post['downloadPermission'] = 'deny'
                verify = await apply_settings(self.page, platform, post)
                await verify()
                self.assertTrue(await self.page.get_by_role('radio', name='仅自己可见', exact=True).is_checked())
                self.assertEqual(await self.page.locator('#original').is_checked(), platform == 'xhs')
                self.assertEqual(await self.page.locator('#download').is_checked(), not (platform == 'douyin' and kind == 'video'))
                await self.page.get_by_role('radio', name='公开', exact=True).check()
                with self.assertRaisesRegex(SettingsError, '未保留'):
                    await verify()

    async def test_native_select_and_unlabelled_field_container(self):
        await self.page.set_content('''<div><span>可见范围</span><select>
            <option value="all">公开</option><option value="self">仅自己可见</option>
            <option value="mutual">好友可见</option></select></div>''')
        verify = await apply_settings(self.page, 'xhs', {'type': 'video', 'visibility': 'friends'})
        self.assertEqual(await self.page.locator('select').input_value(), 'mutual')
        await verify()

    async def test_nonoriginal_unchecks_only_the_original_control(self):
        await self.page.set_content('''<label><input type="checkbox" id="original" checked>声明原创</label>
            <label><input type="checkbox" id="repost">来源转载</label>''')
        verify = await apply_settings(self.page, 'xhs', {'type': 'image', 'originality': 'not_original'})
        await verify()
        self.assertFalse(await self.page.locator('#original').is_checked())
        self.assertFalse(await self.page.locator('#repost').is_checked())

    async def test_xhs_native_visibility_dropdown_retains_exact_choice(self):
        await self.page.set_content('''<div class="d-select-description"
            onclick="document.getElementById('menu').style.display='block'">公开可见</div>
            <div id="menu" style="display:none">
              <div class="group-info"><div class="name" onclick="document.querySelector('.d-select-description').innerText=this.innerText;document.getElementById('menu').style.display='none'">仅自己可见</div></div>
              <div class="group-info"><div class="name">只给谁看</div></div></div>''')
        verify = await apply_settings(self.page, 'xhs', {'type':'image','visibility':'private'})
        self.assertEqual(await self.page.locator('.d-select-description').inner_text(),'仅自己可见')
        await verify()
        await self.page.locator('.d-select-description').evaluate('(e)=>e.innerText="公开可见"')
        with self.assertRaisesRegex(SettingsError,'未保留'):
            await verify()

    async def test_douyin_checkbox_single_choice_is_read_back(self):
        await self.page.set_content('''<div><span>谁可以看</span><div>
            <div onclick="document.querySelectorAll('.choice').forEach(e=>e.checked=false);this.querySelector('input').checked=true">
              <span><input class="choice" type="checkbox" checked></span><span>公开</span></div>
            <div onclick="document.querySelectorAll('.choice').forEach(e=>e.checked=false);this.querySelector('input').checked=true">
              <span><input id="private" class="choice" type="checkbox"></span><span>仅自己可见</span></div>
            </div></div>''')
        verify = await apply_settings(self.page, 'douyin', {'type':'video','visibility':'private'})
        self.assertTrue(await self.page.locator('#private').is_checked())
        await verify()
        await self.page.locator('.choice').first.evaluate('(e)=>e.checked=true')
        with self.assertRaisesRegex(SettingsError,'多个选中项'):
            await verify()

    async def test_missing_ambiguous_or_draft_only_visibility_stops(self):
        for html in ('<button>保存草稿</button>', VISIBILITY * 2,
                     '<fieldset><legend>谁可以看</legend><label><input type="radio">私密/草稿</label></fieldset>'):
            await self.page.set_content(html)
            with self.assertRaises(SettingsError):
                await apply_settings(self.page, 'douyin', {'type': 'image', 'visibility': 'private'})

    async def test_rejected_toggle_never_reports_success(self):
        await self.page.set_content('''<label><input type="checkbox" checked onclick="event.preventDefault()">允许保存视频</label>''')
        with self.assertRaisesRegex(SettingsError, '未保留'):
            await apply_settings(self.page, 'douyin', {'type': 'video', 'downloadPermission': 'deny'})

    async def declaration_form(self, platform, retain=True):
        label = '内容由AI生成' if platform == 'douyin' else '笔记含AI合成内容'
        trigger = '自主声明' if platform == 'douyin' else '添加内容类型声明'
        await self.page.set_content(f'''<button id="entry">{trigger}</button>
          <div id="dialog" role="dialog" class="semi-modal-content" style="display:none">
            <h2>请选择声明类型（单选）</h2>
            <label class="semi-radio"><input type="radio" name="d" value="none" checked>无需声明</label>
            <label class="semi-radio"><input type="radio" name="d" value="chosen">{label}</label>
            <button id="confirm">确定</button>
          </div><script>(()=>{{
            let saved='none'; const dialog=document.getElementById('dialog');
            document.getElementById('entry').onclick=()=>{{
              document.querySelector('[name=d][value='+saved+']').checked=true;dialog.style.display='block';
            }};
            document.getElementById('confirm').onclick=()=>{{
              if ({str(retain).lower()}) saved=document.querySelector('[name=d]:checked').value;
              dialog.style.display='none';
            }};
          }})();</script>''')
        return label

    async def test_declaration_is_reopened_to_check_persisted_selection(self):
        for platform in ('douyin', 'xhs'):
            for kind in ('image', 'video'):
                label = await self.declaration_form(platform)
                verify = await apply_settings(self.page, platform, {'type': kind, 'declaration': label})
                await verify()
                self.assertFalse(await self.page.locator('#dialog').is_visible())

    async def test_native_semi_declaration_checked_label_is_persisted_and_rechecked(self):
        label = await self.declaration_form('douyin')
        await self.page.evaluate("""() => {
            const options=Array.from(document.querySelectorAll('label.semi-radio'));
            let saved=0;
            const render=()=>options.forEach((el,i)=>{
                el.querySelector('input').checked=false;
                el.classList.toggle('semi-radio-checked',i===saved);
            });
            options.forEach((el,i)=>el.onclick=ev=>{ev.preventDefault();saved=i;render()});
            document.getElementById('entry').onclick=()=>{render();document.getElementById('dialog').style.display='block'};
            document.getElementById('confirm').onclick=()=>{document.getElementById('dialog').style.display='none'};
        }""")
        verify = await apply_settings(self.page, 'douyin', {'type':'video','declaration':label})
        await verify()
        self.assertFalse(await self.page.locator('input[type=radio]:checked').count())
        await self.page.locator('label.semi-radio').nth(1).evaluate("el=>el.classList.remove('semi-radio-checked')")
        # The next open renders persisted platform state, so changing only the
        # transient DOM cannot undo the committed selection.
        await verify()
        await self.page.evaluate("""() => {
            document.getElementById('entry').onclick=()=>{
                document.querySelectorAll('.semi-radio-checked').forEach(e=>e.classList.remove('semi-radio-checked'));
                document.getElementById('dialog').style.display='block';
            };
        }""")
        with self.assertRaisesRegex(SettingsError,'未保留'):
            await verify()

    async def test_douyin_declaration_prefers_placeholder_over_field_heading(self):
        label = await self.declaration_form('douyin')
        await self.page.evaluate('''() => {
            document.getElementById('entry').innerText='请选择自主声明';
            document.body.insertAdjacentHTML('afterbegin','<span>自主声明</span>');
        }''')
        verify = await apply_settings(self.page, 'douyin', {'type':'image','declaration':label})
        await verify()

    async def test_declaration_waits_for_native_asynchronous_radio_commit(self):
        label = await self.declaration_form('douyin')
        await self.page.locator('[name=d][value=chosen]').evaluate('''el=>{
            el.onclick=e=>{e.preventDefault();setTimeout(()=>{el.checked=true},100)};
        }''')
        verify = await apply_settings(self.page, 'douyin', {'type':'video','declaration':label})
        await verify()

    async def test_xhs_native_declaration_reads_select_not_preview(self):
        await self.page.set_content('''<div class="d-select-main" onclick="document.getElementById('menu').style.display='block'">
            <div class="d-select-description">添加内容类型声明</div></div>
            <span id="preview">虚构演绎，仅供娱乐</span>
            <div id="menu" style="display:none"><div class="d-option-name"
              onclick="event.stopPropagation();document.querySelector('.d-select-description').innerText=this.innerText;document.getElementById('menu').style.display='none'">虚构演绎，仅供娱乐</div></div>''')
        verify = await apply_settings(self.page, 'xhs', {'type':'video','declaration':'虚构演绎，仅供娱乐'})
        await verify()
        self.assertEqual(await self.page.locator('.d-select-description').inner_text(),'虚构演绎，仅供娱乐')
        await self.page.locator('.d-select-description').evaluate('el=>el.innerText="笔记含AI合成内容"')
        with self.assertRaisesRegex(SettingsError,'未保留'):
            await verify()

    async def test_xhs_unlabelled_originality_switch_requires_explicit_agreement(self):
        await self.page.set_content('''<div class="custom-switch-card"><span>原创声明</span>
            <div class="d-switch" onclick="this.querySelector('input').checked=true;document.getElementById('agreement').hidden=false">
            <input type="checkbox" onclick="event.preventDefault()"></div></div>
            <div id="agreement" hidden><span>我已阅读并同意《原创声明须知》</span><button>声明原创</button></div>''')
        with self.assertRaisesRegex(SettingsError,'原创声明须知'):
            await apply_settings(self.page, 'xhs', {'type':'image','originality':'original'})
        self.assertTrue(await self.page.locator('#agreement').is_visible())
        self.assertFalse(await self.page.get_by_role('button',name='声明原创',exact=True).evaluate('el=>!!el.dataset.clicked'))

    async def test_disabled_native_originality_stops_without_click_or_silent_optout(self):
        await self.page.set_content('''<div class="custom-switch-card"><span>原创声明</span>
            <div class="d-switch disabled" style="pointer-events:none"><input type="checkbox" disabled></div></div>''')
        with self.assertRaisesRegex(SettingsError,'平台已禁用原创声明'):
            await apply_settings(self.page,'xhs',{'type':'video','originality':'original'})
        self.assertFalse(await self.page.locator('input').is_checked())
        verify = await apply_settings(self.page,'xhs',{'type':'video','originality':'not_original'})
        await verify()

    async def test_worker_originality_approval_message_survives_settings_adapter(self):
        from worker import NeedsInput
        await self.page.set_content('''<div class="custom-switch-card"><span>原创声明</span>
            <div class="d-switch" onclick="this.querySelector('input').checked=true;document.getElementById('agreement').hidden=false">
            <input type="checkbox" onclick="event.preventDefault()"></div></div>
            <div id="agreement" hidden><span>我已阅读并同意《原创声明须知》</span><button>声明原创</button></div>''')

        async def awaiting_approval(page):
            raise NeedsInput('原创声明需要确认《原创声明须知》，请完成本次确认后重试；尚未提交')

        with self.assertRaisesRegex(NeedsInput, '原创声明须知'):
            await apply_settings(self.page, 'xhs', {'type': 'video', 'originality': 'original'}, awaiting_approval)
        self.assertTrue(await self.page.locator('#agreement').is_visible())
        self.assertFalse(await self.page.get_by_role('button', name='声明原创', exact=True).evaluate('el=>!!el.dataset.clicked'))

    async def test_closed_modal_is_not_enough_when_platform_discarded_declaration(self):
        for platform in ('douyin', 'xhs'):
            label = await self.declaration_form(platform, retain=False)
            with self.assertRaisesRegex(SettingsError, '未保留'):
                await apply_settings(self.page, platform, {'type': 'video', 'declaration': label})


if __name__ == '__main__':
    unittest.main()
