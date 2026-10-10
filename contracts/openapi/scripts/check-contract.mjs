import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import { parse } from 'yaml';

const document = parse(await readFile(new URL('../openapi.yaml', import.meta.url), 'utf8'));
const applicationConfiguration = await readFile(
  new URL('../../../services/api-server/src/main/resources/application.yml', import.meta.url),
  'utf8'
);

assert.ok(
  applicationConfiguration.includes(`contract-version: \${QJ_API_CONTRACT_VERSION:${document.info.version}}`),
  'runtime actuator contract version must default to the OpenAPI version'
);

const expectedOperations = {
  '/public/categories': ['get'],
  '/public/wallpapers': ['get'],
  '/public/wallpapers/{wallpaperId}': ['get'],
  '/public/wallpaper-tutorials': ['get'],
  '/public/wallpaper-tutorials/{tutorialKey}/video': ['get', 'head'],
  '/device/registrations': ['post'],
  '/device/session-challenges': ['post'],
  '/device/sessions': ['post'],
  '/device/me/entitlements': ['get'],
  '/device/encryption-key': ['put'],
  '/device/redemptions': ['post'],
  '/device/redemptions/{idempotencyKey}': ['get'],
  '/device/wallpapers/{wallpaperId}/download-tickets': ['post'],
  '/device/wallpapers/{wallpaperId}/preview-tickets': ['post'],
  '/preview/files': ['get'],
  '/preview/moving-photo/video': ['get'],
  '/preview/live-photo/video': ['get'],
  '/delivery/moving-photo/poster': ['get'],
  '/delivery/moving-photo/video': ['get'],
  '/delivery/live-photo/image': ['get'],
  '/delivery/live-photo/video': ['get'],
  '/delivery/live-photo/source': ['get'],
  '/delivery/static-image': ['get'],
  '/admin/sessions': ['get', 'post', 'delete'],
  '/admin/assets': ['post'],
  '/admin/parallax-packages': ['post'],
  '/admin/variants/{variantId}/parallax-resource-versions': ['post'],
  '/admin/wallpaper-tutorials': ['get'],
  '/admin/wallpaper-tutorials/{tutorialKey}': ['put'],
  '/admin/categories': ['get', 'post'],
  '/admin/wallpapers': ['get', 'post'],
  '/admin/code-batches': ['get', 'post'],
  '/admin/redemptions': ['get'],
  '/admin/devices': ['get'],
  '/admin/operations/overview': ['get'],
  '/admin/devices/{deviceId}/note': ['put'],
  '/admin/devices/{deviceId}/purchases': ['get'],
  '/device/activity': ['post']
};

for (const [path, methods] of Object.entries(expectedOperations)) {
  assert.ok(document.paths[path], `missing required path ${path}`);
  for (const method of methods) {
    assert.ok(document.paths[path][method], `missing required operation ${method.toUpperCase()} ${path}`);
  }
}

const operations = [];
for (const [path, pathItem] of Object.entries(document.paths)) {
  for (const method of ['get', 'head', 'post', 'put', 'patch', 'delete']) {
    if (pathItem[method]) operations.push({ path, method, operation: pathItem[method] });
  }
}

const operationIds = operations.map(({ operation }) => operation.operationId);
assert.ok(operationIds.every(Boolean), 'every operation must define operationId');
assert.equal(new Set(operationIds).size, operationIds.length, 'operationId values must be unique');

