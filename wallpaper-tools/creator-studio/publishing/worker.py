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
import traceback
import uuid
from identity import ACCOUNT_CARD, IdentityError, choose_account_metrics, read_identity
from settings import SettingsError, apply_settings, unique, validate_settings, wait_checked
from approval import consume as consume_operator_approval

os.umask(0o077)
OUTPUT = sys.stdout


def emit(event, **values):
    OUTPUT.write('CREATOR_EVENT ' + json.dumps({'event': event, **values}, ensure_ascii=False) + '\n')
    OUTPUT.flush()


class NeedsInput(SettingsError):
    pass


IMAGE_UPLOAD_SELECTOR = ','.join('input[type=file][accept*="' + value + '" i]'
    for value in ('image', '.jpg', '.jpeg', '.png', '.webp', '.heic', '.avif'))


def douyin_title_selector(kind):
    return 'input[placeholder="添加作品标题"]' if kind == 'image' else 'input[placeholder*="填写作品标题"]'


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
    else:
        from uploader.xiaohongshu_uploader import main as upstream
    from loguru import logger
    logger.remove()
    # Reuse SAU's QR selectors, keeping sign-in and identity verification in the
    # same Chrome context. Do not import an unrelated user's browser profile.
    upstream.print_terminal_qrcode = lambda *a, **kw: None
    async def qr(info):
        image_path = Path(info['image_path'])
        if image_path.exists() and image_path.stat().st_size <= 1024 * 1024:
            emit('qr', qr='data:image/png;base64,' + base64.b64encode(image_path.read_bytes()).decode())
    emit('progress',message='正在打开本机 Chrome；扫码窗口等待最多10分钟，如有短信或身份验证，请在平台窗口完成'
        if request['mode']=='login' else '正在用本机已保存的登录信息检查账号，无需扫码…')
    from patchright.async_api import async_playwright
    with tempfile.TemporaryDirectory(dir=request['runtime'], prefix='login-') as folder:
        path = Path(folder) / 'state.json'
        async with async_playwright() as runtime:
            try:
                browser=await runtime.chromium.launch(headless=request['mode'] != 'login',channel='chrome')
            except Exception as error:
                raise NeedsInput('无法打开本机 Chrome，请确认已安装并关闭异常窗口后重新登录') from error
            try:
                context=await browser.new_context(**({'storage_state':vault.read()} if request['mode']=='check' else {}))
                page=await context.new_page()
                if request['mode']=='login':
                    url='https://creator.douyin.com/' if account['platform']=='douyin' else 'https://creator.xiaohongshu.com/login'
                    await page.goto(url,wait_until='domcontentloaded',timeout=90000)
                    if account['platform']=='douyin':
                        info=await upstream._save_douyin_qrcode(page,str(path),qrcode_callback=qr)
                        result=await upstream._wait_for_douyin_login(page,str(path),info,qrcode_callback=qr,poll_interval=2,max_checks=300)
                        if not result.get('success'):
                            return {'status':'expired','message':'等待扫码登录超时，请重新打开登录；如有短信或身份验证，请在 Chrome 窗口完成'}
                    else:
                        await upstream._save_xhs_qrcode(page,str(path),qrcode_callback=qr)
                        for _ in range(300):
                            if await upstream._is_xhs_login_completed(page):
                                break
                            await asyncio.sleep(2)
                        else:
                            return {'status':'expired','message':'等待小红书扫码登录超时，请重新打开登录'}
                emit('progress',message='登录已完成，正在核对账号身份；如首页未显示账号号码，请在 Chrome 中展开自己的账号卡片。窗口保留两分钟供操作。'
                    if request['mode']=='login' else '正在检查保存的登录状态和平台账号身份…')
                try:
                    identity=await read_identity(page,account['platform'],account.get('platformUserId'),
                        timeout=120000 if request['mode']=='login' else 20000)
                except IdentityError as error:
                    # Keep only a viewport diagnostic, in the authenticated local
                    # operation response. Never capture cookies or storage state.
                    diagnostic=None
                    try:
                        picture=await page.screenshot(type='jpeg',quality=65)
                        if len(picture)<=1024*1024:
                            diagnostic='data:image/jpeg;base64,'+base64.b64encode(picture).decode()
                    except Exception:
                        pass
                    return {'status':'needs_input','message':str(error),'diagnostic':diagnostic}
                # Identity must be accepted by the backend before credentials replace
                # the encrypted account session. No plaintext storage-state file.
                emit('identity',identity=identity)
                if (await asyncio.to_thread(sys.stdin.readline)).strip()!='ok':
                    raise NeedsInput('账号身份未通过核对，原登录信息未被覆盖')
                vault.save(await context.storage_state())
                name=identity['nickname'] or identity['platformUserId'].split(':',1)[1]
                message=('登录信息已保存，已核对平台账号：'+name+'。扫码窗口会自动关闭，后续发布可复用登录。'
                    if request['mode']=='login' else '保存的登录状态有效，已核对平台账号：'+name)
                return {'status':'ready','identity':identity,'message':message}
            finally:
                await browser.close()


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
    # The video form also contains AI recommendation images. Anchor the
    # actual portrait control by its visible aspect-ratio label, rather than
    # the first image with "cover" in a generated CSS class.
    heading = await unique(page.get_by_text('竖封面3:4', exact=True))
    if heading is None:
        raise NeedsInput('平台没有可核对的竖封面入口，已停止提交')
    area = heading.locator('../..')
    trigger = await unique(area.get_by_text('选择封面', exact=True))
    if trigger is None:
        raise NeedsInput('平台封面入口已变化，已停止提交')
    await trigger.click()
    modal = page.locator('div.dy-creator-content-modal')
    await modal.wait_for(state='visible')
    for label, path in [('设置竖封面', portrait), ('设置横封面', landscape)]:
        if not path:
            continue
        # The native dialog repeats "设置横封面" on its next-step button;
        # select only the observed header step, never an ambiguous text match.
        step = await unique(modal.locator('div[class*="step-"]').filter(has_text=re.compile('^'+label+'$')))
        if step is None:
            raise NeedsInput('平台没有可核对的封面切换标签，已停止提交')
        await step.click()
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
    # Tiptap removes the empty paragraph's placeholder after the first input.
    # Keep a stable editor locator for filling, topics, and final readback.
    editor = await unique(page.locator('[contenteditable=true]'))
    if editor is None:
        raise NeedsInput('平台没有可编辑的正文区域，已停止提交')
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
    title = page.locator(douyin_title_selector(post['type'])).first
    await title.fill(post['title'])
    editor = page.locator('div.zone-container[contenteditable=true]').first
    await editor.fill(post.get('body', ''))
    await editor.press('End')
    for tag in post['tags']:
        await page.keyboard.insert_text(' #' + tag)
        await page.keyboard.press('Space')
    await page.keyboard.press('Escape')
    return title, editor


