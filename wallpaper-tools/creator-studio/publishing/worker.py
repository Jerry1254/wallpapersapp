"""Bounded SAU adapter. No automatic declaration, silent fallback or publish retry."""
import asyncio
import base64
import contextlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import uuid
from identity import IdentityError, read_identity

os.umask(0o077)
OUTPUT = sys.stdout


def emit(event, **values):
    OUTPUT.write('CREATOR_EVENT ' + json.dumps({'event': event, **values}, ensure_ascii=False) + '\n')
    OUTPUT.flush()


class NeedsInput(Exception):
    pass


class SessionVault:
    def __init__(self, root, account_id):
        from cryptography.fernet import Fernet
        self.root = Path(root)
        self.folder = self.root / 'sessions'
        self.folder.mkdir(exist_ok=True, mode=0o700)
        key = self.root / 'session.key'
        if not key.exists():
            with key.open('xb') as target:
                target.write(Fernet.generate_key())
        self.crypto = Fernet(key.read_bytes())
        self.path = self.folder / (str(uuid.UUID(account_id)) + '.enc')

    def read(self):
        if not self.path.exists():
            raise NeedsInput('账号尚未在本机登录，请先到账号管理扫码')
        return json.loads(self.crypto.decrypt(self.path.read_bytes()))

    def save(self, state):
        temporary = self.path.with_suffix('.tmp')
        temporary.write_bytes(self.crypto.encrypt(json.dumps(state).encode()))
        temporary.replace(self.path)


async def login(request):
    account = request['account']
    vault = SessionVault(request['runtime'], account['id'])
    if account['platform'] == 'douyin':
        from uploader.douyin_uploader import main as upstream
        setup = upstream.douyin_setup
    else:
        from uploader.xiaohongshu_uploader import main as upstream
        setup = upstream.xiaohongshu_setup
    from loguru import logger
    logger.remove()
    # Use the library's QR login, without injecting evasion scripts or printing QR secrets.
    async def unchanged(context):
        return context
    upstream.set_init_script = unchanged
    upstream.print_terminal_qrcode = lambda *a, **kw: None
    async def qr(info):
        image_path = Path(info['image_path'])
        if image_path.exists() and image_path.stat().st_size <= 1024 * 1024:
            emit('qr', qr='data:image/png;base64,' + base64.b64encode(image_path.read_bytes()).decode())
    emit('progress',message='正在检查登录；如出现短信或身份验证，请在平台窗口完成')
    with tempfile.TemporaryDirectory(dir=request['runtime'], prefix='login-') as folder:
        path = Path(folder) / 'state.json'
        if request['mode'] == 'check':
            path.write_text(json.dumps(vault.read()))
        value = await setup(str(path), handle=request['mode'] == 'login', return_detail=True, qrcode_callback=qr, headless=request['mode'] != 'login')
        if value.get('success') and path.exists():
            from patchright.async_api import async_playwright
            emit('progress',message='登录已完成，正在核对平台账号身份…')
            async with async_playwright() as runtime:
                browser=await runtime.chromium.launch(headless=True,channel='chromium')
                try:
                    context=await browser.new_context(storage_state=json.loads(path.read_text()))
                    identity=await read_identity(await context.new_page(),account['platform'],account.get('platformUserId'))
                    # The backend checks duplicate/mismatched identities before any
                    # replacement credentials are committed to the encrypted vault.
                    emit('identity',identity=identity)
                    if (await asyncio.to_thread(sys.stdin.readline)).strip()!='ok':
                        raise NeedsInput('账号身份未通过核对，原登录信息未被覆盖')
                    vault.save(await context.storage_state())
                finally:
                    await browser.close()
            return {'status':'ready','identity':identity,'message':'登录完成，已核对平台账号：'+(identity['nickname'] or identity['platformUserId'].split(':',1)[1])}
        return {'status': 'expired', 'message': '登录未完成或已过期，请重新扫码；如有验证，请在浏览器窗口完成'}


