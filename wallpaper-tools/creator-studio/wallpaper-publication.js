/* Wallpaper delivery uses admin asset IDs. The journal is outside undoable project data. */
(() => {
  'use strict';
  const B=window.CreatorBackend, sessions=new Map();
  const pairs={static:['UNIVERSAL','STATIC_IMAGE'],android:['ANDROID','VIDEO'],ios:['IOS','LIVE_PHOTO'],harmony:['HARMONYOS','MOVING_PHOTO'],'4d':['ANDROID','LAYER_PARALLAX']};
  const active=task=>['QUEUED','RUNNING'].includes(task?.state);
  const api=(path,options)=>B.request('/wallpaper'+path,options);
  const codeError=(code,message)=>Object.assign(new Error(message),{code});
  function explain(error){
    const hints={VERSION_CONFLICT:'壁纸已在其他页面修改。请重新读取资料，查看变化后再提交。',CREATOR_ENVIRONMENT_MISMATCH:'当前后台环境与这个项目的上传记录不一致，请连接原后台。',CREATOR_RULES_CHANGED:'后台上传规则已更新，请重新打开创建壁纸，再提交。',IDEMPOTENCY_CONFLICT:'这次提交的请求记录不一致，请保留原记录并重新打开。',CREATOR_WALLPAPER_LINK_CONFLICT:'项目已关联壁纸，请重新读取已有任务，继续更新原壁纸。',PUBLICATION_SELECTION_INCOMPLETE:'需要保留未替换的平台资源，请重新读取壁纸后提交。'};
    const details=(error.details||[]).map(d=>d.message).filter(Boolean).join('；');
    return (hints[error.code]||error.message||'请求未完成，请重试')+(details?' '+details:'');
  }
  function stage(task){
    if(!task)return '尚未提交';
    if(task.state==='NEEDS_INPUT')return '需要调整资料或资源：'+(task.errorMessage||'请重新读取资料');
    if(task.state==='FAILED')return (task.retryable?'处理失败，可继续原任务：':'处理失败：')+(task.errorMessage||'请重试');
    if(task.state==='SUCCEEDED')return task.action==='PUBLISH'?'上架任务已完成，正在读取壁纸状态':'草稿任务已完成';
    if(task.state==='QUEUED')return '已提交，等待后台处理';
    if(task.stage==='WALLPAPER')return '正在保存壁纸资料';
    if(task.stage==='IOS_CONFIGURATION')return '正在保存 iOS 获取配置';
    if(task.stage?.startsWith('VARIANT_'))return '正在建立平台资源';
    if(task.stage?.startsWith('RESOURCE_'))return '正在保存正式资源版本';
    if(task.stage?.startsWith('PREPARE_'))return '正在处理正式交付文件';
    return '后台正在处理';
  }
  class Publication {
    constructor(projectId){this.projectId=projectId;this.id='wallpaper-publication-'+projectId;this.record=null;this.product=null;this.busy=false;this.listeners=new Set();this.message='';this.timer=null;}
    get task(){return this.record?.task;}
    get unresolved(){return this.record?.intent?.state==='SUBMITTING';}
    get processing(){return this.busy||active(this.task)||this.unresolved;}
    notify(message){if(message!==undefined)this.message=message;for(const listener of this.listeners)listener(this);}
    subscribe(listener){this.listeners.add(listener);listener(this);return ()=>this.listeners.delete(listener);}
    async save(){await B.put('meta',this.record);}
    async load(){
      await B.requireConnection();if(this.busy)return this;this.rules=await api('/capabilities');
      const environment=this.rules.environment?.id;
      if(!environment||environment==='UNCONFIGURED'||!this.rules.supportedOperations?.wallpaperPublication)throw new Error('壁纸后台尚未配置上传与上架能力');
      if(!this.busy)this.record=await B.get('meta',this.id)||{id:this.id,projectId:this.projectId,clientProjectKey:'studio-project-'+this.projectId,environmentId:environment,uploads:{}};
      if(this.record.environmentId!==environment)throw codeError('CREATOR_ENVIRONMENT_MISMATCH','上传记录属于另一个后台环境');
      if(this.busy)return this;
      // Recover the project association even if the browser lost the submission response.
      let page=1,found=false;
      do{
        const result=await api('/publications?clientProjectKey='+encodeURIComponent(this.record.clientProjectKey)+'&page='+page+'&pageSize=20');
        if(page===1&&result.items?.length)this.record.task=result.items[0];
        const linked=result.items?.find(task=>task.wallpaperId);
        if(linked){this.record.wallpaperId=linked.wallpaperId;found=true;}
        if(found||page>=(result.page?.totalPages||1))break;
        page++;
      }while(true);
      if(this.record.wallpaperId)this.product=await api('/products/'+this.record.wallpaperId);
      await this.save();this.notify(this.status());this.schedule();return this;
    }
    status(){
      const task=this.task,product=this.product;
      if(this.unresolved&&!active(task))return '上次提交的结果尚未确认，点击「恢复提交」读取原任务';
      if(active(task)||['FAILED','NEEDS_INPUT'].includes(task?.state))return stage(task);
      if(task?.state==='SUCCEEDED'&&task.action==='PUBLISH'&&task.result?.status==='PUBLISHED'&&product?.status==='PUBLISHED'){
        if(product.previewGenerationStatus==='FAILED')return '已上架，预览生成失败；可在管理后台重试预览';
        return ['PENDING','PROCESSING'].includes(product.previewGenerationStatus)?'已上架，预览处理中':'已上架';
      }
      if(task?.state==='SUCCEEDED'&&task.action==='SAVE_DRAFT'&&['DRAFT','OFFLINE'].includes(task.result?.status)&&product?.status===task.result.status)return product.status==='OFFLINE'?'资料已保存，壁纸保持下架状态':'壁纸草稿已保存到后台';
      if(product)return {PUBLISHED:'后台壁纸当前已上架',DRAFT:'后台壁纸当前为草稿',OFFLINE:'后台壁纸当前已下架',ARCHIVED:'后台壁纸当前已归档'}[product.status]||'已读取后台壁纸';
      return '填写资料后，可以保存草稿或提交上架';
    }
    schedule(){
      clearTimeout(this.timer);
      const previews=this.task?.state==='SUCCEEDED'&&this.task.action==='PUBLISH'&&['PENDING','PROCESSING'].includes(this.product?.previewGenerationStatus);
      if((active(this.task)||previews)&&B.connected)this.timer=setTimeout(()=>this.refresh().catch(error=>{this.notify(explain(error)+'；可重新打开壁纸查看状态');this.schedule();}),1500);
    }
    async accept(task){
      if(task.environmentId!==this.record.environmentId||task.clientProjectKey!==this.record.clientProjectKey)throw codeError('CREATOR_ENVIRONMENT_MISMATCH','任务不属于当前项目或环境');
      const changed=B.canonical(this.task)!==B.canonical(task);this.record.task=task;
      if(task.wallpaperId)this.record.wallpaperId=task.wallpaperId;
      if(changed)await this.save();
      if(task.state==='SUCCEEDED'||task.state==='NEEDS_INPUT'||task.state==='FAILED'){
        if(this.record.wallpaperId)this.product=await api('/products/'+this.record.wallpaperId);
        if(task.state==='SUCCEEDED'&&this.product?.version===task.result?.version){this.record.observedVersion=this.product.version;await this.save();}
      }
      this.notify(this.status());this.schedule();return task;
    }
    async refresh(){
      if(this.busy)return;
      if(this.task)await this.accept(await api('/publications/'+this.task.taskId));
      else if(this.record.wallpaperId){this.product=await api('/products/'+this.record.wallpaperId);this.notify(this.status());}
      this.schedule();
    }
    async freshProduct(){
      if(!this.record.wallpaperId)return null;
      const product=await api('/products/'+this.record.wallpaperId);
      if(this.product&&product.version!==this.product.version)throw codeError('VERSION_CONFLICT','壁纸已在其他页面修改');
      return product;
    }
    rule(key){const [platform,resourceType]=pairs[key]||[];return this.rules.wallpaperVariants.find(rule=>rule.platform===platform&&rule.resourceType===resourceType);}
    async upload(file,purpose,rule){
      if(!(file instanceof Blob)||!file.size)throw new Error('素材文件无法读取，请重新导入');
      const maximum=rule?.maximumBytes||this.rules.coverMaximumBytes,mimes=rule?.acceptedMimeTypes||this.rules.coverMimeTypes;
      if(file.size>maximum)throw new Error(`${file.name} 超过后台允许的 ${(maximum/1048576).toFixed(0)} MB`);
      if(purpose!=='PARALLAX_ZIP'&&!mimes.includes(file.type))throw new Error(`${file.name} 的格式不符合后台要求`);
      const sha256=(await B.hash(file)).slice(5),filename=file.name||'wallpaper.zip';
      const cacheKey=await B.hash(new Blob([B.canonical({environment:this.record.environmentId,purpose,sha256,filename,mime:file.type})]));
      let uploaded=this.record.uploads[cacheKey];
      if(uploaded?.result&&purpose!=='PARALLAX_ZIP'){
        const current=await api('/assets/'+uploaded.result.id);
        if(current.validationStatus!=='READY'||current.sha256!==uploaded.result.sha256)throw new Error('已上传的文件未就绪或已失效，请重新导入资源');
        return current;
      }
      if(uploaded?.result&&purpose==='PARALLAX_ZIP')return uploaded.result;
      if(!uploaded){uploaded={key:crypto.randomUUID(),purpose,sha256,filename,mimeType:file.type};this.record.uploads[cacheKey]=uploaded;await this.save();}
      const body=new FormData();body.append('file',file,filename);if(purpose!=='PARALLAX_ZIP')body.append('purpose',purpose);
      const result=await api(purpose==='PARALLAX_ZIP'?'/parallax-packages':'/assets',{method:'POST',body,headers:{'Idempotency-Key':uploaded.key}});
      // Covers are optimized to WebP by the backend; its stored hash differs
      // from the input hash used above for idempotency. Formal resources stay exact.
      const validHash=purpose==='WALLPAPER_COVER'
        ?/^[a-f0-9]{64}$/.test(result.sha256)&&result.mimeType==='image/webp'&&result.widthPx>0&&result.heightPx>0
        :result.sha256===sha256;
      if(!/^[1-9][0-9]*$/.test(result.id)||!validHash||result.validationStatus!=='READY')throw new Error('后台未返回可用的正式文件，请检查素材后重试');
      uploaded.result=result;await this.save();return result;
    }
    async submit(action,form,cover,resources){
      if(this.processing)throw new Error('当前提交尚未完成，请查看原任务');
      this.busy=true;clearTimeout(this.timer);
      try{
        const latestRules=await api('/capabilities');
        if(latestRules.environment?.id!==this.record.environmentId)throw codeError('CREATOR_ENVIRONMENT_MISMATCH','后台环境发生变化');
        if(latestRules.rulesVersion!==this.rules.rulesVersion)throw codeError('CREATOR_RULES_CHANGED','上传规则已更新');
        const product=await this.freshProduct();
        if(product&&product.version!==form.expectedWallpaperVersion)throw codeError('VERSION_CONFLICT','壁纸资料已发生变化');
        if(action==='SAVE_DRAFT'&&product?.status==='PUBLISHED')throw new Error('已上架壁纸请使用「提交上架更新」保存修改');
        if(product?.status==='ARCHIVED')throw new Error('已归档的壁纸不能更新');
        this.record.form=form;await this.save();
        this.notify('正在准备列表封面');const coverAsset=cover?await this.upload(cover,'WALLPAPER_COVER'):product?.cover?.id?await api('/assets/'+product.cover.id):null;
        if(!coverAsset||coverAsset.validationStatus!=='READY')throw new Error('请先选择可用的列表封面');
        const uploaded=[];
        if(action==='PUBLISH')for(const source of resources){
          const rule=this.rule(source.key);if(!rule)throw new Error('后台暂不支持 '+source.title);
          this.notify('正在准备 '+source.title);const file=await source.file();this.notify('正在上传 '+source.title);
          const result=await this.upload(file,rule.uploadPurpose,rule);
          uploaded.push({platform:rule.platform,resourceType:rule.resourceType,...(source.key==='4d'?{sourcePackageId:result.id}:{assetId:result.id})});
        }
        const replacements=new Set(uploaded.map(r=>r.platform+':'+r.resourceType));
        const retained=[];
        if(action==='PUBLISH')for(const variant of product?.variants||[]){
          if(!variant.enabled||replacements.has(variant.platform+':'+variant.resourceType))continue;
          const versions=variant.resourceVersions.filter(v=>['READY','PUBLISHED'].includes(v.status));
          versions.sort((a,b)=>(b.status==='PUBLISHED')-(a.status==='PUBLISHED')||b.versionNo-a.versionNo);
          if(versions.length)retained.push(versions[0].id);
        }
        if(action==='PUBLISH'&&!uploaded.length&&!retained.length)throw new Error('请至少带入一种正式资源');
        const metadata={title:form.title,slug:form.slug,accessType:form.accessType,rootCategoryId:form.categoryId,childCategoryId:form.subcategoryId||null,coverAssetId:coverAsset.id,featuredRank:form.featuredRank,sortOrder:form.sort,copyrightNote:form.copyrightNote,previewWatermarkEnabled:form.previewWatermarkEnabled,offlinePromotionOnly:form.offlinePromotionOnly};
        const hasIOS=form.hasIOS||uploaded.some(r=>r.platform==='IOS')||(product?.variants||[]).some(v=>v.platform==='IOS'&&v.enabled);
        const ios=hasIOS?Object.fromEntries(this.rules.supportedIosAcquisitionFields.filter(key=>form.iosAcquisition[key]!==undefined&&!(key==='productId'&&!form.iosAcquisition[key])&&!(key==='credits'&&form.iosAcquisition.acquisitionMode!=='CREDITS')).map(key=>[key,form.iosAcquisition[key]])):null;
        const payload={environmentId:this.record.environmentId,clientProjectKey:this.record.clientProjectKey,action,wallpaperId:product?.id||null,expectedWallpaperVersion:product?.version??null,metadata,resources:uploaded,retainResourceVersionIds:retained,iosAcquisition:ios,rulesVersion:this.rules.rulesVersion};
        this.record.intent={key:crypto.randomUUID(),payload,state:'SUBMITTING',createdAt:new Date().toISOString()};await this.save();
        return await this.sendIntent();
      }finally{this.busy=false;this.notify();this.schedule();}
    }
    async sendIntent(){
      const intent=this.record.intent;this.notify('正在提交后台任务');
      let task;
      try{task=await api('/publications',{method:'POST',data:intent.payload,headers:{'Idempotency-Key':intent.key}});}
      catch(error){
        // A lost response may already have committed. Keep the exact request for replay.
        if(error.status&&error.status<500){intent.state='REJECTED';intent.error={code:error.code,message:error.message};await this.save();}
        throw error;
      }
      intent.state='ACCEPTED';this.record.task=task;if(task.wallpaperId)this.record.wallpaperId=task.wallpaperId;
      await this.save();return await this.accept(task);
    }
    async resume(){if(this.busy)return;this.busy=true;try{return await this.sendIntent();}finally{this.busy=false;this.notify();this.schedule();}}
    async retry(){if(this.busy||!this.task?.retryable||this.task.state!=='FAILED')return;this.busy=true;try{return await this.accept(await api('/publications/'+this.task.taskId+'/retry',{method:'POST'}));}finally{this.busy=false;this.notify();this.schedule();}}
    async check(){
      const product=await this.freshProduct();if(!product)throw new Error('保存壁纸并建立资源版本后才可以检查');
      const ids=(product.variants||[]).filter(v=>v.enabled).flatMap(v=>{const versions=v.resourceVersions.filter(r=>['READY','PUBLISHED'].includes(r.status)).sort((a,b)=>(b.status==='PUBLISHED')-(a.status==='PUBLISHED')||b.versionNo-a.versionNo);return versions.length?[versions[0].id]:[];});
      if(!ids.length)throw new Error('当前只有壁纸资料草稿，还没有正式资源版本');
      return api('/products/'+product.id+'/publication-check',{method:'POST',data:{resourceVersionIds:ids}});
    }
  }
  async function open(projectId){let session=sessions.get(projectId);if(!session){session=new Publication(projectId);sessions.set(projectId,session);}return session.load();}
  window.WallpaperPublication={open,stage,explain,active};
})();
