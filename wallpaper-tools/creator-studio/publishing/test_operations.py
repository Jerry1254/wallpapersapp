"""Local session binding and bulk operations; no platform or persistent API access."""
import importlib.util
import io
import json
from pathlib import Path
import unittest
import uuid
from unittest.mock import Mock, patch
from .bridge import PublishingBridge, BridgeError


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

    def test_originality_approval_requires_explicit_consent_local_task_and_unsubmitted_status(self):
        self.bridge.session={'token':'test'}
        job={'id':'11111111-1111-4111-8111-111111111111','accountId':'account','status':'needs_input',
             'platform':'xhs','platformUserId':'handle:example','post':{'type':'image','originality':'original'}}
        account={'id':'account','runnerId':'runner'}
        self.bridge.api.side_effect=lambda method,path,*args: [job] if path=='distribution/jobs' else [account]
        def request(accepted=True,cookie='CREATOR_PUBLISH_SESSION=test'):
            h=Handler('/creator-studio/api/distribution/jobs/'+job['id']+'/originality-approval',cookie)
            data=json.dumps({'accepted':accepted}).encode();h.headers['Content-Length']=str(len(data));h.rfile=io.BytesIO(data)
            self.bridge.handle(h);return h
        with patch('publishing.bridge.issue_operator_approval') as issue:
            self.assertEqual(request(cookie='').status,401)
            self.assertEqual(request(False).status,422)
            account['runnerId']='elsewhere';self.assertEqual(request().status,409)
            account['runnerId']='runner';job['status']='submitting';self.assertEqual(request().status,409)
            issue.assert_not_called()
            job['status']='needs_input';self.assertEqual(request().status,200)
            issue.assert_called_once()
            job['status']='queued';self.assertEqual(request().status,200)
            self.assertEqual(issue.call_count,2)
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

    def test_group_crud_uses_authenticated_backend_proxy(self):
        self.bridge.session = {'token': 'publish-for-test', 'cookie': 'backend-for-test', 'csrf': 'csrf-for-test'}
        for route,method,payload in [('/groups','GET',None),('/groups','POST',{'name':'测试分组'}),('/groups/group-id','PUT',{'name':'新名称'}),('/groups/group-id','DELETE',None)]:
            handler=Handler('/creator-studio/api/distribution'+route,'CREATOR_PUBLISH_SESSION=publish-for-test',method)
            encoded=json.dumps(payload or {}).encode();handler.rfile=io.BytesIO(encoded);handler.headers['Content-Length']=str(len(encoded))
            self.bridge.handle(handler)
            self.assertEqual(handler.status,200)
            self.assertEqual(self.bridge.api.call_args.args[:2],(method,'distribution'+route))

    def test_work_history_forwards_filters_without_losing_pagination(self):
        self.bridge.session = {'token': 'publish-for-test', 'cookie': 'backend-for-test', 'csrf': 'csrf-for-test'}
        handler=Handler('/creator-studio/api/distribution/jobs/works?result=attention&page=2&pageSize=20','CREATOR_PUBLISH_SESSION=publish-for-test','GET')
        self.bridge.handle(handler)
        self.assertEqual(handler.status,200)
        self.assertEqual(self.bridge.api.call_args.args[:2],('GET','distribution/jobs/works?result=attention&page=2&pageSize=20'))

    def test_cached_thumbnail_still_requires_valid_admin_session(self):
        media_id=str(uuid.uuid4());self.bridge.thumbnails[media_id]=b'local-jpeg-for-test'
        self.bridge.session = {'token': 'publish-for-test', 'cookie': 'backend-for-test', 'csrf': 'csrf-for-test'}
        route='/creator-studio/api/distribution/media/'+media_id+'/thumbnail'
        missing=Handler(route,'','GET');self.bridge.handle(missing)
        self.assertEqual(missing.status,401);self.bridge.api.assert_not_called()
        valid=Handler(route,'CREATOR_PUBLISH_SESSION=publish-for-test','GET');self.bridge.handle(valid)
        self.assertEqual(valid.status,200);self.assertEqual(valid.response_headers['Content-Type'],'image/jpeg')
        self.assertEqual(valid.wfile.getvalue(),b'local-jpeg-for-test')
        self.bridge.api.assert_called_once_with('GET','distribution/media/'+media_id+'/info',session=self.bridge.session)
        self.bridge.api.side_effect=BridgeError('会话失效',401)
        expired=Handler(route,'CREATOR_PUBLISH_SESSION=publish-for-test','GET');self.bridge.handle(expired)
        self.assertEqual(expired.status,401);self.assertNotIn(b'local-jpeg-for-test',expired.wfile.getvalue())

    def test_identity_diagnostic_is_local_operation_only_and_cannot_mark_account_ready(self):
        account = {'id': 'one', 'name': '账号一'}
        for image, accepted in [('data:image/jpeg;base64,dGVzdA==', True), ('https://example.com/private.jpg', False), ('data:image/jpeg;base64,'+'x'*1400000, False)]:
            self.bridge.execute = Mock(return_value={'status':'needs_input','message':'身份未确认','diagnostic':image})
            result = self.bridge.account_operation(account, 'login', lambda event: None)
            self.assertEqual(result['status'], 'failed')
            self.assertEqual('diagnostic' in result, accepted)
            self.assertEqual(self.bridge.api.call_args.args[2]['status'], 'unverified')
            self.assertNotIn('diagnostic', self.bridge.api.call_args.args[2])

    def test_backend_validation_errors_keep_the_reason_and_status(self):
        bridge = PublishingBridge()
        for body, message in (
            ({'error': {'code': 'INVALID_REQUEST', 'message': '开始日期必须早于结束日期'}}, '开始日期必须早于结束日期'),
            ({'detail': '请选择未来的时间'}, '请选择未来的时间'),
            ({'error': None}, '后台请求失败'),
        ):
            response = Mock(status=422)
            response.read.return_value = json.dumps(body).encode()
            connection = Mock()
            connection.getresponse.return_value = response
            with patch('http.client.HTTPConnection', return_value=connection):
                with self.assertRaises(BridgeError) as caught:
                    bridge.api('GET', 'distribution/overview')
            self.assertEqual(str(caught.exception), message)
            self.assertEqual(caught.exception.status, 422)
            connection.close.assert_called_once()

    def test_nested_expiry_error_clears_only_the_active_session(self):
        bridge = PublishingBridge()
        bridge.session = {'cookie': 'test-cookie', 'csrf': 'test-csrf'}
        response = Mock(status=401)
        response.read.return_value = b'{"error":{"message":"Session expired"}}'
        connection = Mock()
        connection.getresponse.return_value = response
        with patch('http.client.HTTPConnection', return_value=connection):
            with self.assertRaisesRegex(BridgeError, 'Session expired'):
                bridge.api('GET', 'distribution/overview')
        self.assertIsNone(bridge.session)


if __name__ == '__main__': unittest.main()
