const PREVIEW_RESOURCE_ORIGIN = 'chat2db-resource://preview';

function encodeBase64Url(value: string) {
  const bytes = new TextEncoder().encode(value);
  let binary = '';
  const chunkSize = 0x8000;
  for (let offset = 0; offset < bytes.length; offset += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + chunkSize));
  }
  return btoa(binary)
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
}

export function getSqlDirectoryPreviewUrl(rootToken: string, relativePath: string) {
  return `${PREVIEW_RESOURCE_ORIGIN}/${encodeURIComponent(rootToken)}/${encodeBase64Url(relativePath)}`;
}
