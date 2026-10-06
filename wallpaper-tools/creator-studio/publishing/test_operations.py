"""Local session binding and bulk operations; no platform or persistent API access."""
import importlib.util
import io
import json
from pathlib import Path
import unittest
from unittest.mock import Mock
from .bridge import PublishingBridge


class Handler:
    def __init__(self, path, cookie='', command='POST'):
        self.path = path
        self.command = command
        self.headers = {'Host': '127.0.0.1:8176', 'Origin': 'http://127.0.0.1:8176', 'X-Creator-Request': '1', 'Cookie': cookie, 'Content-Length': '0'}
        self.server = type('Server', (), {'server_port': 8176})()
        self.rfile = io.BytesIO()
        self.wfile = io.BytesIO()
        self.response_headers = {}
    def send_response(self, status): self.status = status
    def send_header(self, key, value): self.response_headers[key] = value
    def end_headers(self): pass


class OperationsTests(unittest.TestCase):
    def setUp(self):
        self.bridge = PublishingBridge()
        self.bridge.start = Mock()
        self.bridge.ready = lambda: True
        self.bridge.runner = 'runner'
        self.bridge.api = Mock(return_value={})
    def creator(self):
        spec = importlib.util.spec_from_file_location('creator_test', Path(__file__).resolve().parent.parent / 'creator-bridge.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        creator = module.CreatorBridge()
        creator.session = {'cookie': 'admin-session-for-test', 'csrf': 'csrf-for-test', 'token': 'creator-for-test'}
        creator.publishing = self.bridge
        creator.api = Mock(return_value={})
        return creator
    def test_session_reuse_requires_creator_cookie_and_does_not_expose_backend_credentials(self):
        creator = self.creator()
        missing = Handler('/creator-studio/api/creator/publish-session')
        creator.handle(missing)
        self.assertEqual(missing.status, 401)
        self.assertIsNone(self.bridge.session)
        valid = Handler('/creator-studio/api/creator/publish-session', 'CREATOR_DATA_SESSION=creator-for-test')
        creator.handle(valid)
        self.assertEqual(valid.status, 200)
        self.assertTrue(self.bridge.session['borrowed'])
        self.assertEqual(self.bridge.session['cookie'], creator.session['cookie'])
        self.assertNotIn('admin-session-for-test', valid.wfile.getvalue().decode())
        self.assertNotIn('csrf-for-test', str(valid.response_headers))
        creator.api.assert_called_once()
        self.assertIn('HttpOnly; SameSite=Strict', valid.response_headers['Set-Cookie'])
    def test_disconnect_publishing_preserves_creator_session(self):
        creator = self.creator()
        self.bridge.connect_from_creator(creator.session, creator.backend)
        token = self.bridge.session['token']
        handler = Handler('/creator-studio/api/distribution/disconnect', 'CREATOR_PUBLISH_SESSION=' + token)
        self.bridge.handle(handler)
        self.assertEqual(handler.status, 200)
        self.assertIsNone(self.bridge.session)
        self.assertIsNotNone(creator.session)
        self.bridge.api.assert_not_called()
    def test_bulk_keeps_exclusive_operation_and_continues_after_one_account_fails(self):
        accounts = [{'id': 'one', 'name': '账号一'}, {'id': 'two', 'name': '账号二'}]
        self.bridge.active = 'op'
        self.bridge.operations['op'] = {}
        def operation(account, mode, progress):
            self.assertEqual(self.bridge.active, 'op')
            progress({'message': '正在读取'})
            if account['id'] == 'one': raise ValueError('登录已失效')
            return {'status': 'ready', 'message': '完成'}
        self.bridge.account_operation = operation
        self.bridge.bulk('op', accounts, 'analytics')
        result = self.bridge.operations['op']
        self.assertEqual(result['completed'], 2)
        self.assertEqual([r['status'] for r in result['results']], ['failed', 'ready'])
        self.assertIsNone(self.bridge.active)
    def test_snapshot_only_saves_actual_values_and_invalid_identity_blocks_future_publication(self):
        account = {'id': 'one', 'name': '账号一'}
        self.bridge.execute = Mock(return_value={'status': 'ready', 'metrics': {'followers': 7}, 'sourceUrl': 'https://creator.douyin.com/creator-micro/home'})
        self.bridge.account_operation(account, 'analytics', lambda event: None)
        saved = self.bridge.api.call_args.args[2]
        self.assertEqual(saved['metrics'], {'followers': 7})
        self.bridge.execute.return_value = {'status': 'needs_input', 'message': '身份未确认'}
        self.bridge.account_operation(account, 'analytics', lambda event: None)
        self.assertEqual(self.bridge.api.call_args.args[2]['status'], 'unverified')
    def test_query_proxy_preserves_encoded_filters_and_rejects_duplicate_parameters(self):
        self.bridge.session = {'token': 'publish-for-test', 'cookie': 'backend-for-test', 'csrf': 'csrf-for-test'}
        handler = Handler('/creator-studio/api/distribution/jobs/page?keyword=%25_%26&page=2', 'CREATOR_PUBLISH_SESSION=publish-for-test', 'GET')
        self.bridge.handle(handler)
        self.assertEqual(handler.status, 200)
        self.assertEqual(self.bridge.api.call_args.args[:2], ('GET', 'distribution/jobs/page?keyword=%25_%26&page=2'))
        bad = Handler('/creator-studio/api/distribution/jobs/page?page=1&page=2', 'CREATOR_PUBLISH_SESSION=publish-for-test', 'GET')
        self.bridge.handle(bad)
        self.assertEqual(bad.status, 422)


if __name__ == '__main__': unittest.main()
