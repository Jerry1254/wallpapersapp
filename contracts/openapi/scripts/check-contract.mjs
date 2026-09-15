import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import { parse } from 'yaml';

const document = parse(await readFile(new URL('../openapi.yaml', import.meta.url), 'utf8'));

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
  '/admin/devices': ['get']
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
  document.components.schemas.RedemptionResultCode.enum,
  ['GRANTED', 'ALREADY_OWNED', 'CODE_NOT_FOUND', 'CODE_EXHAUSTED', 'WALLPAPER_UNAVAILABLE', 'FAILED'],
  'redemption result enum drifted from DM-001'
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

assert.ok(
  !document.components.schemas.AdminRedemptionCode.properties.code,
  'admin code history must never expose a complete redemption code'
);
assert.ok(
  document.components.schemas.DownloadDescriptor.properties.deliveryMode.enum.includes('H5_PLACEHOLDER'),
  'download contract must retain the H5 placeholder mode'
);
assert.ok(
  document.components.schemas.DownloadDescriptor.properties.deliveryMode.enum.includes('SECURE_PACKAGE'),
  'download contract must retain the future App secure package mode'
);
assert.deepEqual(document.components.schemas.PreviewDescriptor.properties.purpose.enum, ['APP_PREVIEW']);
assert.deepEqual(document.components.schemas.PreviewDescriptor.properties.durationSeconds.enum, [120]);
assert.deepEqual(document.components.schemas.PreviewPackageMetadata.properties.formatVersion.enum, [3]);
assert.deepEqual(document.components.schemas.SecurePackageMetadata.properties.formatVersion.enum, [2]);
assert.deepEqual(document.paths['/preview/files'].get.security, [{ previewTicketBearer: [] }]);

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
