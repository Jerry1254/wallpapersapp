import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { parse } from 'yaml';

const root = new URL('../../../', import.meta.url);
const baseline = JSON.parse(await readFile(new URL('../baseline-v1.json', import.meta.url), 'utf8'));
for (const { path, sha256 } of baseline.files) {
  assert.ok(!path.startsWith('/') && !path.split('/').includes('..'), `invalid baseline path: ${path}`);
  const bytes = await readFile(new URL(path, root));
  assert.equal(createHash('sha256').update(bytes).digest('hex'), sha256, `frozen file changed: ${path}`);
}
const contract = parse(await readFile(new URL('../openapi.yaml', import.meta.url), 'utf8'));
const metadata = JSON.parse(await readFile(new URL('../package.json', import.meta.url), 'utf8'));
assert.equal(contract.info.version, baseline.contractVersion, 'OpenAPI version differs from freeze');
assert.equal(metadata.version, baseline.contractVersion, 'package version differs from freeze');
const operations = Object.values(contract.paths).flatMap(item => Object.keys(item).filter(method =>
  ['get', 'post', 'put', 'patch', 'delete', 'options', 'head', 'trace'].includes(method)));
assert.equal(operations.length, baseline.operationCount, 'operation count differs from freeze');
assert.equal(Object.keys(contract.components.schemas).length, baseline.schemaCount, 'schema count differs from freeze');
console.log(`App baseline ${baseline.contractVersion} passed: ${baseline.files.length} SHA-256 files, ${operations.length} operations, ${baseline.schemaCount} schemas.`);