async def dismiss_publish_hints(page):
    guide = await unique(page.locator('button.feature-guide__btn').filter(has_text=re.compile(r'^我知道了$')))
    if guide is not None:
        await guide.click()
    # The observed cover-PK onboarding notice blocks the title/editor. This is
    # only an informational acknowledgement, never a terms or verification step.
    notices = [item for item in await page.get_by_role('button', name='我知道了', exact=True)
               .and_(page.locator('button.pk-cover-guide-confirm')).all()
               if await item.is_visible()]
    if len(notices) > 1:
        raise NeedsInput('平台出现多个提示窗口，请先在平台关闭提示后重试')
    if notices:
        await notices[0].click()


async def save_publish_diagnostic(page, request, phase, submitted, error=None):
    """Keep one bounded troubleshooting screenshot locally, outside business payloads."""
    try:
        job_id = str(uuid.UUID(request['job']['id']))
        folder = Path(request['runtime']) / 'diagnostics'
        folder.mkdir(exist_ok=True, mode=0o700)
        # Slow/offline fonts or screenshot failure must not discard control
        # metadata needed to diagnose a stopped, unsubmitted task.
        with contextlib.suppress(Exception):
            picture = await page.screenshot(type='jpeg', quality=65, full_page=False, timeout=5000)
            if picture and len(picture) <= 1024 * 1024:
                (folder / (job_id + '.jpg')).write_bytes(picture)
        controls = []
        if hasattr(page, 'evaluate'):
            with contextlib.suppress(Exception):
                controls = await page.evaluate('''() => Array.from(document.querySelectorAll('input,textarea,[contenteditable=true],button,select,label,[role=combobox],[role=radio],[role=radiogroup]'))
                    .filter(e=>e.type==='file'||e.getClientRects().length).slice(0,80)
                    .map(e=>({tag:e.tagName,type:e.type||'',placeholder:e.getAttribute('placeholder')||'',
                        accept:e.getAttribute('accept')||'',role:e.getAttribute('role')||'',
                        editable:e.getAttribute('contenteditable')||'',classes:String(e.className||'').slice(0,200),
                        checked:['radio','checkbox'].includes(e.type)?e.checked:null,
                        ariaChecked:e.getAttribute('aria-checked'),
                        label:['BUTTON','LABEL','SELECT'].includes(e.tagName)||e.hasAttribute('role')?(e.innerText||'').slice(0,100):'',
                        parent:e.type==='checkbox'?{tag:e.parentElement.tagName,classes:String(e.parentElement.className||''),text:(e.parentElement.innerText||'').slice(0,100)}:null,
                        field:e.type==='checkbox'?Array.from((function*(n){for(let i=0;n&&i<4;i++,n=n.parentElement)yield n})(e.parentElement))
                            .filter(n=>n.innerText&&n.innerText.length<500).map(n=>({classes:String(n.className||'').slice(0,200),text:n.innerText.slice(0,200)})):null}))''')
        diagnostic = {'phase': phase, 'submitted': submitted, 'controls': controls}
        if hasattr(page, 'evaluate'):
            with contextlib.suppress(Exception):
                diagnostic['visibilityControls'] = await page.evaluate('''() => Array.from(document.querySelectorAll('*'))
                    .filter(e=>e.getClientRects().length&&/^(公开可见|公开|仅自己可见|仅自己|私密|好友可见|更多设置)$/.test((e.innerText||'').trim())
                        &&!Array.from(e.children).some(c=>/^(公开可见|公开|仅自己可见|仅自己|私密|好友可见|更多设置)$/.test((c.innerText||'').trim())))
                    .slice(0,20).map(e=>({text:e.innerText,html:e.parentElement.outerHTML.slice(0,2400)}))''')
            with contextlib.suppress(Exception):
                diagnostic['navigation'] = await page.evaluate('''() => Array.from(document.querySelectorAll('a[href]'))
                    .filter(e=>e.getClientRects().length&&/^(笔记管理|内容管理)$/.test((e.innerText||'').trim()))
                    .map(e=>({label:e.innerText.trim(),path:new URL(e.href).pathname}))''')
        if error is not None:
            diagnostic['failure'] = {'type': type(error).__name__, 'frames': [
                {'file': Path(frame.filename).name, 'line': frame.lineno, 'function': frame.name}
                for frame in traceback.extract_tb(error.__traceback__)[-6:]]}
        (folder / (job_id + '.json')).write_text(json.dumps(
            diagnostic, ensure_ascii=False), encoding='utf-8')
    except Exception:
        # Diagnostics must not alter submission/retry classification.
        pass