async def wait_new_cover(page, selector, previous):
    await page.wait_for_function('''({selector, previous}) => [...document.querySelectorAll(selector)]
        .some(img => img.complete && img.naturalWidth > 0 && img.src && !previous.includes(img.src))''',
        arg={'selector': selector, 'previous': previous}, timeout=60000)


async def cover_xhs(page, path):
    trigger = page.locator('div.upload-cover, div.cover-plugin-preview div.default.pointer').first
    await trigger.click()
    await page.get_by_text('上传封面', exact=True).first.click()
    previous = await page.locator('div.d-modal img').evaluate_all('(images) => images.map(img => img.src)')
    await page.locator('div.upload-wrapper input[type=file][accept*="image"]').first.set_input_files(path)
    await wait_new_cover(page, 'div.d-modal img', previous)
    modal = page.locator('div.d-modal').filter(has=page.locator('div.d-modal-footer')).first
    await modal.locator('div.d-modal-footer').get_by_text('确定', exact=True).click()
    await modal.wait_for(state='hidden', timeout=60000)


async def cover_douyin(page, portrait, landscape):
    area = page.locator('[class*="cover-"]').filter(has=page.locator('img')).first
    await area.hover()
    trigger = page.get_by_text(re.compile(r'^(编辑封面|选择封面|设置封面)$')).first
    if await trigger.is_visible():
        await trigger.click()
    else:
        await area.click()
    modal = page.locator('div.dy-creator-content-modal').first
    await modal.wait_for(state='visible')
    for label, path in [('设置竖封面', portrait), ('设置横封面', landscape)]:
        if not path:
            continue
        await modal.get_by_text(label, exact=True).click()
        previous = await modal.locator('img').evaluate_all('(images) => images.map(img => img.src)')
        upload = modal.locator('.semi-upload:has(.semi-upload-drag-area-main-text) input.semi-upload-hidden-input').first
        await upload.set_input_files(path)
        # Wait for the chosen image to load, rather than silently keeping the default.
        await wait_new_cover(page, 'div.dy-creator-content-modal img', previous)
    await modal.get_by_role('button', name='完成', exact=True).click(timeout=60000)
    await modal.wait_for(state='hidden', timeout=60000)


async def fill_xhs(page, post):
    title = page.locator('input[placeholder*="填写标题"]').first
    await title.fill(post['title'])
    editor = page.locator('[contenteditable=true]').filter(has=page.locator('p[data-placeholder*="输入正文描述"]')).first
    if not await editor.count():
        editor = page.locator('[contenteditable=true]').first
    await editor.fill(post.get('body', ''))
    await editor.press('End')
    for tag in post['tags']:
        await page.keyboard.insert_text(' #' + tag)
        menu = page.locator('#creator-editor-topic-container')
        await menu.wait_for(state='visible', timeout=10000)
        # An exact topic is required; never choose an unrelated first suggestion.
        match = menu.get_by_text(re.compile(r'^#?' + re.escape(tag) + r'$')).first
        await match.click(timeout=10000)
    await page.keyboard.press('Escape')
    return title, editor


async def fill_douyin(page, post):
    title = page.locator('input[placeholder*="填写作品标题"]').first
    await title.fill(post['title'])
    editor = page.locator('div.zone-container[contenteditable=true]').first
    await editor.fill(post.get('body', ''))
    await editor.press('End')
    for tag in post['tags']:
        await page.keyboard.insert_text(' #' + tag)
        await page.keyboard.press('Space')
    await page.keyboard.press('Escape')
    return title, editor


