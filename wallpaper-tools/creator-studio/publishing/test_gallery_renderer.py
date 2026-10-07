"""Pixel-level container blend and preview/export checks; no live page or network."""
import unittest
from . import test_batch_ui as fixture


@unittest.skipIf(fixture.async_playwright is None, 'Requires local browser runtime')
class GalleryRendererTests(unittest.IsolatedAsyncioTestCase):
    asyncSetUp = fixture.BatchUITests.asyncSetUp
    asyncTearDown = fixture.BatchUITests.asyncTearDown

    async def mount(self):
        await self.page.set_content('<canvas id="result"></canvas>')
        for name in ('content-core.js', 'gallery-core.js', 'gallery-editing.js', 'gallery-renderer.js'):
            await self.page.evaluate((fixture.ROOT/name).read_text())
        await self.page.evaluate('''window.blendProbe=async({kind,mode,childMode,opacity=1,preview=false})=>{
          const rect=(id,color,blendMode='source-over')=>({id,type:'rect',x:0,y:0,width:80,height:80,color,visible:true,opacity:1,blendMode});
          const child=rect('child','#cc6633',childMode||'source-over');
          const container={id:'container',type:kind,x:0,y:0,width:80,height:80,visible:true,opacity,blendMode:mode,clip:false,children:[child],componentId:'component'};
          const gallery={components:{component:{id:'component',width:80,height:80,nodes:[child],clip:false}}};
          const frame={id:'frame',type:'frame',width:80,height:80,nodes:[rect('back','#336699'),container]};
          await GalleryRenderer.paint(document.getElementById('result'),{gallery,slots:{}},frame,()=>null,{preview});
          return Array.from(document.getElementById('result').getContext('2d').getImageData(40,40,1,1).data);
        };void 0;''')

    async def test_group_and_component_blend_match_numeric_compositing(self):
        await self.mount()
        backdrop,source=[51,102,153],[204,102,51]
        def expected(mode):
            out=[]
            for a,b in zip(backdrop,source):
                if mode=='source-over': value=b
                elif mode=='multiply': value=a*b/255
                elif mode=='screen': value=255-(255-a)*(255-b)/255
                else: value=2*a*b/255 if a<=127.5 else 255-2*(255-a)*(255-b)/255
                out.append(round(value))
            return out+[255]
        for kind in ('group','instance'):
            for mode in ('source-over','multiply','screen','overlay'):
                with self.subTest(kind=kind,mode=mode):
                    actual=await self.page.evaluate('blendProbe',{'kind':kind,'mode':mode})
                    for value,wanted in zip(actual,expected(mode)):
                        self.assertLessEqual(abs(value-wanted),1,(kind,mode,actual,expected(mode)))
                    preview=await self.page.evaluate('blendProbe',{'kind':kind,'mode':mode,'preview':True})
                    self.assertEqual(actual,preview)

    async def test_passthrough_keeps_child_blend_while_normal_isolates_it(self):
        await self.mount()
        for kind in ('group','instance'):
            passing=await self.page.evaluate('blendProbe',{'kind':kind,'mode':'pass-through','childMode':'multiply'})
            isolated=await self.page.evaluate('blendProbe',{'kind':kind,'mode':'source-over','childMode':'multiply'})
            for value,wanted in zip(passing,[41,41,31,255]):
                self.assertLessEqual(abs(value-wanted),1,(kind,passing))
            self.assertEqual(isolated,[204,102,51,255])

    async def test_container_opacity_composites_once_and_matches_preview(self):
        await self.mount()
        for kind in ('group','instance'):
            args={'kind':kind,'mode':'source-over','opacity':.5}
            actual=await self.page.evaluate('blendProbe',args)
            for value,wanted in zip(actual,[128,102,102,255]):
                self.assertLessEqual(abs(value-wanted),1,(kind,actual))
            self.assertEqual(actual,await self.page.evaluate('blendProbe',{**args,'preview':True}))

    async def test_frame_converted_to_component_preserves_appearance(self):
        await self.mount()
        for mode in ('source-over','multiply','screen','overlay'):
            with self.subTest(mode=mode):
                result=await self.page.evaluate('''async mode=>{
                  const child={id:'child',type:'rect',x:0,y:0,width:80,height:80,color:'#cc6633',visible:true,opacity:1,blendMode:'source-over'};
                  const source=GalleryCore.frame(80,80);source.background='transparent';source.nodes=[child];source.opacity=.5;source.blendMode=mode;
                  const target=GalleryCore.frame(80,80);target.background='#336699';
                  const gallery={frames:[source,target],components:{},selectedSurface:source.id,selection:[],surfaceSelection:[]};
                  const component=GalleryCore.frameToComponent(gallery,source.id);
                  gallery.selectedSurface=target.id;
                  const instance=GalleryCore.addInstance(gallery,component.id);
                  Object.assign(instance,{x:0,y:0,width:80,height:80});
                  const pixels=[];
                  for(const preview of [false,true]){
                    await GalleryRenderer.paint(document.getElementById('result'),{gallery,slots:{}},target,()=>null,{preview});
                    pixels.push(Array.from(document.getElementById('result').getContext('2d').getImageData(40,40,1,1).data));
                  }
                  return {opacity:instance.opacity,mode:instance.blendMode,pixels};
                }''',mode)
                self.assertEqual(result['opacity'],.5)
                self.assertEqual(result['mode'],mode)
                self.assertEqual(result['pixels'][0],result['pixels'][1])
                # Native frame -> component -> instance must blend against the receiving frame.
                opaque=await self.page.evaluate('blendProbe',{'kind':'instance','mode':mode})
                expected=[round((a+b)/2) for a,b in zip([51,102,153],opaque[:3])]+[255]
                for value,wanted in zip(result['pixels'][0],expected):
                    self.assertLessEqual(abs(value-wanted),2,(mode,result,expected))


if __name__ == '__main__': unittest.main()
