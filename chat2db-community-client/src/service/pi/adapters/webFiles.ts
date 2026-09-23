import type { PiSelectedFile } from '../contract';
import type { IChatAttachment } from '../../aiAttachment';
import { PiRequestError } from '../client';

export function selectBrowserFiles(types: string[]): Promise<PiSelectedFile[]> {
  return new Promise((resolve) => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = types.map((type) => `.${type}`).join(',');
    input.multiple = true;
    input.hidden = true;
    const finish = () => {
      const selected = Array.from(input.files ?? []).map((file) => ({ file, fileName: file.name }));
      input.removeEventListener('change', finish);
      input.removeEventListener('cancel', finish);
      input.remove();
      resolve(selected);
    };
    input.addEventListener('change', finish);
    input.addEventListener('cancel', finish);
    document.body.appendChild(input);
    input.click();
  });
}

export async function parseUploadedPiAttachment(file: PiSelectedFile,
  headers: Record<string, string>): Promise<IChatAttachment> {
  if (!file.file) throw new Error('Missing uploaded file');
  const body = new FormData();
  body.append('file', file.file);
  const response = await fetch('/api/v3/ai/chat/attachment/parse/upload', {
    method: 'POST', credentials: 'include', headers, body,
  });
  if (!response.ok) throw new PiRequestError(`http.${response.status}`, response.statusText);
  const result = await response.json();
  if (!result.success) throw new PiRequestError(result.errorCode, result.errorMessage);
  return result.data;
}
