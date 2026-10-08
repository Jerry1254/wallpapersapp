"""Login lifetime and identity handshake; no real browser or platform access."""
import io
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import worker


class RuntimeContext:
    def __init__(self, runtime): self.runtime = runtime
    async def __aenter__(self): return self.runtime
    async def __aexit__(self, *args): pass


class LoginTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.page = SimpleNamespace(goto=AsyncMock(), screenshot=AsyncMock(return_value=b'test-jpeg'))
        self.state = {'cookies': [{'name': 'test-session', 'value': 'test-only'}], 'origins': []}
        self.context = SimpleNamespace(new_page=AsyncMock(return_value=self.page), storage_state=AsyncMock(return_value=self.state))
        self.browser = SimpleNamespace(new_context=AsyncMock(return_value=self.context), close=AsyncMock())
        self.runtime = SimpleNamespace(chromium=SimpleNamespace(launch=AsyncMock(return_value=self.browser)))
        self.upstream = SimpleNamespace(
            print_terminal_qrcode=Mock(),
            _save_douyin_qrcode=AsyncMock(return_value={'image_path': ''}),
            _wait_for_douyin_login=AsyncMock(return_value={'success': True}),
            _save_xhs_qrcode=AsyncMock(return_value={'image_path': ''}),
            _is_xhs_login_completed=AsyncMock(return_value=True),
        )
        self.vault = SimpleNamespace(read=Mock(return_value=self.state), save=Mock())
        self.identity = {'platformUserId': 'handle:qa-account', 'nickname': '验收账号', 'avatarUrl': ''}
        self.request = {'mode': 'login', 'runtime': self.folder.name, 'account': {'id': 'test-id', 'platform': 'douyin'}}
        modules = {
            'patchright.async_api': SimpleNamespace(async_playwright=lambda: RuntimeContext(self.runtime)),
            'uploader.douyin_uploader': SimpleNamespace(main=self.upstream),
            'uploader.xiaohongshu_uploader': SimpleNamespace(main=self.upstream),
            'loguru': SimpleNamespace(logger=SimpleNamespace(remove=Mock())),
        }
        self.addCleanup(patch.stopall)
        patch.dict(sys.modules, modules).start()
        patch.object(worker, 'SessionVault', return_value=self.vault).start()
        patch.object(worker, 'emit').start()
        self.read_identity = patch.object(worker, 'read_identity', new=AsyncMock(return_value=self.identity)).start()
        patch.object(worker.sys, 'stdin', io.StringIO('ok\n')).start()

    async def test_scan_and_identity_use_one_chrome_context_before_encrypted_save(self):
        result = await worker.login(self.request)
        self.assertEqual(result['status'], 'ready')
        self.assertIn('扫码窗口会自动关闭', result['message'])
        self.runtime.chromium.launch.assert_awaited_once_with(headless=False, channel='chrome')
        self.browser.new_context.assert_awaited_once_with()
        self.read_identity.assert_awaited_once_with(self.page, 'douyin', None, timeout=120000)
        self.assertEqual(self.upstream._wait_for_douyin_login.call_args.kwargs['max_checks'], 300)
        self.assertEqual(self.upstream._wait_for_douyin_login.call_args.kwargs['poll_interval'], 2)
        self.vault.save.assert_called_once_with(self.state)
        self.assertEqual(list(Path(self.folder.name).rglob('state.json')), [])
        self.browser.close.assert_awaited_once()

    async def test_identity_mismatch_never_replaces_existing_session(self):
        self.request['account']['platformUserId'] = 'handle:original'
        self.read_identity.side_effect = worker.IdentityError('账号不一致')
        result = await worker.login(self.request)
        self.assertEqual(result['status'], 'needs_input')
        self.assertEqual(result['message'], '账号不一致')
        self.assertTrue(result['diagnostic'].startswith('data:image/jpeg;base64,'))
        self.vault.save.assert_not_called()
        self.context.storage_state.assert_not_awaited()
        self.browser.close.assert_awaited_once()

    async def test_backend_rejection_never_replaces_existing_session(self):
        with patch.object(worker.sys, 'stdin', io.StringIO('rejected\n')):
            with self.assertRaises(worker.NeedsInput): await worker.login(self.request)
        self.vault.save.assert_not_called()
        self.browser.close.assert_awaited_once()

    async def test_scan_timeout_does_not_claim_ready_or_read_identity(self):
        self.upstream._wait_for_douyin_login.return_value = {'success': False}
        result = await worker.login(self.request)
        self.assertEqual(result['status'], 'expired')
        self.read_identity.assert_not_awaited()
        self.vault.save.assert_not_called()
        self.browser.close.assert_awaited_once()

    async def test_check_restores_only_this_accounts_encrypted_state(self):
        self.request['mode'] = 'check'
        result = await worker.login(self.request)
        self.assertIn('保存的登录状态有效', result['message'])
        self.assertNotIn('扫码窗口', result['message'])
        self.runtime.chromium.launch.assert_awaited_once_with(headless=True, channel='chrome')
        self.browser.new_context.assert_awaited_once_with(storage_state=self.state)
        self.upstream._wait_for_douyin_login.assert_not_awaited()
        self.read_identity.assert_awaited_once_with(self.page, 'douyin', None, timeout=20000)

    async def test_xhs_identity_uses_scanned_window_without_hidden_relogin(self):
        self.request['account']['platform'] = 'xhs'
        result = await worker.login(self.request)
        self.assertEqual(result['status'], 'ready')
        self.upstream._save_xhs_qrcode.assert_awaited_once()
        self.read_identity.assert_awaited_once_with(self.page, 'xhs', None, timeout=120000)
        self.runtime.chromium.launch.assert_awaited_once_with(headless=False, channel='chrome')

    async def test_failed_diagnostic_does_not_hide_identity_failure_or_save_credentials(self):
        self.read_identity.side_effect = worker.IdentityError('身份未确认')
        self.page.screenshot.side_effect = RuntimeError('closed')
        result = await worker.login(self.request)
        self.assertEqual(result, {'status': 'needs_input', 'message': '身份未确认', 'diagnostic': None})
        self.vault.save.assert_not_called()

    async def test_oversized_diagnostic_is_not_returned(self):
        self.read_identity.side_effect = worker.IdentityError('身份未确认')
        self.page.screenshot.return_value = b'x' * (1024 * 1024 + 1)
        result = await worker.login(self.request)
        self.assertIsNone(result['diagnostic'])
        self.vault.save.assert_not_called()


