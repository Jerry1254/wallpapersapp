import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { parse } from 'yaml';

const read = path => readFile(new URL(path, import.meta.url), 'utf8');
const contract = parse(await read('../ios-acquisition.draft.yaml'));
const config = await read('../../../apps/mobile/lib/config/app_config.dart');
const client = await read('../../../apps/mobile/lib/entitlements/ios_acquisition.dart');
const native = await read('../../../packages/wallpaper-ios/ios/Classes/IosAcquisitionBridge.swift');
const standardScheme = await read('../../../apps/mobile/ios/Runner.xcodeproj/xcshareddata/xcschemes/Runner.xcscheme');
const testScheme = await read('../../../apps/mobile/ios/Runner.xcodeproj/xcshareddata/xcschemes/Runner-StoreKit.xcscheme');
const testProducts = JSON.parse(await read('../../../apps/mobile/ios/StoreKit/QingjingPurchases.storekit'));

assert.equal(contract.info.version, '0.1.0');
assert.match(config, /'IOS_ACQUISITION_ENABLED',\s*defaultValue: false/,
  'unimplemented iOS API must stay disabled in standard builds');
for (const path of Object.keys(contract.paths)) {
  assert.ok(client.includes(`'${path}'`), `draft path not used by client: ${path}`);
  assert.ok(contract.paths[path].post, 'all sensitive operations use signed POST requests');
}
for (const path of ['/device/ios/acquisition/status', '/device/ios/acquisition/free-claims', '/device/ios/acquisition/purchases']) {
  assert.deepEqual(contract.paths[path].post.parameters.map(p => p.$ref), [
    '#/components/parameters/AppAttestKey', '#/components/parameters/AppAttestAssertion',
  ]);
}
assert.deepEqual(contract.components.schemas.AcquisitionState.properties.freeAllowance.enum,
  ['UNKNOWN', 'AVAILABLE', 'USED', 'UNAVAILABLE']);
assert.deepEqual(contract.components.schemas.AcquisitionState.properties.purchasedWallpaperIds.items,
  {$ref: '#/components/schemas/WallpaperId'});
assert.ok(!standardScheme.includes('StoreKitConfigurationFileReference'),
  'normal iOS scheme must not inject test purchases');
assert.ok(testScheme.includes('QingjingPurchases.storekit'));
assert.ok(!testScheme.includes('<ArchiveAction'), 'test scheme cannot archive an app');
assert.ok(testProducts.products.every(p => p.type === 'NonConsumable' && p.productID.startsWith('com.qingjing.bizhi.test.')));
assert.match(native, /#if DEBUG[\s\S]*app\.environment == \.xcode/,
  'test-only purchases must validate Xcode environment before starting payment');
assert.ok(!client.includes("environment == 'XCODE'"),
  'the production coordinator must never approve purchases using a client environment flag');
console.log('iOS acquisition draft, front-end boundary, and StoreKit test isolation verified.');
