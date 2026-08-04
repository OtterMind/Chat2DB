import assert from 'node:assert/strict';
import { getSqlDirectoryPreviewUrl } from './previewResource';

function decodeBase64Url(value: string) {
  const base64 = value
    .replace(/-/g, '+')
    .replace(/_/g, '/')
    .padEnd(Math.ceil(value.length / 4) * 4, '=');
  return Buffer.from(base64, 'base64').toString('utf8');
}

const rootToken = 'root token/with?#reserved';
const relativePath = '预览文件/带 空格/image #1%25.gif';
const previewUrl = getSqlDirectoryPreviewUrl(rootToken, relativePath);
const parsedUrl = new URL(previewUrl);

assert.equal(parsedUrl.protocol, 'chat2db-resource:');
assert.equal(parsedUrl.hostname, 'preview');

const [encodedRootToken, encodedRelativePath] = parsedUrl.pathname.slice(1).split('/');
assert.equal(decodeURIComponent(encodedRootToken), rootToken);
assert.equal(decodeBase64Url(encodedRelativePath), relativePath);
assert.doesNotMatch(encodedRelativePath, /[+/=]/);

console.log('Preview resource URL tests passed');