assert.equal(document.components.schemas.LongId.type, 'string', 'LongId must remain a JSON string');
assert.deepEqual(
  document.components.schemas.DevicePlatform.enum,
  ['ANDROID', 'IOS', 'HARMONYOS'],
  'device identity platforms drifted'
);
assert.ok(
  document.components.schemas.DeviceRegistrationRequest.required.includes('publicKeyPem'),
  'formal device registration must require an installation public key'
);
assert.ok(
  document.components.schemas.DeviceSessionChallenge.properties.algorithm.enum.includes('ECDSA_P256_SHA256'),
  'iOS challenge algorithm must remain part of the public contract'
);
assert.ok(
  applicationConfiguration.includes('ios-enabled: ${QJ_DEVICE_IOS_ENABLED:true}')
    && applicationConfiguration.includes('${QJ_DEVICE_IOS_PROD_SCOPE:com.qingjing.bizhi}')
    && applicationConfiguration.includes('${QJ_DEVICE_IOS_TEST_SCOPE:com.qingjing.livephotolab}'),
  'runtime iOS provider scopes must match the public identity contract'
);
assert.deepEqual(
  document.components.schemas.RedemptionResultCode.enum,
  ['GRANTED', 'ALREADY_OWNED', 'CODE_NOT_FOUND', 'CODE_EXHAUSTED', 'WALLPAPER_UNAVAILABLE', 'WALLPAPER_FREE', 'FAILED'],
  'redemption result enum drifted from DM-001'
);
assert.deepEqual(document.components.schemas.WallpaperAccessType.enum, ['REDEEM', 'FREE']);
assert.equal(document.components.schemas.WallpaperWriteRequest.properties.offlinePromotionOnly.default, false);
assert.ok(document.components.schemas.AdminWallpaperSummary.required.includes('offlinePromotionOnly'));
assert.equal(document.components.schemas.AdminWallpaperSummary.properties.offlinePromotionOnly.type, 'boolean');
assert.ok(applicationConfiguration.includes('offline-android-enabled: ${QJ_DEVICE_OFFLINE_ANDROID_ENABLED:false}'));
assert.ok(document.paths['/wallpapers/{wallpaperId}/cover'].get.security.some(requirement => 'deviceBearer' in requirement));
assert.ok(document.paths['/public/assets/{assetId}/content'].get.security.some(requirement => 'deviceBearer' in requirement));
const releaseAppSelector = document.paths['/admin/app-releases'].get.parameters.find(parameter => parameter.name === 'packageName');
assert.deepEqual(releaseAppSelector.schema.enum, ['com.qingjing.bizhi', 'com.jiyi.wallpaper']);
assert.deepEqual(document.paths['/admin/app-releases/android'].post.requestBody.content['multipart/form-data'].schema.properties.packageName.enum,
  ['com.qingjing.bizhi', 'com.jiyi.wallpaper']);
for (const [path, schema] of [
  ['/public/wallpapers', 'PublicWallpaperSummary'],
  ['/admin/wallpapers', 'AdminWallpaperSummary']
]) {
  assert.ok(document.paths[path].get.parameters.some(parameter => parameter.name === 'accessType'));
  assert.equal(document.components.schemas[schema].properties.accessType.$ref, '#/components/schemas/WallpaperAccessType');
}
const publicWallpaperParameters = Object.fromEntries(
  document.paths['/public/wallpapers'].get.parameters
    .filter((parameter) => parameter.name)
    .map((parameter) => [parameter.name, parameter])
);
assert.equal(
  publicWallpaperParameters.deliveryPlatform.schema.$ref,
  '#/components/schemas/DeliveryPlatform',
  'public catalog must expose the exact delivery platform filter'
);
assert.equal(
  publicWallpaperParameters.resourceType.schema.$ref,
  '#/components/schemas/ResourceType',
  'public catalog must expose the exact resource type filter'
);
assert.deepEqual(
  publicWallpaperParameters.view.schema.enum,
  ['FEATURED'],
  'static capability must use deliveryPlatform/resourceType instead of a duplicate view'
);
assert.equal(
  document.components.schemas.WallpaperWriteRequest.properties.accessType.$ref,
  '#/components/schemas/WallpaperAccessType'
);

const parameterName = (parameter) => {
  if (parameter?.$ref) return parameter.$ref.split('/').at(-1);
  return parameter?.name;
};

for (const [path, method] of [
  ['/device/redemptions', 'post'],
  ['/admin/code-batches', 'post']
]) {
  const names = document.paths[path][method].parameters.map(parameterName);
  assert.ok(names.includes('IdempotencyKey'), `${method.toUpperCase()} ${path} must require Idempotency-Key`);
}

