"""Offline component and submission-recovery tests, with a fake backend only."""
import asyncio
import json
from pathlib import Path
import unittest

try:
    from patchright.async_api import async_playwright
except ImportError:
    async_playwright = None

ROOT = Path(__file__).resolve().parent.parent


@unittest.skipIf(async_playwright is None, 'Requires the local publishing assistant browser environment')
class BatchUITests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.runtime = await async_playwright().start()
        self.browser = await self.runtime.chromium.launch(headless=True, channel='chromium')
        self.page = await self.browser.new_page(viewport={'width': 1280, 'height': 900})
        self.page.set_default_timeout(7000)
        await self.page.route('**/*', lambda route: route.abort())

    async def asyncTearDown(self):
        await self.browser.close()
        await self.runtime.stop()

    async def wait_for(self, expression):
        deadline=asyncio.get_running_loop().time()+10
        while asyncio.get_running_loop().time()<deadline:
            if await self.page.evaluate(expression):return
            await asyncio.sleep(.05)
        self.fail('Browser condition did not become true: '+expression)

    async def mount(self, saved=None, batches=None):
        await self.page.set_content('''<style>[hidden]{display:none!important}body{margin:0;background:#202227;color:#dce1e9;font:14px sans-serif}button,input,select,textarea{font:inherit;padding:8px;background:#292c32;color:#dce1e9;border:1px solid #4b5059;border-radius:5px}button{cursor:pointer}button:disabled{opacity:.5}.field{display:grid;gap:8px;margin:16px 0}.hint{color:#aab4c3;font-size:12px}dialog{width:850px;max-height:85vh;overflow:auto;background:#292b31;color:#dce1e9;border:1px solid #596574}#dialog-actions{display:flex;justify-content:end;gap:12px;margin-top:20px}</style>
          <div id="publish-workspace"><div class="publish-columns"><section class="post-form"><div class="section-title">发布内容</div><button id="download-copy"></button></section></div></div>
          <div id="account-list"></div><span id="account-count"></span><span id="selected-count"></span><span id="platform-summary"></span><button id="publish-preview-btn"></button>
          <select id="publish-timing"><option value="now">now</option><option value="scheduled">scheduled</option></select><input id="publish-date">
          <dialog id="dialog"><button id="dialog-close">关闭</button><div id="dialog-body"></div><div id="dialog-actions"></div></dialog><p id="toast"></p>''')
        await self.page.add_style_tag(content=(ROOT/'distribution.css').read_text())
        await self.page.evaluate('''({saved,batches})=>{
          window.setInterval=()=>0;let nextId=0;Object.defineProperty(window.crypto,'randomUUID',{configurable:true,value:()=> 'test-id-'+(++nextId)});
          window.fakeAccounts=[{id:'a',platform:'xhs',name:'账号甲',status:'ready',platformUserId:'handle:a',runnerId:'runner'},{id:'b',platform:'douyin',name:'账号乙',status:'ready',platformUserId:'handle:b',runnerId:'runner'}];
          const post=type=>({type,title:'',body:'正文',tags:'#壁纸',assetIds:[],style:'simple'});
          window.testState=saved||{workspace:'publish',social:'xhs',posts:{xhs:post('image'),douyin:post('video')},accountPosts:{},editAccountId:'',accounts:fakeAccounts,content:{items:[{id:'gallery',name:'完整图集',type:'gallery',assetIds:['two','one']},{id:'video-work',name:'视频作品',type:'video',assetIds:['video']}]},postAssets:[{id:'one',name:'第一张',type:'image',file:new Blob(['1']),contentItemId:'gallery'},{id:'two',name:'第二张',type:'image',file:new Blob(['2']),contentItemId:'gallery'},{id:'video',name:'视频文件',type:'video',file:new Blob(['3']),contentItemId:'video-work'}],timing:'now',scheduledAt:'',batchPlan:{mode:'all',rows:[]},submissionPending:null};
          testState.selectedAccounts=new Set(['a','b']);
          window.fakeBatches=batches||{};window.failOnce=!batches;window.batchRequests=[];window.savedPending=false;
          window.CreatorBackend={hash:async file=>await file.text()};
          window.fetch=async (url,options={})=>{
            const route=url.split('/distribution')[1],body=options.body?JSON.parse(options.body):{};
            let data;
            if(route==='/status')data={connected:true,ready:true,runnerId:'runner'};
            else if(route==='/accounts')data=fakeAccounts;
            else if(route==='/jobs'||route==='/metrics')data=[];
            else if(route==='/media/reuse')data={id:'media-'+body.workspaceMediaId};
            else if(route==='/batches'){
              if(!savedPending)throw Error('Pending request was not saved before submission');
              batchRequests.push(body);
              if(fakeBatches[body.id]&&JSON.stringify(fakeBatches[body.id])!==JSON.stringify(body))throw Error('Batch changed across retry');
              fakeBatches[body.id]=body;data=body.entries.map((entry,i)=>({id:body.id+'-'+i}));
              if(failOnce){failOnce=false;throw Error('连接暂时中断');}
            }else throw Error('Unexpected route '+route);
            return {ok:true,status:200,json:async()=>data};
          };
          window.testHooks={state:()=>testState,projectId:()=> 'project',prepare:async()=>{savedPending=!!testState.submissionPending;},save:()=>{},render:()=>{},toast:text=>{document.getElementById('toast').textContent=text;},close:()=>document.getElementById('dialog').close(),modal:(title,body,actions)=>{
            document.getElementById('dialog-body').innerHTML='<h2>'+title+'</h2>'+body;
            const buttons=document.getElementById('dialog-actions');buttons.innerHTML='';
            for(const action of actions){const b=document.createElement('button');b.textContent=action.label;if(action.primary)b.className='primary';b.onclick=action.run;buttons.append(b);}
            if(!document.getElementById('dialog').open)document.getElementById('dialog').showModal();
            document.getElementById('dialog-close').onclick=testHooks.close;
          }};
        }''', {'saved': saved, 'batches': batches})
        for name in ('distribution-core.js', 'distribution-batch.js', 'distribution.js'):
            await self.page.evaluate((ROOT/name).read_text())
        await self.page.evaluate('Distribution.init(testHooks);Distribution.open("batch")')
        await self.page.get_by_role('button', name='选择作品和账号', exact=True).wait_for()

    async def build(self):
        await self.page.get_by_role('button', name='选择作品和账号', exact=True).click()
        await self.page.locator('[data-batch-content="work:gallery"]').check()
        await self.page.locator('[data-batch-content="work:video-work"]').check()
        await self.page.get_by_role('button', name='生成清单', exact=True).click()
        await self.wait_for('testState.batchPlan.rows.length===4')

    async def test_batch_picker_editor_removal_and_duplicate_append(self):
        await self.mount();await self.build()
        self.assertEqual(await self.page.locator('.distribution-batch-row').count(), 4)
        self.assertFalse(await self.page.locator('#batch-date-field').is_visible())
        self.assertEqual(await self.page.evaluate('testState.batchPlan.rows[0].post.assetIds'), ['two', 'one'])
        await self.page.locator('[data-batch-edit]').first.click()
        await self.page.locator('#batch-row-title').fill('这条独立文案')
        await self.page.get_by_role('button', name='保存修改', exact=True).click()
        self.assertEqual(await self.page.evaluate('testState.batchPlan.rows[0].post.title'), '这条独立文案')
        self.assertEqual(await self.page.evaluate('testState.batchPlan.rows[1].post.title'), '完整图集')
        await self.page.get_by_role('button', name='选择作品和账号', exact=True).click()
        await self.page.locator('#batch-build-mode').select_option('append')
        await self.page.get_by_role('button', name='生成清单', exact=True).click()
        await self.wait_for('document.getElementById("batch-build-error").textContent.includes("相同账号与素材")')
        self.assertEqual(await self.page.evaluate('testState.batchPlan.rows.length'), 4)
        await self.page.get_by_role('button', name='取消', exact=True).click()
        folder=ROOT.parent.parent/'.runtime/creator-tests';folder.mkdir(parents=True,exist_ok=True)
        await self.page.screenshot(path=str(folder/'batch-list.png'))
        await self.page.locator('[data-batch-remove]').first.click()
        self.assertEqual(await self.page.evaluate('testState.batchPlan.rows.length'), 3)

    async def test_response_loss_and_page_reload_reuse_one_batch_and_freeze_review(self):
        await self.mount();await self.build()
        await self.page.locator('#batch-preview').click()
        await self.page.get_by_role('button', name='确认发布', exact=True).click()
        await self.wait_for('document.getElementById("distribution-submit-status").textContent.includes("连接暂时中断")')
        saved=await self.page.evaluate('JSON.parse(JSON.stringify(testState))')
        batches=await self.page.evaluate('fakeBatches')
        original=saved['submissionPending']['payload']
        self.assertEqual(len(batches),1)
        self.assertEqual(len(original['entries']),4)
        # Simulate a new page with only the persisted project and remote batch.
        await self.mount(saved,batches)
        self.assertTrue(await self.page.locator('#batch-select').is_disabled())
        await self.page.locator('#batch-preview').click()
        await self.page.get_by_role('button', name='继续创建同一批次', exact=True).click()
        await self.wait_for('testState.submissionPending===null')
        self.assertEqual(await self.page.evaluate('batchRequests[0]'), original)
        self.assertEqual(await self.page.evaluate('Object.keys(fakeBatches).length'),1)
        self.assertEqual(await self.page.evaluate('testState.batchPlan.rows.length'),0)


if __name__ == '__main__':unittest.main()
