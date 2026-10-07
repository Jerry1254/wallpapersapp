"""Explicit publication choices, applied through visible controls and checked again.

Only semantic controls and the pinned uploader's observed Semi radio markup are
accepted. A changed or ambiguous platform form requires input, never a fallback
to public visibility, a different declaration, or a draft submission.
"""
import re


class SettingsError(Exception):
    pass


DECLARATIONS = {
    'douyin': ('内容由AI生成', '可能引人不适', '虚构演绎，仅供娱乐', '危险行为，请勿模仿',
               '内容为个人观点或见解', '内容含营销推广信息'),
    'xhs': ('虚构演绎，仅供娱乐', '笔记含AI合成内容'),
}
VISIBILITY = {'public': ('公开',), 'private': ('仅自己可见', '仅自己', '私密'),
              'friends': ('好友可见',)}
ORIGINALITY = {'original': ('原创', '声明原创'), 'not_original': ('非原创', '不声明原创')}
DOWNLOAD = {'allow': ('允许',), 'deny': ('不允许',)}
HEADINGS = ('谁可以看', '可见范围', '可见性设置', '保存权限', '允许保存视频',
            '允许他人保存视频', '允许下载', '原创声明', '声明原创', '类型')


def validate_settings(platform, post):
    if platform not in DECLARATIONS or post.get('type') not in ('image', 'video'):
        raise SettingsError('发布平台或内容类型无效')
    choices = {'visibility': tuple(VISIBILITY), 'declaration': ('', *DECLARATIONS[platform])}
    if platform == 'xhs':
        choices['originality'] = ('', *ORIGINALITY)
    if platform == 'douyin' and post['type'] == 'video':
        choices['downloadPermission'] = ('', *DOWNLOAD)
    for key in ('visibility', 'declaration', 'originality', 'downloadPermission'):
        if key in post and (key not in choices or not isinstance(post[key], str) or post[key] not in choices[key]):
            raise SettingsError('发布设置无效：' + key)
    if any(key in post for key in ('draft', 'saveAsDraft', 'publishMode')):
        raise SettingsError('此发布流程不支持保存草稿')


def exact(labels):
    return re.compile(r'^\s*(?:' + '|'.join(re.escape(label) for label in labels) + r')\s*$')


async def visible(locator):
    return [item for item in await locator.all() if await item.is_visible()]


async def unique(locator):
    items = await visible(locator)
    if len(items) > 1:
        raise SettingsError('平台出现多处同名设置，无法确认目标，已停止提交')
    return items[0] if items else None


async def checked(node):
    return await node.evaluate('''el => {
        const control = el.matches('input[type=radio],input[type=checkbox]') ? el
            : el.querySelector('input[type=radio],input[type=checkbox]');
        if (control) return control.checked;
        const value=el.getAttribute('aria-checked') ?? el.getAttribute('aria-pressed');
        return value==='true' ? true : value==='false' ? false : null;
    }''')


async def radio(scope, labels):
    node = await unique(scope.get_by_role('radio', name=exact(labels)))
    if node is None:
        # Observed in the pinned Douyin declaration dialog; no class-name guesses.
        node = await unique(scope.locator('label.semi-radio').filter(has_text=exact(labels)))
    return node


async def checkbox_choice(scope, labels):
    # Douyin renders its single-choice rows as native checkboxes. Match the
    # exact visible option next to that checkbox within the identified field.
    candidates = []
    for control in await scope.locator('input[type=checkbox]').all():
        for depth in (1, 2):
            parent = control.locator('/'.join(['..'] * depth))
            if await parent.is_visible() and (await parent.inner_text()).strip() in labels:
                candidates.append((parent, control))
                break
    if len(candidates) > 1:
        raise SettingsError('平台出现多处同名设置，无法确认目标，已停止提交')
    return candidates[0] if candidates else None


