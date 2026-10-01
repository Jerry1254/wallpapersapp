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

assert.equal(contract.info.version, '0.2.0');
assert.match(config, /'IOS_ACQUISITION_ENABLED',\s*defaultValue: false/,
  'unimplemented iOS API must stay disabled in standard builds');
// The new reset/admin/notification routes are proposed contracts, not implemented clients.
const existingClientPaths = [
  '/device/ios/attestation/challenges',
  '/device/ios/attestation/registrations',
  '/device/ios/acquisition/status',
  '/device/ios/acquisition/free-claims',
  '/device/ios/acquisition/purchases',
];
for (const path of existingClientPaths) {
  assert.ok(client.includes(`'${path}'`), `draft path not used by client: ${path}`);
  assert.ok(contract.paths[path].post, 'all sensitive operations use signed POST requests');
}
const reset = contract.paths['/device/ios/acquisition/free-resets/{resetId}/complete'].post;
assert.deepEqual(reset.parameters.map(p => p.$ref), [
  '#/components/parameters/ResetId', '#/components/parameters/AppAttestKey',
  '#/components/parameters/AppAttestAssertion',
]);
const resetBody = reset.requestBody.content['application/json'].schema;
assert.equal(resetBody.additionalProperties, false, 'device cannot choose reset bits or test status');
assert.deepEqual(resetBody.required, ['challengeId', 'nonce', 'deviceToken', 'expectedGeneration']);
assert.ok(reset.responses['200'] && reset.responses['202'], 'accepted reset is distinct from completed reset');
assert.ok(contract.paths['/device/ios/attestation/challenges'].post.requestBody.content['application/json']
  .schema.properties.action.enum.includes('FREE_RESET'));
for (const [path, methods] of Object.entries(contract.paths)) {
  if (!path.startsWith('/admin/')) continue;
  for (const [method, operation] of Object.entries(methods)) {
    assert.deepEqual(operation.security, method === 'get'
      ? [{adminCookie: []}] : [{adminCookie: [], adminCsrf: []}],
    `admin-only authorization and write CSRF required: ${method} ${path}`);
  }
}
const createReset = contract.paths['/admin/devices/{deviceId}/ios-free-resets'].post;
assert.ok(createReset.parameters.some(p => p.name === 'Idempotency-Key' && p.required));
assert.deepEqual(createReset.requestBody.content['application/json'].schema.required,
  ['expectedGeneration', 'reason']);
assert.ok(contract.components.schemas.AcquisitionState.required.includes('freeGeneration'));
assert.equal(contract.components.schemas.FreeGeneration.minimum, 0);
const notification = contract.paths['/integrations/apple/app-store-notifications'].post;
assert.deepEqual(notification.security, [], 'Apple notifications do not carry a device session');
assert.deepEqual(notification.requestBody.content['application/json'].schema.required, ['signedPayload']);
assert.match(notification.description, /验证 Apple JWS/, 'session-free callback still requires Apple authentication');
for (const path of ['/device/ios/acquisition/status', '/device/ios/acquisition/free-claims', '/device/ios/acquisition/purchases']) {
  assert.deepEqual(contract.paths[path].post.parameters.map(p => p.$ref), [
    '#/components/parameters/AppAttestKey', '#/components/parameters/AppAttestAssertion',
  ]);
}
assert.deepEqual(contract.components.schemas.AcquisitionState.properties.freeAllowance.enum,
  ['AVAILABLE', 'USED', 'UNAVAILABLE', 'PENDING_RESET']);
assert.deepEqual(
  contract.paths['/device/ios/acquisition/purchases'].post.requestBody.content['application/json'].schema.required,
  ['challengeId', 'nonce', 'signedTransaction', 'signedAppTransaction', 'deviceVerificationId']);
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
console.log('iOS acquisition/reset draft, authorization boundaries, and StoreKit test isolation verified.');
