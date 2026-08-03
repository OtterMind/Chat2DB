export interface LocalFileSelection {
  filePath?: string;
  file?: File;
}

export interface BrowserDownloadEnvironment {
  fetch: typeof fetch;
  document: Document;
  url: typeof URL;
}

export interface BrowserDirectDownloadEnvironment {
  document: Document;
  location: Pick<Location, 'href' | 'origin'>;
  url: typeof URL;
}

export interface HostArtifactOptions {
  desktop: boolean;
  revealInExplorer: (path: string) => Promise<unknown>;
  download?: (url: string) => Promise<void>;
}

export type HostImportContent = string | string[] | File | undefined;

export function createMultipartFormData(params: Record<string, unknown>): FormData {
  const formData = new FormData();
  Object.entries(params).forEach(([key, value]) => {
    if (value === null || value === undefined) {
      return;
    }
    if (typeof Blob !== 'undefined' && value instanceof Blob) {
      formData.append(key, value);
      return;
    }
    formData.append(key, String(value));
  });
  return formData;
}

export function connectionImportContent(selections: LocalFileSelection[]): HostImportContent {
  const selection = selections[0];
  if (!selection) {
    return undefined;
  }
  return selection.filePath ? [selection.filePath] : selection.file;
}

export function hasHostImportContent(content: HostImportContent): boolean {
  if (Array.isArray(content)) {
    return content.length > 0 && content.every((path) => path.trim().length > 0);
  }
  if (typeof content === 'string') {
    return content.trim().length > 0;
  }
  return content !== undefined;
}

export function selectedLocalFile(selection?: LocalFileSelection): string | File | undefined {
  return selection?.filePath || selection?.file;
}

export function withHostImportFile<T extends Record<string, unknown>>(
  params: T,
  file: string | File | undefined,
  desktop: boolean,
): T & { fileName?: string | File; file?: string | File } {
  return desktop ? { ...params, fileName: file } : { ...params, file };
}

export async function selectHostExportPath(
  desktop: boolean,
  selectDirectory: () => Promise<string | undefined>,
): Promise<string | undefined> {
  if (!desktop) {
    return '';
  }
  return selectDirectory();
}

export function attachmentFileName(contentDisposition: string | null, fallback = 'chat2db-export'): string {
  const encoded = contentDisposition?.match(/filename\*\s*=\s*UTF-8''([^;]+)/i)?.[1];
  const ordinary = contentDisposition?.match(/filename\s*=\s*(?:"([^"]+)"|([^;]+))/i);
  const candidate = encoded ? decodeAttachmentName(encoded) : ordinary ? (ordinary[1] || ordinary[2]).trim() : fallback;
  const safeName = candidate
    .split(/[\\/]/)
    .pop()
    ?.trim();
  return safeName || fallback;
}

export async function downloadHttpAttachment(
  url: string,
  init: RequestInit = {},
  environment?: BrowserDownloadEnvironment,
): Promise<void> {
  const browser = environment || {
    fetch: globalThis.fetch,
    document: globalThis.document,
    url: globalThis.URL,
  };
  const response = await browser.fetch(url, {
    credentials: 'include',
    ...init,
  });
  const contentType = response.headers.get('content-type') || '';
  if (!response.ok || contentType.toLowerCase().includes('json')) {
    const payload = await response
      .clone()
      .json()
      .catch(() => undefined);
    if (!response.ok || payload?.success === false) {
      throw new Error(payload?.errorMessage || `${response.status}: ${response.statusText}`);
    }
    throw new Error('The server did not return a downloadable file');
  }

  const blob = await response.blob();
  const blobUrl = browser.url.createObjectURL(blob);
  const anchor = browser.document.createElement('a');
  anchor.style.display = 'none';
  anchor.href = blobUrl;
  anchor.download = attachmentFileName(response.headers.get('content-disposition'));
  browser.document.body.appendChild(anchor);
  try {
    anchor.click();
  } finally {
    anchor.remove();
    browser.url.revokeObjectURL(blobUrl);
  }
}

export async function downloadHttpGetAttachment(
  url: string,
  environment?: BrowserDirectDownloadEnvironment,
): Promise<void> {
  const browser = environment || {
    document: globalThis.document,
    location: globalThis.location,
    url: globalThis.URL,
  };
  const target = new browser.url(url, browser.location.href);
  if (!['http:', 'https:'].includes(target.protocol) || target.origin !== browser.location.origin) {
    throw new Error('Download URL must use same-origin HTTP');
  }

  const anchor = browser.document.createElement('a');
  anchor.style.display = 'none';
  anchor.href = target.href;
  anchor.download = '';
  browser.document.body.appendChild(anchor);
  try {
    anchor.click();
  } finally {
    anchor.remove();
  }
}

export async function openHostArtifact(location: string | undefined, options: HostArtifactOptions): Promise<void> {
  if (!location) {
    return;
  }
  if (options.desktop) {
    await options.revealInExplorer(location);
    return;
  }
  await (options.download || downloadHttpGetAttachment)(location);
}

function decodeAttachmentName(value: string): string {
  try {
    return decodeURIComponent(value.trim().replace(/^"|"$/g, ''));
  } catch {
    return value.trim().replace(/^"|"$/g, '');
  }
}