async def field(page, labels):
    for role in ('radiogroup', 'group'):
        node = await unique(page.get_by_role(role, name=exact(labels)))
        if node is not None:
            return node
    heading = await unique(page.get_by_text(exact(labels)))
    if heading is not None:
        for depth in range(1, 5):
            node = heading.locator('/'.join(['..'] * depth))
            tag = await node.evaluate('el => el.tagName')
            text = await node.inner_text()
            if tag in ('BODY', 'HTML') or len(text) > 700:
                break
            # Do not climb into an entire form with unrelated settings.
            unrelated = False
            for h in HEADINGS:
                if h not in labels and await visible(node.get_by_text(h, exact=True)):
                    unrelated = True
                    break
            if unrelated:
                break
            if await node.locator('select,[role=combobox],input[type=radio],[role=radio],input[type=checkbox],[role=switch],label.semi-radio').count():
                return node
    raise SettingsError('平台没有可核对的“' + labels[0] + '”设置，已停止提交')


async def select_choice(page, headings, labels):
    """Apply one radio/select choice and return a fresh readback function."""
    scope = await field(page, headings)
    select = await unique(scope.locator('select'))
    if select is not None:
        options = await select.locator('option').all()
        matches = [o for o in options if (await o.inner_text()).strip() in labels]
        if len(matches) != 1:
            raise SettingsError('平台不支持所选“' + headings[0] + '”，已停止提交')
        value = await matches[0].get_attribute('value')
        if value is None:
            value = (await matches[0].inner_text()).strip()
        await select.select_option(value=value)

        async def verify():
            current = await field(page, headings)
            target = await unique(current.locator('select'))
            if target is None or await target.input_value() != value:
                raise SettingsError(headings[0] + '未保留所选设置，已停止提交')
        await verify()
        return verify
    choice = await radio(scope, labels)
    native = await checkbox_choice(scope, labels) if choice is None else None
    if native is not None:
        choice, control = native
        if await checked(control) is not True:
            await choice.click()

        async def verify():
            current = await field(page, headings)
            target = await checkbox_choice(current, labels)
            if target is None or await checked(target[1]) is not True:
                raise SettingsError(headings[0] + '未保留所选设置，已停止提交')
            # A single-choice field must not retain another visibility at once.
            selected = sum([await checked(peer) is True
                            for peer in await current.locator('input[type=checkbox]').all()])
            if selected != 1:
                raise SettingsError(headings[0] + '出现多个选中项，已停止提交')
        await verify()
        return verify
    if choice is None:
        # Accessible custom selects: select only an exact named option.
        combo = await unique(scope.get_by_role('combobox'))
        if combo is None:
            raise SettingsError('平台不支持所选“' + headings[0] + '”，已停止提交')
        await combo.click()
        option = await unique(page.get_by_role('option', name=exact(labels)))
        if option is None:
            raise SettingsError('平台没有所选选项，已停止提交')
        await option.click()

        async def verify():
            current = await field(page, headings)
            control = await unique(current.get_by_role('combobox'))
            value = '' if control is None else await control.evaluate('el => el.value || el.innerText || ""')
            if value.strip() not in labels:
                raise SettingsError(headings[0] + '未保留所选设置，已停止提交')
    else:
        if await checked(choice) is not True:
            await choice.click()

        async def verify():
            current = await field(page, headings)
            target = await radio(current, labels)
            if target is None or await checked(target) is not True:
                raise SettingsError(headings[0] + '未保留所选设置，已停止提交')
    await verify()
    return verify


async def toggle_or_choice(page, headings, labels, enabled, toggle_labels):
    toggle = None
    for role in ('switch', 'checkbox'):
        toggle = await unique(page.get_by_role(role, name=exact(toggle_labels)))
        if toggle is not None:
            break
    if toggle is None:
        return await select_choice(page, headings, labels)
    state = await checked(toggle)
    if state is None:
        raise SettingsError(headings[0] + '无法核对，已停止提交')
    if state != enabled:
        await toggle.click()

    async def verify():
        target = await unique(page.get_by_role(role, name=exact(toggle_labels)))
        if target is None or await checked(target) != enabled:
            raise SettingsError(headings[0] + '未保留所选设置，已停止提交')
    await verify()
    return verify