class AnalyticsTests(unittest.IsolatedAsyncioTestCase):
    setUp = LoginTests.setUp

    async def test_real_account_card_counts_are_saved_without_filling_missing_metrics(self):
        self.request['mode']='analytics'
        self.request['account'].update(platform='xhs', platformUserId='handle:qa-account')
        self.page.url='https://creator.xiaohongshu.com/new/home'
        self.page.evaluate=AsyncMock(return_value=[{'handle':'qa-account','metrics':{'followers':14}}])
        self.page.get_by_text=Mock(return_value=SimpleNamespace(count=AsyncMock(return_value=0)))
        result=await worker.analytics(self.request)
        self.assertEqual(result['metrics'],{'followers':14})
        self.vault.save.assert_called_once_with(self.state)

    async def test_account_change_between_identity_and_sampling_stops_sync(self):
        self.request['account']['platformUserId']='handle:qa-account'
        self.page.evaluate=AsyncMock(return_value=[{'handle':'another-account','metrics':{'followers':900}}])
        with self.assertRaises(worker.IdentityError):await worker.analytics(self.request)
        self.vault.save.assert_not_called()
        self.browser.close.assert_awaited_once()


class PublishLifetimeTests(unittest.IsolatedAsyncioTestCase):
    setUp = LoginTests.setUp

    async def prepare_publish(self):
        self.request.update(mode='publish', job={'id':'8c1ea09a-b84c-4b9f-b605-acec12c9f2d1',
            'platform':'xhs','accountId':'test-id','platformUserId':'handle:qa-account',
            'post':{'type':'image','title':'测试标题','body':'测试正文','tags':[],
                    'mediaIds':['file'],'visibility':'private'}}, paths={'file':'test.png'})
        self.runtime.stop=AsyncMock()
        patch.dict(sys.modules,{'patchright.async_api':SimpleNamespace(async_playwright=lambda:
            SimpleNamespace(start=AsyncMock(return_value=self.runtime)))}).start()
        self.page.set_default_timeout=Mock()
        self.page.url='https://creator.xiaohongshu.com/publish/publish'
        patch.object(worker, 'dismiss_publish_hints', new=AsyncMock()).start()

    async def test_failure_is_captured_before_browser_runtime_stops(self):
        await self.prepare_publish()
        self.page.locator=Mock(side_effect=RuntimeError('missing upload control'))
        async def capture(*args):
            self.browser.close.assert_not_awaited()
            self.runtime.stop.assert_not_awaited()
        with patch.object(worker,'save_publish_diagnostic',new=AsyncMock(side_effect=capture)) as diagnostic:
            result=await worker.publish(self.request)
        self.assertEqual(result['status'],'failed')
        diagnostic.assert_awaited_once()
        self.browser.close.assert_awaited_once()
        self.runtime.stop.assert_awaited_once()

    async def test_error_after_final_click_is_uncertain_and_never_reclicks(self):
        await self.prepare_publish()
        control=SimpleNamespace(set_input_files=AsyncMock(),wait_for=AsyncMock(),
            input_value=AsyncMock(return_value='测试标题'),inner_text=AsyncMock(return_value='测试正文'))
        control.first=control
        self.page.locator=Mock(return_value=control)
        button=SimpleNamespace(wait_for=AsyncMock(),is_enabled=AsyncMock(return_value=True),
            click=AsyncMock(side_effect=RuntimeError('connection lost')),all=AsyncMock(return_value=[]))
        button.and_=Mock(return_value=button)
        self.page.get_by_role=Mock(return_value=button)
        with patch.object(worker,'fill_xhs',new=AsyncMock(return_value=(control,control))), \
             patch.object(worker,'apply_settings',new=AsyncMock(return_value=AsyncMock())), \
             patch.object(worker,'save_publish_diagnostic',new=AsyncMock()) as diagnostic:
            result=await worker.publish(self.request)
        self.assertEqual(result['status'],'uncertain')
        button.click.assert_awaited_once()
        self.assertTrue(diagnostic.call_args.args[3])
        self.vault.save.assert_not_called()
        self.runtime.stop.assert_awaited_once()

    async def test_platform_receipt_remains_submitted_pending_verification(self):
        await self.prepare_publish()
        control=SimpleNamespace(set_input_files=AsyncMock(),wait_for=AsyncMock(),
            input_value=AsyncMock(return_value='测试标题'),inner_text=AsyncMock(return_value='测试正文'))
        control.first=control
        self.page.locator=Mock(return_value=control)
        self.page.get_by_text=Mock(return_value=control)
        self.page.wait_for_url=AsyncMock()
        self.context.close=AsyncMock()
        button=SimpleNamespace(wait_for=AsyncMock(),is_enabled=AsyncMock(return_value=True),
            click=AsyncMock(),all=AsyncMock(return_value=[]))
        button.and_=Mock(return_value=button)
        self.page.get_by_role=Mock(return_value=button)
        with patch.object(worker,'fill_xhs',new=AsyncMock(return_value=(control,control))), \
             patch.object(worker,'apply_settings',new=AsyncMock(return_value=AsyncMock())), \
             patch.object(worker,'save_publish_diagnostic',new=AsyncMock()) as diagnostic:
            result=await worker.publish(self.request)
        self.assertEqual(result['status'],'submitted')
        self.assertEqual(result['url'],'https://creator.xiaohongshu.com/new/note-manager')
        self.assertIn('确认审核结果',result['message'])
        self.assertEqual(diagnostic.call_args.args[2],'平台已接收提交，待核对审核结果')
        self.assertTrue(diagnostic.call_args.args[3])
        button.click.assert_awaited_once()
        self.context.close.assert_awaited_once()
        self.browser.close.assert_awaited_once()
        self.runtime.stop.assert_awaited_once()


