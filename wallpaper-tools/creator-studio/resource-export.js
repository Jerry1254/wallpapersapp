/* Existing binary files only; packaging never changes media bytes. */
(function(root){
  'use strict';
  const filename=value=>String(value||'未命名资源').replace(/[\\/:*?"<>|\u0000-\u001f]/g,'_').replace(/^\.+/,'_').trim()||'未命名资源';
  function archiveEntries(rows){
    const used=new Set();
    return rows.map(row=>{
      const folder=filename(row.group),base=filename(row.file.name),dot=base.lastIndexOf('.'),stem=dot>0?base.slice(0,dot):base,ext=dot>0?base.slice(dot):'';
      let path=`${folder}/${base}`,index=2;
      while(used.has(path.toLowerCase()))path=`${folder}/${stem} (${index++})${ext}`;
      used.add(path.toLowerCase());return {path,file:row.file};
    });
  }
  async function pack(rows,JSZip){
    const zip=new JSZip();for(const entry of archiveEntries(rows))zip.file(entry.path,entry.file);
    return zip.generateAsync({type:'blob',compression:'STORE'});
  }
  const api={filename,archiveEntries,pack};root.ResourceExport=api;
  if(typeof module!=='undefined'&&module.exports)module.exports=api;
})(typeof window!=='undefined'?window:globalThis);
