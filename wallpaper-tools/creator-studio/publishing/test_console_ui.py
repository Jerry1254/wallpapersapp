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

    async def test_retry_requires_originality_confirmation_and_shows_request_errors_in_dialog(self):
        await self.mount()
        await self.page.evaluate('''fakeTotal=1;fakeJobs=[{id:'original-job',batchId:'batch',status:'needs_input',platform:'xhs',accountName:'账号甲',post:{type:'video',title:'原创视频',body:'正文',tags:[],mediaIds:['file'],originality:'original'}}];Distribution.open('jobs')''')
        await self.page.get_by_role('button', name='重试', exact=True).click()
        await self.page.get_by_role('button', name='确认重试', exact=True).click()
        self.assertTrue(await self.page.locator('#dialog').is_visible())
        self.assertEqual(await self.page.locator('#dialog').get_by_role('alert').inner_text(), '请先确认本次原创声明须知')
        self.assertFalse(await self.page.evaluate('consoleRequests.some(r=>r.route.includes("/originality-approval")||r.route.includes("/action"))'))
        await self.page.evaluate('''const previous=fetch;window.fetch=async(url,options={})=>{
          if(url.endsWith('/originality-approval')){consoleRequests.push({route:'/jobs/original-job/originality-approval',body:JSON.parse(options.body)});return {ok:false,status:409,json:async()=>({message:'任务状态已变化，请刷新'})};}
          return previous(url,options);
        };void 0;''')
        await self.page.locator('#console-originality-accepted').check()
        await self.page.get_by_role('button', name='确认重试', exact=True).click()
        await self.wait_for('document.getElementById("console-action-error").textContent.includes("任务状态已变化")')
        self.assertEqual(await self.page.locator('#dialog').get_by_role('alert').inner_text(), '任务状态已变化，请刷新')
        self.assertTrue(await self.page.locator('#dialog').is_visible())
        self.assertFalse(await self.page.evaluate('consoleRequests.some(r=>r.route.includes("/action"))'))

    async def test_result_verification_updates_only_the_original_job_without_publishing(self):
        await self.mount()
        await self.page.evaluate('''const previous=fetch;window.fetch=async(url,options={})=>{
          if(url.endsWith('/jobs/verification-job/action')){
            const body=JSON.parse(options.body);consoleRequests.push({route:'/jobs/verification-job/action',body});
            fakeJobs[0].status=body.result;return {ok:true,status:200,json:async()=>({})};
          }return previous(url,options);
        };void 0;''')
        for status, result, label in [('submitted','published','确认已发布'),('uncertain','failed','确认未发布')]:
            await self.page.evaluate('''status=>{
              fakeTotal=1;fakeJobs=[{id:'verification-job',batchId:'original-batch',status,platform:'xhs',accountName:'账号甲',post:{type:'video',title:'核对原任务',body:'正文',tags:[],mediaIds:['file']}}];
              Distribution.open('jobs');
            }''',status)
            await self.page.get_by_role('button',name='核对结果',exact=True).click()
            await self.page.get_by_role('button',name=label,exact=True).click()
            await self.wait_for('!document.getElementById("dialog").open')
            self.assertEqual(await self.page.evaluate('fakeJobs[0].status'),result)
        actions=await self.page.evaluate('consoleRequests.filter(r=>r.route.endsWith("/action"))')
        self.assertEqual([r['body'] for r in actions],[{'action':'resolve','result':'published'},{'action':'resolve','result':'failed'}])
        self.assertTrue(all(r['route']=='/jobs/verification-job/action' for r in actions))
        self.assertFalse(await self.page.evaluate('consoleRequests.some(r=>r.route==="/batches"||r.route.includes("/originality-approval"))'))

    async def test_scheduled_originality_confirmation_does_not_retry_or_change_due_time(self):
        await self.mount()
        await self.page.evaluate('''fakeTotal=1;fakeJobs=[{id:'scheduled-job',status:'queued',platform:'xhs',accountName:'账号甲',dueAt:'2026-10-08T02:30:00Z',post:{type:'video',title:'定时原创视频',originality:'original',mediaIds:['file']}}];
          const previous=fetch;window.fetch=async(url,options={})=>{
            if(url.endsWith('/jobs/scheduled-job/originality-approval')){
              consoleRequests.push({route:'/jobs/scheduled-job/originality-approval',body:JSON.parse(options.body)});
              return {ok:true,status:200,json:async()=>({ok:true})};
            }return previous(url,options);
          };Distribution.open('jobs');''')
        await self.page.get_by_role('button',name='确认原创须知',exact=True).click()
        await self.page.get_by_role('button',name='确认本条原创须知',exact=True).click()
        self.assertIn('请先确认本次原创声明须知',await self.page.locator('#console-action-error').inner_text())
        self.assertFalse(await self.page.evaluate('consoleRequests.some(r=>r.route.includes("originality-approval"))'))
        await self.page.locator('#console-originality-accepted').check()
        await self.page.get_by_role('button',name='确认本条原创须知',exact=True).click()
        await self.wait_for('!document.getElementById("dialog").open')
        self.assertEqual(await self.page.evaluate('fakeJobs[0].dueAt'),'2026-10-08T02:30:00Z')
        self.assertEqual(await self.page.evaluate('fakeJobs[0].status'),'queued')
        self.assertFalse(await self.page.evaluate('consoleRequests.some(r=>r.route.endsWith("/action")||r.route==="/batches")'))

    async def test_account_filters_select_only_matching_local_accounts(self):
        await self.mount()
        await self.page.evaluate("fakeAccounts[0].group='主账号';fakeAccounts[1].group='其他';Distribution.open('accounts')")
        await self.wait_for('document.getElementById("console-account-all")!=null')
        await self.page.locator('#console-account-group').select_option('主账号')
        self.assertEqual(await self.page.locator('[data-account-select]').count(), 1)
        await self.page.locator('#console-account-all').check()
        self.assertFalse(await self.page.locator('#console-check').is_disabled())
        self.assertIn('已选 1 个', await self.page.locator('#console-account-count').inner_text())

    async def test_dashboard_delayed_success_and_error_cannot_replace_new_query(self):
        await self.mount()
        await self.page.evaluate('''const previous=fetch;window.overviewPending=[];
          window.fetch=(url,options={})=>url.includes('/overview?')?
            new Promise((resolve,reject)=>overviewPending.push({url,resolve,reject})):previous(url,options);
          window.overviewResult=total=>({total,statuses:{published:total},daily:[],accounts:[],metrics:[]});
          Distribution.open('data');''')
        await self.wait_for('overviewPending.length===2')
        await self.page.get_by_role('button',name='近 7 天',exact=True).click()
        await self.wait_for('overviewPending.length===3')
        await self.page.evaluate('overviewPending[2].resolve({ok:true,status:200,json:async()=>overviewResult(7)})')
        await self.wait_for('document.getElementById("console-data-results").textContent.includes("共 7 条任务")')
        await self.page.evaluate('for(const pending of overviewPending.slice(0,2))pending.resolve({ok:true,status:200,json:async()=>overviewResult(30)})')
        await self.page.wait_for_timeout(100)
        self.assertIn('共 7 条任务',await self.page.locator('#console-data-results').inner_text())
        await self.page.get_by_role('button',name='近 30 天',exact=True).click()
        await self.wait_for('overviewPending.length===4')
        await self.page.get_by_role('button',name='近 90 天',exact=True).click()
        await self.wait_for('overviewPending.length===5')
        await self.page.evaluate('overviewPending[4].resolve({ok:true,status:200,json:async()=>overviewResult(90)})')
        await self.wait_for('document.getElementById("console-data-results").textContent.includes("共 90 条任务")')
        await self.page.evaluate('overviewPending[3].reject(Error("旧请求网络错误"))')
        await self.page.wait_for_timeout(100)
        self.assertIn('共 90 条任务',await self.page.locator('#console-data-results').inner_text())
        self.assertEqual(await self.page.get_by_role('alert').count(),0)
        await self.page.get_by_role('button',name='近 7 天',exact=True).click()
        await self.wait_for('overviewPending.length===6')
        await self.page.evaluate('overviewPending[5].reject(Error("本次请求失败"))')
        await self.page.get_by_role('alert').wait_for()
        self.assertEqual(await self.page.get_by_role('alert').inner_text(),'本次请求失败')
        self.assertNotIn('共 90 条任务',await self.page.locator('#console-data-results').inner_text())

    async def test_other_computer_account_is_excluded_and_active_operation_disables_actions(self):
        await self.mount()
        await self.page.evaluate("fakeAccounts[1].runnerId='other-computer';Distribution.open('accounts')")
        await self.wait_for('document.getElementById("console-account-all")!=null')
        self.assertTrue(await self.page.locator('[data-account-select="b"]').is_disabled())
        self.assertTrue(await self.page.locator('[data-login="b"]').is_disabled())
        self.assertTrue(await self.page.locator('[data-check="b"]').is_disabled())
        await self.page.locator('#console-account-all').check()
        self.assertIn('已选 1 个',await self.page.locator('#console-account-count').inner_text())
        await self.page.evaluate("const previous=fetch;window.fetch=async(url,options={})=>url.endsWith('/status')?{ok:true,status:200,json:async()=>({connected:true,ready:true,runnerId:'runner',active:'active-check'})}:previous(url,options);void 0;")
        await self.page.locator('[data-distribution-view="accounts"]').click()
        await self.wait_for('document.getElementById("console-check").disabled')
        self.assertTrue(await self.page.locator('#console-sync').is_disabled())
        for selector in ('[data-login="a"]','[data-check="a"]','[data-remove="a"]'):
            self.assertTrue(await self.page.locator(selector).is_disabled())

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
