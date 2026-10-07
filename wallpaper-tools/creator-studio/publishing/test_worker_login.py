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
        self.page = SimpleNamespace(goto=AsyncMock())
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
        self.runtime.chromium.launch.assert_awaited_once_with(headless=False, channel='chrome')
        self.browser.new_context.assert_awaited_once_with()
        self.read_identity.assert_awaited_once_with(self.page, 'douyin', None)
        self.assertEqual(self.upstream._wait_for_douyin_login.call_args.kwargs['max_checks'], 300)
        self.assertEqual(self.upstream._wait_for_douyin_login.call_args.kwargs['poll_interval'], 2)
        self.vault.save.assert_called_once_with(self.state)
        self.assertEqual(list(Path(self.folder.name).rglob('state.json')), [])
        self.browser.close.assert_awaited_once()

    async def test_identity_mismatch_never_replaces_existing_session(self):
        self.request['account']['platformUserId'] = 'handle:original'
        self.read_identity.side_effect = worker.IdentityError('账号不一致')
        with self.assertRaises(worker.IdentityError): await worker.login(self.request)
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
        await worker.login(self.request)
        self.runtime.chromium.launch.assert_awaited_once_with(headless=True, channel='chrome')
        self.browser.new_context.assert_awaited_once_with(storage_state=self.state)
        self.upstream._wait_for_douyin_login.assert_not_awaited()

    async def test_xhs_identity_uses_scanned_window_without_hidden_relogin(self):
        self.request['account']['platform'] = 'xhs'
        result = await worker.login(self.request)
        self.assertEqual(result['status'], 'ready')
        self.upstream._save_xhs_qrcode.assert_awaited_once()
        self.read_identity.assert_awaited_once_with(self.page, 'xhs', None)
        self.runtime.chromium.launch.assert_awaited_once_with(headless=False, channel='chrome')


if __name__ == '__main__': unittest.main()
