/* Mirrors the current admin editor's credit-pack pricing rules. No Apple calls. */
(function(root){
  'use strict';
  function iosCreditPack(credits){
    if(!credits||!Number.isInteger(credits)||credits<1||credits>30)return null;
    for(let pack=1;pack<=3;pack++)if(credits%pack===0&&credits/pack<=10)return {pack,quantity:credits/pack};
    return null;
  }
  function iosPriceSyncLabel(value){
    switch(value.priceSyncStatus){
      case 'READY':return value.acquisitionMode==='CREDITS'?'积分商品价格核验通过':value.chinaReferencePrice?`¥${value.chinaReferencePrice}`:'价格同步中';
      case 'ERROR':return '同步失败，请重试';
      case 'STALE':return '价格已过期，等待同步';
      case 'UNAVAILABLE':return '自动查价尚未启用';
      default:return '等待从 Apple 同步';
    }
  }
  function normalize(value){
    return {credits:null,productId:'',chinaReferencePrice:null,productIdLocked:false,verifiedTransactionAt:null,...value,acquisitionMode:value?.acquisitionMode||(value?.productId?'NON_CONSUMABLE':'CREDITS'),enabled:true,firstFreeEligible:true};
  }
  const api={iosCreditPack,iosPriceSyncLabel,normalize};
  root.IosPricing=api;if(typeof module!=='undefined'&&module.exports)module.exports=api;
})(typeof window!=='undefined'?window:globalThis);