class PublishDiagnosticTests(unittest.IsolatedAsyncioTestCase):
    async def test_failure_picture_is_local_bounded_and_private(self):
        import json
        import stat
        with tempfile.TemporaryDirectory() as folder:
            job_id = '8c1ea09a-b84c-4b9f-b605-acec12c9f2d1'
            page = SimpleNamespace(screenshot=AsyncMock(return_value=b'jpeg'))
            request = {'runtime': folder, 'job': {'id': job_id}}
            await worker.save_publish_diagnostic(page, request, '上传素材', False)
            image = Path(folder) / 'diagnostics' / (job_id + '.jpg')
            self.assertEqual(image.read_bytes(), b'jpeg')
            self.assertEqual(stat.S_IMODE(image.stat().st_mode), 0o600)
            self.assertEqual(json.loads(image.with_suffix('.json').read_text()),
                             {'phase': '上传素材', 'submitted': False, 'controls': []})

    async def test_missing_invalid_or_oversized_diagnostics_do_not_change_result(self):
        with tempfile.TemporaryDirectory() as folder:
            page = SimpleNamespace(screenshot=AsyncMock(return_value=b'x' * (1024 * 1024 + 1)))
            request = {'runtime': folder, 'job': {'id': '8c1ea09a-b84c-4b9f-b605-acec12c9f2d1'}}
            await worker.save_publish_diagnostic(page, request, '提交后', True)
            request['job']['id'] = '../unsafe'
            await worker.save_publish_diagnostic(page, request, '上传', False)
            request['job']['id'] = '8c1ea09a-b84c-4b9f-b605-acec12c9f2d1'
            page.screenshot.side_effect = RuntimeError('closed page')
            await worker.save_publish_diagnostic(page, request, '提交后', True)
            self.assertEqual([file.name for file in Path(folder).iterdir()], ['diagnostics'])
            diagnostics = list((Path(folder) / 'diagnostics').iterdir())
            self.assertEqual([file.name for file in diagnostics],
                             ['8c1ea09a-b84c-4b9f-b605-acec12c9f2d1.json'])
            import json
            self.assertEqual(json.loads(diagnostics[0].read_text()),
                             {'phase': '提交后', 'submitted': True, 'controls': []})


if __name__ == '__main__': unittest.main()