for (const [path, method] of [
  ['/admin/categories/{categoryId}', 'patch'],
  ['/admin/categories/{categoryId}', 'delete'],
  ['/admin/wallpapers/{wallpaperId}', 'patch'],
  ['/admin/wallpapers/{wallpaperId}', 'delete'],
  ['/admin/wallpapers/{wallpaperId}/publish', 'post'],
  ['/admin/wallpapers/{wallpaperId}/offline', 'post'],
  ['/admin/wallpapers/{wallpaperId}/archive', 'post'],
  ['/admin/variants/{variantId}', 'patch'],
  ['/admin/variants/{variantId}', 'delete'],
  ['/admin/wallpaper-tutorials/{tutorialKey}', 'put']
]) {
  const names = document.paths[path][method].parameters.map(parameterName);
  assert.ok(names.includes('IfMatch'), `${method.toUpperCase()} ${path} must require If-Match`);
}

const sensitiveDeviceOperations = [
  document.paths['/device/encryption-key'].put,
  document.paths['/device/redemptions'].post,
  document.paths['/device/activity'].post,
  document.paths['/device/security/harmony/challenges'].post,
  document.paths['/device/security/harmony/reports'].post,
  document.paths['/device/wallpapers/{wallpaperId}/download-tickets'].post,
  document.paths['/device/wallpapers/{wallpaperId}/preview-tickets'].post
];
for (const operation of sensitiveDeviceOperations) {
  const names = operation.parameters.map(parameterName);
  for (const required of ['RequestTimestamp', 'RequestNonce', 'RequestSignature']) {
    assert.ok(names.includes(required), `${operation.operationId} must include ${required}`);
  }
  assert.ok(operation.security?.some((requirement) => requirement.deviceBearer), `${operation.operationId} must require a device session`);
}

const forbiddenSchemaProperties = [
  'storageKey',
  'absolutePath',
  'codeHash',
  'codeKeyVersion',
  'evidenceHash',
  'secretHash',
  'passwordHash',
  'privateKey',
  'contentKey'
];

for (const [schemaName, schema] of Object.entries(document.components.schemas)) {
  const serialized = JSON.stringify(schema);
  for (const property of forbiddenSchemaProperties) {
    assert.ok(!serialized.includes(`"${property}":`), `${schemaName} exposes forbidden property ${property}`);
  }
}