async def publish(request):
    from patchright.async_api import async_playwright
    from uploader.douyin_uploader.main import DouYinBaseUploader
    from loguru import logger
    logger.remove()
    class Declaration(DouYinBaseUploader):
        async def _clear_blocking_overlays(self, page):
            await page.keyboard.press('Escape')
    job, paths = request['job'], request['paths']
    post, platform = job['post'], job['platform']
    vault = SessionVault(request['runtime'], job['accountId'])
    saved_state = vault.read()
    submitted = False
    phase = '打开发布页'
    browser = None
    try:
        async with async_playwright() as runtime:
            browser = await runtime.chromium.launch(headless=False, channel='chromium')
            context = await browser.new_context(storage_state=saved_state)
            page = await context.new_page()
            page.set_default_timeout(30000)
            phase='核对发布账号'
            emit('progress',message=phase)
            if not job.get('platformUserId'):
                raise NeedsInput('请先在账号管理检查平台身份')
            await read_identity(page,platform,job['platformUserId'])
            files = [paths[item] for item in post['mediaIds']]
            emit('progress', message=phase)
            if platform == 'douyin':
                await page.goto('https://creator.douyin.com/creator-micro/content/upload', wait_until='domcontentloaded', timeout=90000)
                if post['type'] == 'image':
                    await page.get_by_text('发布图文', exact=True).click()
                selector = 'input[type=file][accept*="image"]' if post['type'] == 'image' else 'input[type=file]'
                upload = page.locator(selector).first
                title_selector = 'input[placeholder*="填写作品标题"]'
            else:
                await page.goto('https://creator.xiaohongshu.com/publish/publish?from=homepage&target=' + post['type'], wait_until='domcontentloaded', timeout=90000)
                upload = page.locator('input[type=file][accept*="image"]').first if post['type'] == 'image' else page.locator('input.upload-input').first
                title_selector = 'input[placeholder*="填写标题"]'
            if '/login' in page.url:
                raise NeedsInput('账号登录已失效，请重新扫码')
            phase = '上传素材'
            emit('progress', message=phase)
            await upload.set_input_files(files, timeout=120000)
            await page.locator(title_selector).first.wait_for(state='visible', timeout=600000)
            if post['type'] == 'video':
                uploaded = page.get_by_text(re.compile('重新上传|上传成功')).first
                await uploaded.wait_for(state='visible', timeout=600000)
            phase = '填写标题、正文和话题'
            emit('progress', message=phase)
            title, editor = await (fill_douyin(page, post) if platform == 'douyin' else fill_xhs(page, post))
            if post.get('declaration'):
                phase = '设置内容声明'
                helper = Declaration(0, '')
                if not await helper.set_self_declaration(page, post['declaration']):
                    raise NeedsInput('所选内容声明未设置成功，请在平台确认支持的声明')
                await page.locator('.semi-modal-content').filter(has_text='请选择声明类型').first.wait_for(state='hidden')
            portrait = paths.get(post.get('coverId'))
            landscape = paths.get(post.get('landscapeCoverId'))
            if portrait or landscape:
                phase = '设置封面'
                emit('progress', message=phase)
                if platform == 'douyin':
                    await cover_douyin(page, portrait, landscape)
                else:
                    await cover_xhs(page, portrait)
            phase = '检查待提交内容'
            if await title.input_value() != post['title']:
                raise NeedsInput('平台未完整保留标题，已停止提交，请调整后重试')
            rendered = await editor.inner_text()
            if post.get('body') and re.sub(r'\s+', '', post['body']) not in re.sub(r'\s+', '', rendered):
                raise NeedsInput('平台未完整保留正文，已停止提交')
            for tag in post['tags']:
                if tag not in rendered:
                    raise NeedsInput('平台未完整保留话题：' + tag)
            button = page.get_by_role('button', name='发布', exact=True)
            await button.wait_for(state='visible')
            if not await button.is_enabled():
                raise NeedsInput('平台尚未允许发布，请检查素材或必填项')
            # Persist the submission intent before a single click. Loss of the lease
            # prevents the click; a crash afterwards becomes an uncertain result.
            emit('commit')
            if (await asyncio.to_thread(sys.stdin.readline)).strip() != 'ok':
                raise RuntimeError('发布任务已停止')
            submitted = True
            await button.click()
            if platform == 'douyin':
                await page.wait_for_url('**/creator-micro/content/manage**', timeout=45000)
            else:
                await page.wait_for_url(re.compile(r'creator\.xiaohongshu\.com/(publish/success|publish/publish-success|new/note-manager)'), timeout=45000)
            vault.save(await context.storage_state())
            result_url = page.url
            await context.close()
            await browser.close()
            browser = None
            return {'status': 'submitted', 'message': '平台已接收提交，请在平台确认审核结果', 'url': result_url}
    except Exception as error:
        # Never retry after the final click, regardless of a timeout or challenge.
        return {'status': 'uncertain' if submitted else 'needs_input' if isinstance(error, (NeedsInput,IdentityError)) else 'failed',
                'message': ('已点击发布，结果尚未确认，请到平台核对' if submitted else str(error) if isinstance(error, (NeedsInput,IdentityError)) else f'{phase}未完成；可能需要登录验证或平台页面已变化，请在账号管理重新登录后重试')}
    finally:
        if browser:
            with contextlib.suppress(Exception):
                await browser.close()


