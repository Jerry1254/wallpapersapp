const test=require('node:test');
const assert=require('node:assert/strict');
const Pricing=require('./ios-pricing.js');

test('accepts exactly the admin price list and charges the entered amount without rounding',()=>{
  const supported=[1,2,3,4,5,6,7,8,9,10,12,14,15,16,18,20,21,24,27,30];
  for(let price=1;price<=30;price++){
    const selection=Pricing.iosCreditPack(price);
    assert.equal(Boolean(selection),supported.includes(price),`price ${price}`);
    if(selection){assert.equal(selection.pack*selection.quantity,price);assert.ok(selection.quantity<=10);assert.ok([1,2,3].includes(selection.pack));}
  }
  assert.deepEqual(Pricing.iosCreditPack(3),{pack:1,quantity:3});
  assert.deepEqual(Pricing.iosCreditPack(14),{pack:2,quantity:7});
  assert.deepEqual(Pricing.iosCreditPack(21),{pack:3,quantity:7});
});
test('rejects missing, fractional, out-of-range and unsupported prices',()=>{
  for(const price of [null,undefined,NaN,Infinity,-1,0,3.5,11,13,17,29,31,'3'])assert.equal(Pricing.iosCreditPack(price),null);
});
test('uses credits for new products and preserves old product identifiers during migration',()=>{
  const fresh=Pricing.normalize();assert.equal(fresh.acquisitionMode,'CREDITS');assert.equal(fresh.credits,null);assert.equal(fresh.firstFreeEligible,true);assert.equal(fresh.enabled,true);
  const legacy=Pricing.normalize({productId:'com.qingjing.legacy',productIdLocked:true,enabled:false});assert.equal(legacy.acquisitionMode,'NON_CONSUMABLE');assert.equal(legacy.productId,'com.qingjing.legacy');assert.equal(legacy.productIdLocked,true);assert.equal(legacy.enabled,false);
  const switched=Pricing.normalize({...legacy,acquisitionMode:'CREDITS',credits:3});assert.equal(switched.acquisitionMode,'CREDITS');assert.equal(switched.productId,legacy.productId);assert.equal(switched.credits,3);
});
test('shows successful verification only for an explicit READY result',()=>{
  assert.equal(Pricing.iosPriceSyncLabel({acquisitionMode:'CREDITS'}),'等待从 Apple 同步');
  assert.equal(Pricing.iosPriceSyncLabel({acquisitionMode:'CREDITS',priceSyncStatus:'READY'}),'积分商品价格核验通过');
  assert.equal(Pricing.iosPriceSyncLabel({priceSyncStatus:'ERROR'}),'同步失败，请重试');
});