assert.equal(
  document.components.schemas.AdminRedemptionCode.properties.code.readOnly,
  true,
  'complete redemption codes may only be returned by the authenticated admin API'
);
assert.equal(
  document.components.schemas.AdminRedemptionCode.properties.code.nullable,
  true,
  'legacy redemption codes without recoverable material must remain representable'
);
assert.deepEqual(document.components.schemas.DownloadDescriptor.properties.deliveryMode.enum, ['SECURE_PACKAGE', 'MOVING_PHOTO', 'LIVE_PHOTO', 'STATIC_IMAGE']);
assert.ok(document.components.schemas.DownloadDescriptor.properties.image, 'static image delivery metadata is required');
assert.ok(document.components.schemas.DownloadDescriptor.properties.photo, 'Live Photo image delivery metadata is required');
assert.ok(document.components.schemas.DownloadDescriptor.properties.sourceVideo, 'Live Photo source video metadata is required');
assert.ok(document.paths['/admin/resource-versions/{resourceVersionId}/live-photo/build'].post, 'Live Photo build operation is required');
assert.match(
  document.paths['/device/wallpapers/{wallpaperId}/download-tickets'].post.description,
  /UNIVERSAL \/ STATIC_IMAGE.*所有平台/s,
  'download tickets must document universal static image availability'
);
assert.ok(!document.components.schemas.PublicWallpaperSummary.properties.kind, 'wallpaper kind must not remain exclusive');
assert.ok(document.components.schemas.PublicWallpaperSummary.properties.availableCapabilities);
for (const path of ['/public/categories', '/public/wallpapers', '/public/wallpapers/{wallpaperId}']) {
  assert.ok(!document.paths[path].get.responses['428'], `${path} must not require a device capability profile`);
}
assert.match(
  document.components.schemas.PublicWallpaperSummary.properties.availableCapabilities.description,
  /安装包平台/,
  'availableCapabilities must describe the authenticated App platform scope'
);
assert.ok(!document.paths['/device/me/capabilities']);
assert.ok(!document.components.schemas.DeviceCapabilityReportRequest);
assert.match(
  document.paths['/device/registrations'].post.description,
  /HarmonyOS.*HUKS RSA-2048.*PKCS#1 v1\.5/s,
  'device registration must document the fixed HarmonyOS HUKS contract'
);
assert.match(
  document.components.schemas.DeviceRegistrationRequest.properties.appInstallScope.description,
  /com\.qingjing\.bizhi/,
  'HarmonyOS appInstallScope must remain fixed'
);
assert.match(
  document.components.schemas.DeviceRegistrationRequest.properties.evidenceToken.description,
  /QJ-HARMONYOS-REGISTER-V1/,
  'HarmonyOS registration signature domain must remain stable'
);
assert.match(
  document.components.schemas.DeviceSessionChallenge.properties.algorithm.description,
  /RSA_SHA256/,
  'HarmonyOS session challenge algorithm must remain RSA_SHA256'
);
assert.deepEqual(document.components.schemas.PreviewDescriptor.properties.purpose.enum, ['APP_PREVIEW']);
assert.deepEqual(document.components.schemas.PreviewDescriptor.properties.deliveryMode.enum, ['APP_PREVIEW', 'MOVING_PHOTO_PREVIEW', 'LIVE_PHOTO_PREVIEW']);
assert.equal(document.components.schemas.PreviewDescriptor.properties.durationSeconds.minimum, 1);
assert.equal(document.components.schemas.PreviewDescriptor.properties.durationSeconds.maximum, 120);
assert.equal(document.components.schemas.PreviewDescriptor.properties.package.nullable, true);
assert.equal(document.components.schemas.PreviewDescriptor.properties.video.nullable, true);
assert.equal(document.components.schemas.PreviewDescriptor.properties.video.allOf[0].$ref, '#/components/schemas/DeliveryFile');
assert.deepEqual(document.components.schemas.PreviewPackageMetadata.properties.formatVersion.enum, [3]);
assert.match(
  document.paths['/device/wallpapers/{wallpaperId}/preview-tickets'].post.description,
  /live_photo_package/,
  'preview contract must use the generated iOS Live Photo MOV'
);
assert.deepEqual(document.components.schemas.SecurePackageMetadata.properties.formatVersion.enum, [2]);
assert.equal(document.components.schemas.AdminParallaxSourcePackage.properties.configFormatVersion.minimum, 2);
assert.ok(!document.components.schemas.AdminParallaxSourcePackage.properties.configFormatVersion.enum);
for (const removed of ['depth', 'scale', 'opacity', 'blendMode']) {
  assert.ok(!document.components.schemas.AdminParallaxSourceLayer.properties[removed], `admin source layer still exposes ${removed}`);
}
assert.deepEqual(document.paths['/preview/files'].get.security, [{ previewTicketBearer: [] }]);
assert.deepEqual(document.paths['/preview/moving-photo/video'].get.security, [{ previewTicketBearer: [] }]);
assert.deepEqual(document.paths['/preview/live-photo/video'].get.security, [{ previewTicketBearer: [] }]);
assert.ok(document.paths['/preview/live-photo/video'].get.responses['200'].content['video/quicktime']);

