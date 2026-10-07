"""Exercise task filters, dashboard and bulk account selection against fake responses."""
import unittest
from . import test_batch_ui as fixture
ROOT=fixture.ROOT


@unittest.skipIf(fixture.async_playwright is None, 'Requires local browser runtime')
class ConsoleUITests(unittest.IsolatedAsyncioTestCase):
    asyncSetUp = fixture.BatchUITests.asyncSetUp
    asyncTearDown = fixture.BatchUITests.asyncTearDown
    wait_for = fixture.BatchUITests.wait_for
    mount = fixture.BatchUITests.mount

    async def test_history_pagination_and_filters_are_sent_to_backend(self):
        await self.mount()
        await self.page.evaluate('''fakeTotal=205;fakeJobs=[{id:'job',batchId:'batch',batchPosition:0,status:'published',platform:'xhs',accountName:'账号甲',resultUrl:'https://creator.xiaohongshu.com/publish/publish?published=true',post:{type:'image',title:'真实历史内容',body:'正文',tags:[],mediaIds:['file']},createdAt:'2026-10-01T00:00:00Z',dueAt:'2026-10-01T00:00:00Z'}];Distribution.open('jobs')''')
        await self.wait_for('document.getElementById("console-next")!=null')
        self.assertIn('205', await self.page.locator('#console-jobs-results').inner_text())
        self.assertEqual(await self.page.get_by_role('link',name='查看平台作品').get_attribute('href'),
                         'https://creator.xiaohongshu.com/new/note-manager')
        await self.page.locator('#console-next').click()
        await self.wait_for('consoleRequests.some(r=>r.route.includes("page=2"))')
        await self.page.locator('[data-filter="keyword"]').fill('%_测试')
        # A data refresh must retain typing before the input has emitted change.
        await self.page.evaluate("Distribution.open('jobs')")
        self.assertEqual(await self.page.locator('[data-filter="keyword"]').input_value(), '%_测试')
        await self.page.locator('[data-filter="status"]').select_option('published')
        await self.page.get_by_role('button', name='查询', exact=True).click()
        await self.wait_for('consoleRequests.some(r=>r.route.includes("status=published")&&r.route.includes("page=1"))')
        self.assertTrue(await self.page.evaluate('consoleRequests.some(r=>r.route.includes("keyword=%25_%E6%B5%8B%E8%AF%95"))'))
        await self.page.get_by_role('button', name='查看内容', exact=True).click()
        self.assertIn('真实历史内容', await self.page.locator('#dialog-body').inner_text())

    async def test_dashboard_keeps_missing_metrics_and_exports_safe_csv(self):
        await self.mount()
        await self.page.evaluate('''fakeOverview={total:1000,statuses:{published:900,submitted:50,failed:20,cancelled:30},daily:[{date:'2026-10-01',total:1000,published:900,needsAttention:20}],accounts:[{accountId:'a',name:'账号甲',platform:'xhs',total:1000,published:900,awaiting:50,needsAttention:20}],metrics:[{accountId:'a',name:'=bad',platform:'xhs',metrics:{followers:90},delta:{followers:-10},collectedAt:'2026-10-06T00:00:00Z',baselineAt:'2026-09-30T00:00:00Z'}]};Distribution.open('data')''')
        await self.wait_for('document.getElementById("console-export-data")!=null')
        self.assertIn('1,000', await self.page.locator('#console-data-results').inner_text())
        self.assertIn('变化 -10', await self.page.locator('#console-data-results').inner_text())
        self.assertIn('变化 —', await self.page.locator('#console-data-results').inner_text())
        await self.page.evaluate('URL.createObjectURL=blob=>{window.csvBlob=blob;return "blob:fake"};URL.revokeObjectURL=()=>{};HTMLAnchorElement.prototype.click=()=>{}')
        await self.page.locator('#console-export-data').click()
        csv = await self.page.evaluate('csvBlob.text()')
        self.assertIn("'=bad", csv)
        self.assertIn('"-10"', csv)
        self.assertNotIn('"0","0","0","0"', csv)
        folder=ROOT.parent.parent/'.runtime/creator-tests';folder.mkdir(parents=True,exist_ok=True)
        await self.page.screenshot(path=str(folder/'distribution-dashboard.png'))

    async def test_account_filters_select_only_matching_local_accounts(self):
        await self.mount()
        await self.page.evaluate("fakeAccounts[0].group='主账号';fakeAccounts[1].group='其他';Distribution.open('accounts')")
        await self.wait_for('document.getElementById("console-account-all")!=null')
        await self.page.locator('#console-account-group').select_option('主账号')
        self.assertEqual(await self.page.locator('[data-account-select]').count(), 1)
        await self.page.locator('#console-account-all').check()
        self.assertFalse(await self.page.locator('#console-check').is_disabled())
        self.assertIn('已选 1 个', await self.page.locator('#console-account-count').inner_text())

    async def test_existing_creator_connection_is_reused_once_and_disconnect_is_respected(self):
        await self.mount()
        await self.page.evaluate('''window.bound=false;window.connectRoutes=[];CreatorBackend.connected=true;CreatorBackend.request=async route=>{connectRoutes.push(route);bound=true;};const previous=fetch;window.fetch=async(url,options={})=>{if(url.endsWith('/status'))return {ok:true,status:200,json:async()=>({connected:bound,ready:true,runnerId:'runner'})};if(url.endsWith('/disconnect')){bound=false;return {ok:true,status:200,json:async()=>({})};}return previous(url,options);};Distribution.enter();''')
        await self.wait_for('connectRoutes.length===1')
        await self.wait_for('document.getElementById("distribution-status").textContent.includes("在线")')
        self.assertEqual(await self.page.evaluate('connectRoutes'),['/publish-session'])
        await self.page.locator('#distribution-connect').click()
        await self.page.get_by_role('button',name='断开连接',exact=True).click()
        await self.wait_for('document.getElementById("distribution-status").textContent.includes("尚未连接")')
        self.assertEqual(await self.page.evaluate('connectRoutes.length'),1)

    async def test_backup_restores_editable_copy_without_original_publish_request(self):
        await self.mount()
        # Patchright isolates evaluation; its message event source belongs to a
        # different world. Use the library's supported timer scheduler here.
        await self.page.evaluate('window.setImmediate=(fn,...args)=>setTimeout(fn,0,...args);window.clearImmediate=clearTimeout')
        await self.page.evaluate((ROOT/'parallax-runtime.js').read_text())
        await self.page.evaluate((ROOT/'resource-export.js').read_text())
        await self.page.evaluate((ROOT/'creator-backup.js').read_text())
        await self.page.evaluate('''CreatorBackend.connected=true;CreatorBackend.list=async()=>[];CreatorBackend.requireConnection=async()=>{};
          window.backupRecord={id:'original-project',name:'原项目',media:[],data:{submissionPending:{payload:{id:'original-batch'}},productRecord:{id:'original-wallpaper'}},history:{entries:[{data:{submissionPending:{payload:{id:'original-batch'}},productRecord:{id:'original-wallpaper'}}}]}};
          CreatorBackup.open({...testHooks,record:async()=>backupRecord,download:(name,blob)=>window.backupBlob=blob,restore:async value=>window.restoredBackup=value});''')
        await self.page.locator('#creator-backup-export').click()
        await self.wait_for('!!window.backupBlob || !document.getElementById("creator-backup-export").disabled')
        self.assertTrue(await self.page.evaluate('!!window.backupBlob'), await self.page.locator('#creator-backup-message').inner_text())
        value=bytes(await self.page.evaluate('async()=>Array.from(new Uint8Array(await backupBlob.arrayBuffer()))'))
        await self.page.locator('#creator-backup-import').set_input_files({'name':'备份.zip','mimeType':'application/zip','buffer':value})
        await self.wait_for('!!window.restoredBackup')
        restored=await self.page.evaluate('restoredBackup.projects[0]')
        self.assertNotEqual(restored['id'],'original-project')
        self.assertIsNone(restored['data']['submissionPending'])
        self.assertIsNone(restored['data']['productRecord'])
        self.assertIsNone(restored['history']['entries'][0]['data']['submissionPending'])
        self.assertIsNone(restored['history']['entries'][0]['data']['productRecord'])


if __name__ == '__main__': unittest.main()
