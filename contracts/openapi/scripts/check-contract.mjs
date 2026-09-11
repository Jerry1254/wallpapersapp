import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { parse } from 'yaml';

const document = parse(await readFile(new URL('../openapi.yaml', import.meta.url), 'utf8'));

const expectedOperations = {
  '/public/categories': ['get'],
  '/public/wallpapers': ['get'],
  '/public/wallpapers/{wallpaperId}': ['get'],
  '/device/registrations': ['post'],
  '/device/session-challenges': ['post'],
  '/device/sessions': ['post'],
  '/device/me/entitlements': ['get'],
  '/device/redemptions': ['post'],
  '/device/redemptions/{idempotencyKey}': ['get'],
  '/device/wallpapers/{wallpaperId}/download-tickets': ['post'],
  '/admin/sessions': ['get', 'post', 'delete'],
  '/admin/assets': ['post'],
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
  for (const method of ['get', 'post', 'put', 'patch', 'delete']) {
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
  ['/admin/variants/{variantId}', 'delete']
]) {
  const names = document.paths[path][method].parameters.map(parameterName);
  assert.ok(names.includes('IfMatch'), `${method.toUpperCase()} ${path} must require If-Match`);
}

const sensitiveDeviceOperations = [
  document.paths['/device/redemptions'].post,
  document.paths['/device/wallpapers/{wallpaperId}/download-tickets'].post
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

console.log(`Contract coverage checks passed for ${operations.length} operations and ${Object.keys(document.components.schemas).length} schemas.`);