// Generated clients cannot decode an implementation error omitted by the enum.
const knownErrors = new Set(document.components.schemas.ErrorCode.enum);
let checkedErrorLiterals = 0;
async function checkJavaErrors(directory) {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const url = new URL(entry.name + (entry.isDirectory() ? '/' : ''), directory);
    if (entry.isDirectory()) await checkJavaErrors(url);
    else if (entry.name.endsWith('.java')) {
      const source = await readFile(url, 'utf8');
      for (const match of source.matchAll(/new ApiException\(\s*HttpStatus\.\w+\s*,\s*"([A-Z_]+)"/g)) {
        assert.ok(knownErrors.has(match[1]), `Java API exposes an undocumented ErrorCode: ${match[1]}`);
        checkedErrorLiterals++;
      }
    }
  }
}
await checkJavaErrors(new URL('../../../services/api-server/src/main/java/', import.meta.url));
assert.ok(checkedErrorLiterals > 0, 'Java error coverage must inspect implementation sources');
assert.ok(document.paths['/device/redemptions'].post.responses['202'], 'redemption processing must be documented');
assert.ok(document.paths['/device/redemptions'].post.responses['404'], 'missing wallpaper must be a definite error');
assert.ok(document.paths['/device/wallpapers/{wallpaperId}/download-tickets'].post.responses['409'], 'nonce reuse conflict must be documented');

console.log(`Contract coverage checks passed for ${operations.length} operations and ${Object.keys(document.components.schemas).length} schemas.`);
console.log(`ErrorCode coverage passed for ${checkedErrorLiterals} direct Java ApiException literals.`);

for (const [path, method] of [
  ['/app-updates/check', 'get'],
  ['/app-updates/packages/{releaseId}', 'get'],
  ['/admin/app-releases', 'get'],
  ['/admin/app-releases', 'post'],
  ['/admin/app-releases/android', 'post'],
  ['/admin/app-releases/{releaseId}', 'put'],
  ['/admin/app-releases/{releaseId}/publish', 'post'],
  ['/admin/app-releases/{releaseId}/deprecate', 'post']
]) assert.ok(document.paths[path]?.[method], `missing App release operation ${method} ${path}`);
assert.deepEqual(document.paths['/app-updates/check'].get.security, []);
assert.deepEqual(document.paths['/app-updates/packages/{releaseId}'].get.security, []);
assert.deepEqual(document.components.schemas.AppReleasePlatform.enum, ['android', 'ios', 'harmony']);
assert.deepEqual(document.components.schemas.AppReleaseView.properties.status.enum, ['DRAFT', 'PUBLISHED', 'DEPRECATED']);
for (const path of [
  '/device/redemptions', '/device/wallpapers/{wallpaperId}/download-tickets',
  '/device/ios/acquisition/free-claims', '/device/ios/acquisition/credit-orders'
]) {
  assert.equal(document.paths[path].post.responses['426'].$ref, '#/components/responses/AppUpdateRequired');
  const headerNames = document.paths[path].post.parameters.map(parameterName);
  assert.ok(headerNames.includes('AppVersionName') && headerNames.includes('AppVersionCode'));
}
assert.ok(!document.paths['/device/ios/acquisition/purchases'].post.responses['426'], 'paid transaction confirmation must remain available to old versions');
assert.ok(!document.paths['/device/ios/acquisition/credit-restores'].post.responses['426'], 'transaction recovery must remain available to old versions');
console.log('App release policy and forced-update recovery contract checks passed.');

// Preview metadata must bind catalog, ticket, and cached media to one policy revision.
const previewStatus = document.components.schemas.PreviewGenerationStatus;
assert.deepEqual(previewStatus.enum, ['PENDING', 'PROCESSING', 'READY', 'FAILED']);
for (const schemaName of ['PublicWallpaperSummary', 'AdminWallpaperSummary']) {
  const schema = document.components.schemas[schemaName];
  for (const field of ['previewRevision', 'previewGenerationStatus']) {
    assert.ok(schema.required.includes(field), `${schemaName} must require ${field}`);
  }
  assert.equal(schema.properties.previewRevision.type, 'integer');
  assert.equal(schema.properties.previewRevision.format, 'int64');
  assert.equal(schema.properties.previewGenerationStatus.$ref, '#/components/schemas/PreviewGenerationStatus');
}
const adminWallpaper = document.components.schemas.AdminWallpaperSummary;
for (const field of ['previewWatermarkEnabled', 'previewGenerationError']) {
  assert.ok(adminWallpaper.required.includes(field), `admin wallpaper must require ${field}`);
}
assert.equal(adminWallpaper.properties.previewGenerationError.nullable, true);
const watermarkWrite = document.components.schemas.WallpaperWriteRequest;
assert.equal(watermarkWrite.properties.previewWatermarkEnabled.type, 'boolean');
assert.equal(watermarkWrite.properties.previewWatermarkEnabled.default, true);
assert.ok(!watermarkWrite.required.includes('previewWatermarkEnabled'), 'legacy PATCH must retain an omitted watermark setting');
assert.match(watermarkWrite.properties.previewWatermarkEnabled.description, /PATCH.*保留原开关/);
assert.ok(document.components.schemas.PreviewDescriptor.required.includes('previewRevision'));
assert.equal(document.components.schemas.PreviewDescriptor.properties.previewRevision.format, 'int64');

