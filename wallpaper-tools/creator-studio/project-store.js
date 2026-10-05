/* Local prototype adapter. Formal folder/server adapters can use the same records. */
(function(root){
  'use strict';
  const request=r=>new Promise((resolve,reject)=>{r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error);});
  const done=tx=>new Promise((resolve,reject)=>{tx.oncomplete=()=>resolve();tx.onabort=tx.onerror=()=>reject(tx.error||new Error('本地存储事务未完成'));});
  let opening;
  function open(){return opening ||= new Promise((resolve,reject)=>{const r=indexedDB.open('qingjing-creator-projects',1);r.onupgradeneeded=()=>{r.result.createObjectStore('projects',{keyPath:'id'});r.result.createObjectStore('media',{keyPath:'id'});r.result.createObjectStore('meta');};r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error);r.onblocked=()=>reject(new Error('请关闭旧版创作台标签后重试'));});}
  async function list(){const db=await open();return (await request(db.transaction('projects').objectStore('projects').getAll())).sort((a,b)=>b.updatedAt.localeCompare(a.updatedAt));}
  async function load(id){const db=await open();return request(db.transaction('projects').objectStore('projects').get(id));}
  async function media(ids){const db=await open(),tx=db.transaction('media'),store=tx.objectStore('media');return Promise.all(ids.map(id=>request(store.get(id))));}
  async function last(){const db=await open();return request(db.transaction('meta').objectStore('meta').get('lastProject'));}
  async function save(project,assets=[]){const db=await open(),tx=db.transaction(['projects','media','meta'],'readwrite'),finished=done(tx);for(const asset of assets)tx.objectStore('media').put(asset);tx.objectStore('projects').put(project);tx.objectStore('meta').put(project.id,'lastProject');await finished;}
  root.ProjectStore={list,load,media,last,save};
})(window);
