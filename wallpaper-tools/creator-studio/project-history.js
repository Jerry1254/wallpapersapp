/* Project-level action history. Media bytes live in the store, never in snapshots. */
(function(root){
  'use strict';
  const LIMIT=15,clone=value=>JSON.parse(JSON.stringify(value));
  function fingerprint(data){const p=clone(data);delete p.view;for(const v of Object.values(p.versions||{})){delete v.cursor;delete v.selectedClipId;delete v.zoom;}return JSON.stringify(p);}
  function create(data,label='新建项目'){return {entries:[{label,at:new Date().toISOString(),data:clone(data)}],index:0};}
  function commit(history,data,label){
    if(fingerprint(history.entries[history.index].data)===fingerprint(data))return false;
    history.entries.splice(history.index+1);
    history.entries.push({label,at:new Date().toISOString(),data:clone(data)});
    if(history.entries.length>LIMIT+1){history.entries.shift();history.entries[0].label='较早状态';}
    history.index=history.entries.length-1;return true;
  }
  function restore(history,index){if(!Number.isInteger(index)||index<0||index>=history.entries.length)return null;history.index=index;return clone(history.entries[index].data);}
  const api={LIMIT,create,commit,restore,fingerprint};root.ProjectHistory=api;
  if(typeof module!=='undefined'&&module.exports)module.exports=api;
})(typeof window!=='undefined'?window:globalThis);