const previewCover = document.paths['/wallpapers/{wallpaperId}/cover'].get;
assert.ok(previewCover.security.some(requirement => Object.keys(requirement).length === 0),
  'ONLINE preview covers must remain readable by image loaders without a device session');
assert.equal(previewCover.parameters.find(parameter => parameter.name === 'revision').required, false);
assert.deepEqual(previewCover.responses['200'].headers['Cache-Control'].schema.enum, ['no-store']);
assert.equal(previewCover.responses['503'].$ref, '#/components/responses/PreviewUnavailable');
assert.ok(previewCover.responses['200'].content['image/png']);
assert.match(document.paths['/public/assets/{assetId}/content'].get.description, /禁止回源原图/);
assert.match(document.paths['/public/assets/{assetId}/content'].get.description, /不同预览策略.*拒绝请求/);
assert.equal(document.paths['/device/wallpapers/{wallpaperId}/preview-tickets'].post.responses['503'].$ref,
  '#/components/responses/PreviewUnavailable');
for (const code of ['PREVIEW_PROCESSING', 'PREVIEW_GENERATION_FAILED', 'PREVIEW_WATERMARK_FAILED']) {
  assert.ok(knownErrors.has(code), `preview failure must document ${code}`);
}

for (const [path, method] of [
  ['/admin/wallpaper-previews/rebuild-plan', 'get'],
  ['/admin/wallpaper-previews/rebuild', 'post'],
  ['/admin/wallpapers/{wallpaperId}/preview-rebuild', 'post']
]) {
  const operation = document.paths[path]?.[method];
  assert.ok(operation, `missing preview rebuild operation ${method} ${path}`);
  assert.ok(operation.security.some(requirement => requirement.adminCookie), 'preview administration must require an admin session');
  if (method === 'post') {
    assert.ok(operation.security.some(requirement => requirement.adminCsrf), 'rebuild mutations must require CSRF');
    assert.ok(!operation.requestBody, 'preview rebuild operations must not require invented request bodies');
  }
}
const rebuildPlan = document.components.schemas.PreviewRebuildPlan;
assert.deepEqual(rebuildPlan.required,
  ['wallpaperCount', 'resourceVersionCount', 'watermarkedWallpaperCount', 'cleanWallpaperCount']);
for (const field of rebuildPlan.required) {
  assert.equal(rebuildPlan.properties[field].type, 'integer');
  assert.equal(rebuildPlan.properties[field].format, 'int64');
}
console.log('Preview watermark policy, cache revision, fail-closed cover and rebuild contract checks passed.');

assert.deepEqual(document.components.schemas.UserChannel.enum,
  ['ANDROID_ONLINE', 'ANDROID_OFFLINE', 'IOS', 'HARMONYOS', 'OTHER'], 'four customer App editions must remain distinguishable');
assert.equal(document.components.schemas.OperationsDaily.properties.activeUsers.nullable, true,
  'historic uncollected DAU must remain nullable');
assert.deepEqual(document.components.schemas.OperationsOverview.properties.timeZone.enum, ['Asia/Shanghai']);
assert.ok(document.paths['/admin/devices/{deviceId}/note'].put.security.some(requirement => requirement.adminCsrf),
  'user note edits must require administrator CSRF');