async def publish(request):
    from patchright.async_api import async_playwright
    from loguru import logger
    logger.remove()
    job, paths = request['job'], request['paths']
    post, platform = job['post'], job['platform']
    vault = SessionVault(request['runtime'], job['accountId'])
    saved_state = vault.read()
    submitted = False
    phase = '打开发布页'
    browser = None
    page = None
    runtime = None
    try:
        validate_settings(platform, post)
        runtime = await async_playwright().start()
        browser = await runtime.chromium.launch(headless=False, channel='chrome')
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
            selector = IMAGE_UPLOAD_SELECTOR if post['type'] == 'image' else 'input[type=file]'
            upload = page.locator(selector).first
            title_selector = douyin_title_selector(post['type'])
        else:
            await page.goto('https://creator.xiaohongshu.com/publish/publish?from=homepage&target=' + post['type'], wait_until='domcontentloaded', timeout=90000)
            upload = page.locator(IMAGE_UPLOAD_SELECTOR).first if post['type'] == 'image' else page.locator('input.upload-input').first
            title_selector = 'input[placeholder*="填写标题"]'
        if '/login' in page.url:
            raise NeedsInput('账号登录已失效，请重新扫码')
        phase = '上传素材'
        emit('progress', message=phase)
        await save_publish_diagnostic(page, request, phase, submitted)
        await upload.set_input_files(files, timeout=120000)
        phase = '等待平台处理素材'
        emit('progress', message=phase)
        await save_publish_diagnostic(page, request, phase, submitted)
        await page.locator(title_selector).first.wait_for(state='visible', timeout=600000)
        if post['type'] == 'video':
            uploaded = page.get_by_text(re.compile('重新上传|上传成功')).first
            await uploaded.wait_for(state='visible', timeout=600000)
        phase = '填写标题、正文和话题'
        emit('progress', message=phase)
        await dismiss_publish_hints(page)
        title, editor = await (fill_douyin(page, post) if platform == 'douyin' else fill_xhs(page, post))
        phase = '设置并核对发布选项'
        emit('progress', message=phase)
        async def confirm_originality(current):
            heading = await unique(current.get_by_text('笔记完成原创声明后，将获得以下权益', exact=True))
            if heading is None:
                raise NeedsInput('平台原创须知弹窗已变化，尚未提交')
            modal = None
            for depth in range(1, 5):
                candidate = heading.locator('/'.join(['..'] * depth))
                if await candidate.get_by_text('我已阅读并同意', exact=False).count():
                    modal = candidate
                    break
            if modal is None:
                raise NeedsInput('未找到可核对的原创须知，尚未提交')
            agreement = await unique(modal.locator('.d-checkbox').filter(has=page.get_by_text('我已阅读并同意', exact=False)))
            confirm = await unique(modal.get_by_role('button', name='声明原创', exact=True))
            if agreement is None or confirm is None or not consume_operator_approval(request['runtime'], job):
                raise NeedsInput('原创声明需要确认《原创声明须知》，请完成本次确认后重试；尚未提交')
            await agreement.click()
            if not await wait_checked(agreement):
                raise NeedsInput('原创须知未被勾选，尚未提交')
            await confirm.click()
            await heading.wait_for(state='hidden', timeout=8000)
        verify_settings = await apply_settings(page, platform, post, confirm_originality if platform == 'xhs' else None)
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
        await verify_settings()
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
        # Preserve the platform's actual receipt/result page for local acceptance
        # checks. A receipt still does not mean an approved/published work.
        phase = '平台已接收提交，待核对审核结果'
        if platform == 'xhs':
            with contextlib.suppress(Exception):
                management = await unique(page.get_by_text('笔记管理', exact=True))
                if management is not None:
                    await management.click()
        with contextlib.suppress(Exception):
            await page.get_by_text(post['title'], exact=True).first.wait_for(state='visible', timeout=10000)
        await save_publish_diagnostic(page, request, phase, submitted)
        vault.save(await context.storage_state())
        # XHS redirects its receipt to the upload page; that is not a work link.
        result_url = 'https://creator.xiaohongshu.com/new/note-manager' if platform == 'xhs' else page.url
        await context.close()
        await browser.close()
        browser = None
        return {'status': 'submitted', 'message': '平台已接收提交，请在平台确认审核结果', 'url': result_url}
    except Exception as error:
        if page:
            await save_publish_diagnostic(page, request, phase, submitted, error)
        network_failure = any(code in str(error) for code in (
            'net::ERR_CONNECTION_CLOSED', 'net::ERR_CONNECTION_RESET',
            'net::ERR_INTERNET_DISCONNECTED', 'net::ERR_NAME_NOT_RESOLVED',
            'net::ERR_CONNECTION_TIMED_OUT'))
        # Never retry after the final click, regardless of a timeout or challenge.
        return {'status': 'uncertain' if submitted else 'needs_input' if isinstance(error, (NeedsInput,IdentityError,SettingsError)) else 'failed',
                'message': ('已点击发布，结果尚未确认，请到平台核对' if submitted else '平台页面连接失败，请检查网络后重试；尚未提交' if network_failure else str(error) if isinstance(error, (NeedsInput,IdentityError,SettingsError)) else f'{phase}未完成；可能需要登录验证或平台页面已变化，请在账号管理重新登录后重试')}
    finally:
        if browser:
            with contextlib.suppress(Exception):
                await browser.close()

        if runtime:
            with contextlib.suppress(Exception):
                await runtime.stop()

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
        browser = await runtime.chromium.launch(headless=True, channel='chrome')
        try:
            context = await browser.new_context(storage_state=vault.read())
            page = await context.new_page()
            if not account.get('platformUserId'):
                raise NeedsInput('请先检查平台账号身份，再同步数据')
            await read_identity(page,account['platform'],account['platformUserId'])
            values.update(choose_account_metrics(await page.evaluate(ACCOUNT_CARD, account['platform']), account['platformUserId']))
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
        result = await asyncio.wait_for(operation, timeout=1800 if request['mode'] == 'publish' else 900 if request['mode'] == 'login' else 420)
    except (NeedsInput,IdentityError,SettingsError) as error:
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
