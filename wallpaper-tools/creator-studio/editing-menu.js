/* Editing commands stay inside the workbench; text fields keep native editing menus. */
(() => {
  'use strict';
  const providers=[],menus=[],editable='textarea,input:not([type="checkbox"]):not([type="radio"]):not([type="range"]):not([type="color"]):not([type="button"]):not([type="submit"]):not([type="file"]),[contenteditable]:not([contenteditable="false"])';
  let restoreFocus=null;
  const shortcut=(mac,other)=>/Mac|iPhone|iPad/.test(navigator.platform)?mac:other;
  function close(focus=false){
    menus.splice(0).forEach(m=>m.element.remove());
    const target=restoreFocus;restoreFocus=null;
    if(focus&&target?.isConnected)target.focus({preventScroll:true});
  }
  function closeChildren(level){
    menus.splice(level+1).forEach(m=>m.element.remove());
    menus[level]?.element.querySelectorAll('[aria-expanded]').forEach(b=>b.setAttribute('aria-expanded','false'));
  }
  function position(element,x,y){
    const padding=8,rect=element.getBoundingClientRect();
    element.style.left=Math.max(padding,Math.min(x,window.innerWidth-rect.width-padding))+'px';
    element.style.top=Math.max(padding,Math.min(y,window.innerHeight-rect.height-padding))+'px';
  }
  function open(items,x,y,level=0,parentButton=null){
    const element=document.createElement('div');element.className='editing-context-menu';
    element.setAttribute('role','menu');element.setAttribute('aria-label',level?'编辑子菜单':'工作台编辑菜单');
    const entry={element,parentButton,buttons:[]};menus.push(entry);
    for(const item of items){
      if(!item){const line=document.createElement('div');line.className='editing-menu-separator';line.setAttribute('role','separator');element.append(line);continue;}
      const button=document.createElement('button');button.type='button';button.tabIndex=-1;
      button.setAttribute('role','menuitem');button.disabled=!!item.disabled;
      if(item.danger)button.className='editing-menu-danger';
      const label=document.createElement('span');label.className='editing-menu-label';label.textContent=item.label;button.append(label);
      if(item.children){button.setAttribute('aria-haspopup','menu');button.setAttribute('aria-expanded','false');const arrow=document.createElement('span');arrow.textContent='›';arrow.className='editing-menu-arrow';button.append(arrow);}
      else if(item.shortcut){const key=document.createElement('span');key.className='editing-menu-key';key.textContent=item.shortcut;button.append(key);}
      const expand=(focus=false)=>{
        if(button.disabled||!item.children)return;
        if(menus[level+1]?.parentButton!==button){
          closeChildren(level);button.setAttribute('aria-expanded','true');
          const r=button.getBoundingClientRect(),child=open(item.children,r.right+5,r.top-6,level+1,button),b=child.element.getBoundingClientRect();
          if(r.right+5+b.width>window.innerWidth-8)position(child.element,r.left-b.width-5,r.top-6);
        }
        if(focus)menus[level+1]?.buttons.find(b=>!b.disabled)?.focus({preventScroll:true});
      };
      button.onpointerenter=()=>{if(button.disabled)return;button.focus({preventScroll:true});if(item.children)expand();else closeChildren(level);};
      button.onclick=()=>{if(item.children){expand(true);return;}close(true);item.run?.();};
      button.expand=expand;entry.buttons.push(button);element.append(button);
    }
    document.body.append(element);position(element,x,y);return entry;
  }
  function show(items,event,focus){
    close();if(!items?.length)return;restoreFocus=focus||event.target.closest('[tabindex]')||document.activeElement;
    const menu=open(items,event.clientX,event.clientY);menu.buttons.find(b=>!b.disabled)?.focus({preventScroll:true});
  }
  function command(label,id,key,scope){
    const button=document.getElementById(id);
    if(!button||button.hidden||!button.getClientRects().length||scope&&!scope.contains(button))return null;
    return {label,shortcut:key,disabled:button.disabled,run:()=>button.click()};
  }
  function fallback(scope){
    const items=[];
    if(scope.id==='wallpaper-workspace'){
      for(const [label,id,key] of [['撤销编辑','undo-edit',shortcut('⌘ Z','Ctrl+Z')],['重做编辑','redo-edit',shortcut('⇧ ⌘ Z','Ctrl+Shift+Z')],['剪断片段','split-clip',shortcut('⌘ B','Ctrl+B')],['创建副本','duplicate-clip'],['删除片段','delete-clip','Delete'],['画面回正','reset-crop']]){const item=command(label,id,key,scope);if(item)items.push(item);}
      if(items.length)items.push(null);
    }
    const add=scope.querySelector('[data-import],#content-import');
    if(add)items.push({label:'导入素材…',run:()=>add.click()});
    for(const [label,id] of [['打开内容库…','content-library-btn'],['模板库…','content-save-template']]){const item=command(label,id,'',scope);if(item)items.push(item);}
    if(!items.length)items.push({label:'打开项目列表…',run:()=>document.getElementById('project-list-btn')?.click()});
    return items;
  }
  document.addEventListener('contextmenu',event=>{
    if(event.target.closest('.editing-context-menu')){event.preventDefault();return;}
    const scope=event.target.closest('.workspace-view');
    if(!scope||scope.hidden||event.target.closest(editable)||document.getElementById('dialog')?.open){close();return;}
    event.preventDefault();close();
    const provider=providers.slice().reverse().find(p=>p.matches(scope,event));
    const items=provider?provider.items(event):fallback(scope);
    show(items,event,provider?.focus?.());
  });
  document.addEventListener('pointerdown',event=>{if(menus.length&&!event.target.closest('.editing-context-menu'))close();},true);
  document.addEventListener('keydown',event=>{
    if(!menus.length)return;
    if(!['Escape','ArrowDown','ArrowUp','ArrowRight','ArrowLeft','Home','End','Enter',' ','Tab'].includes(event.key)){close(true);return;}
    event.preventDefault();event.stopImmediatePropagation();
    if(event.key==='Escape'||event.key==='Tab'){close(true);return;}
    const level=menus.findIndex(m=>m.element.contains(document.activeElement)),menu=menus[level<0?0:level],buttons=menu.buttons.filter(b=>!b.disabled),index=buttons.indexOf(document.activeElement);
    if(!buttons.length)return;
    if(event.key==='ArrowRight'){document.activeElement.expand?.(true);return;}
    if(event.key==='ArrowLeft'){if(level>0){const button=menu.parentButton;closeChildren(level-1);button.focus({preventScroll:true});}return;}
    if(event.key==='Enter'||event.key===' '){buttons[Math.max(0,index)].click();return;}
    const next=event.key==='Home'?0:event.key==='End'?buttons.length-1:(index+(event.key==='ArrowDown'?1:-1)+buttons.length)%buttons.length;
    closeChildren(level<0?0:level);buttons[next].focus({preventScroll:true});
  },true);
  document.addEventListener('scroll',event=>{if(!menus.length)return;if(event.target.closest?.('.editing-context-menu'))return;close();},true);
  window.addEventListener('resize',()=>close());window.addEventListener('blur',()=>close());
  window.EditingMenu={register:provider=>providers.push(provider),close,shortcut};
})();