async def analytics(request):
    from patchright.async_api import async_playwright
    account = request['account']
    vault = SessionVault(request['runtime'], account['id'])
    source = ('https://creator.douyin.com/creator-micro/home' if account['platform'] == 'douyin'
              else 'https://creator.xiaohongshu.com/new/home')
    labels = {'followers': ['粉丝总数', '总粉丝数', '累计粉丝'],
              'likes': ['总获赞数', '累计点赞', '累计获赞'],
              'favorites': ['总收藏数', '累计收藏'],
              'plays': ['累计播放量', '累计阅读量'],
              'comments': ['累计评论', '总评论数']}
    values = {}
    async with async_playwright() as runtime:
        browser = await runtime.chromium.launch(headless=True, channel='chromium')
        try:
            context = await browser.new_context(storage_state=vault.read())
            page = await context.new_page()
            if not account.get('platformUserId'):
                raise NeedsInput('请先检查平台账号身份，再同步数据')
            await read_identity(page,account['platform'],account['platformUserId'])
            if '/login' in page.url:
                raise NeedsInput('登录已过期，请重新扫码后同步数据')
            for key, alternatives in labels.items():
                for label in alternatives:
                    node = page.get_by_text(label, exact=True)
                    if await node.count() != 1:
                        continue
                    # Accept only an unambiguous metric card with one number.
                    # Comparative deltas, abbreviated counts and hidden values are omitted.
                    if not await node.is_visible():
                        continue
                    for depth in (1, 2):
                        card = node.locator('..' if depth == 1 else '../..')
                        text = (await card.inner_text()).strip()
                        clean = text.replace(label, '').strip()
                        if re.fullmatch(r'[\d,，]+', clean):
                            values[key] = int(clean.replace(',', '').replace('，', ''))
                            break
                    if key in values:
                        break
            vault.save(await context.storage_state())
            return {'status': 'ready', 'metrics': values, 'sourceUrl': source,
                    'message': f'已同步 {len(values)} 项累计指标；未明确读取的指标保持为空' if values else '平台未展示可明确读取的累计指标，已保留本次采集时间；可打开平台数据页查看'}
        finally:
            await browser.close()


async def main(request):
    try:
        operation = publish(request) if request['mode'] == 'publish' else analytics(request) if request['mode'] == 'analytics' else login(request)
        result = await asyncio.wait_for(operation, timeout=1800 if request['mode'] == 'publish' else 420)
    except (NeedsInput,IdentityError) as error:
        result = {'status': 'needs_input', 'message': str(error)}
    except asyncio.TimeoutError:
        # Parent tracks the submitting state and upgrades this on a late timeout.
        result = {'status': 'timeout', 'message': '操作超时，请核对平台状态'}
    except Exception:
        result = {'status': 'failed', 'message': '登录或发布环境暂不可用，请重新准备助手并扫码登录'}
    emit('result', **result)


if __name__ == '__main__':
    request = json.loads(sys.stdin.readline())
    # Upstream output may contain QR and temporary session paths. Do not forward it.
    with contextlib.redirect_stdout(sys.stderr):
        asyncio.run(main(request))
