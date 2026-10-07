import json
import os
from pathlib import Path
import tempfile
import time
import unittest
from approval import consume, scope


class OperatorApprovalTests(unittest.TestCase):
    def test_approval_is_bound_to_task_account_content_and_consumed_once(self):
        job = {'id':'11111111-1111-4111-8111-111111111111','accountId':'test',
               'platformUserId':'test-user','platform':'xhs','post':{'originality':'original','type':'image'}}
        with tempfile.TemporaryDirectory() as root:
            folder=Path(root)/'operator-approvals';folder.mkdir(mode=0o700)
            path=folder/(job['id']+'.json')
            def issue(expires=None):
                path.write_text(json.dumps({'scope':scope(job),'expiresAt':expires or time.time()+60}))
                os.chmod(path,0o600)
            issue()
            self.assertFalse(consume(root,{**job,'accountId':'other'}))
            self.assertFalse(consume(root,{**job,'post':{**job['post'],'type':'video'}}))
            self.assertTrue(consume(root,job))
            self.assertFalse(consume(root,job))
            issue(time.time()-1);self.assertFalse(consume(root,job))
            issue(time.time()+3600);self.assertFalse(consume(root,job))
            issue();os.chmod(path,0o644);self.assertFalse(consume(root,job))


if __name__=='__main__':
    unittest.main()