async def xhs_visibility(page, value):
    # Observed XHS dropdown: its description is the retained selection, and
    # the opened menu uses group-info/name entries without ARIA option roles.
    options = {'public': ('公开可见',), 'private': ('仅自己可见',),
               'friends': ('仅互关好友可见',)}
    all_labels = tuple(label for labels in options.values() for label in labels)

    async def entry():
        return await unique(page.locator('.d-select-description').filter(has_text=exact(all_labels)))

    control = await entry()
    if control is None:
        return await select_choice(page, ('谁可以看', '可见范围', '可见性设置'), VISIBILITY[value])
    if (await control.inner_text()).strip() not in options[value]:
        await control.click()
        option = await unique(page.locator('.group-info .name').filter(has_text=exact(options[value])))
        if option is None:
            raise SettingsError('小红书没有所选可见范围，已停止提交')
        await option.click()

    async def verify():
        current = await entry()
        if current is None or (await current.inner_text()).strip() not in options[value]:
            raise SettingsError('谁可以看未保留所选设置，已停止提交')
    await verify()
    return verify


async def declaration(page, platform, value):
    # The native label may differ only in punctuation or AI capitalization.
    labels = tuple(dict.fromkeys((value, value.replace('，', ','), value.replace('，', ''), value.replace('AI', 'ai'))))
    entries = ('请选择自主声明', '请选择声明类型', '添加自主声明', '自主声明', '作品声明') if platform == 'douyin' else ('添加内容类型声明', '内容类型声明')

    async def open_dialog():
        entry = await unique(page.get_by_text(exact(entries)))
        if entry is None:
            # A saved declaration can replace the initial placeholder.
            entry = await unique(page.get_by_text(exact(labels)))
        if entry is None:
            raise SettingsError('平台未找到内容声明入口，已停止提交')
        await entry.click()
        dialog = (page.locator('.semi-modal-content').filter(has_text='请选择声明类型') if platform == 'douyin'
                  else page.get_by_role('dialog').filter(has=page.get_by_text(exact(labels))))
        await dialog.wait_for(state='visible', timeout=8000)
        return dialog

    async def confirm(dialog):
        button = await unique(dialog.get_by_role('button', name=exact(('确定', '确认'))))
        if button is None:
            raise SettingsError('内容声明无法确认，已停止提交')
        await button.click()
        await dialog.wait_for(state='hidden', timeout=8000)

    dialog = await open_dialog()
    option = await radio(dialog, labels)
    if option is None:
        raise SettingsError('平台没有可核对的所选声明，已停止提交')
    await option.click()
    if await checked(option) is not True:
        raise SettingsError('平台未选中所选声明，已停止提交')
    await confirm(dialog)

    async def verify():
        # Reopen the committed control. A successful click/closed modal alone
        # does not prove that the platform retained a declaration.
        current = await open_dialog()
        chosen = await radio(current, labels)
        if chosen is None or await checked(chosen) is not True:
            raise SettingsError('平台未保留所选声明，已停止提交')
        await confirm(current)
    await verify()
    return verify


async def apply_settings(page, platform, post):
    validate_settings(platform, post)
    checks = []
    label = ''
    try:
        if post.get('declaration'):
            label = '内容声明'
            checks.append((label, await declaration(page, platform, post['declaration'])))
        if post.get('originality'):
            label = '原创设置'
            checks.append((label, await toggle_or_choice(page, ('原创声明', '声明原创', '类型'), ORIGINALITY[post['originality']], post['originality'] == 'original', ('声明原创', '原创声明'))))
        if post.get('downloadPermission'):
            label = '允许保存视频'
            checks.append((label, await toggle_or_choice(page, ('保存权限', '允许保存视频', '允许下载'), DOWNLOAD[post['downloadPermission']], post['downloadPermission'] == 'allow', ('允许保存视频', '允许他人保存视频', '允许下载'))))
        if post.get('visibility'):
            label = '谁可以看'
            checks.append((label, await xhs_visibility(page, post['visibility']) if platform == 'xhs'
                           else await select_choice(page, ('谁可以看', '可见范围', '可见性设置'), VISIBILITY[post['visibility']])))
    except SettingsError:
        raise
    except Exception as error:
        raise SettingsError(label + '未完成，平台页面可能已变化，已停止提交') from error

    async def verify():
        for name, check in checks:
            try:
                await check()
            except SettingsError:
                raise
            except Exception as error:
                raise SettingsError(name + '无法再次核对，已停止提交') from error
    return verify
