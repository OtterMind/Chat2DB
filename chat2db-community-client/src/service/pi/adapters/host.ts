import type { PiClient } from '../client';
import type { PiCallOptions, PiHostAdapter, PiOutput, PiSelectedFile } from '../contract';

export const outputDownloadUrl = ({ sessionId, artifactId }: PiOutput) =>
  `/api/v3/ai/sessions/${encodeURIComponent(sessionId)}/outputs/${encodeURIComponent(artifactId)}/download`;

export function createWebPiHost(client: PiClient, dependencies: {
  selectDirectory(current: string, options?: PiCallOptions): Promise<string | null>;
  download(url: string): void;
  selectFiles(types: string[]): Promise<PiSelectedFile[]>;
  parseAttachment(file: PiSelectedFile): ReturnType<PiHostAdapter['parseAttachment']>;
}): PiHostAdapter {
  return {
    selectDirectory: dependencies.selectDirectory,
    selectFiles: dependencies.selectFiles,
    parseAttachment: dependencies.parseAttachment,
    async downloadOutput(output, options) {
      await client.outputs.read({ ...output, limit: 1 }, options);
      options?.signal?.throwIfAborted();
      dependencies.download(outputDownloadUrl(output));
    },
  };
}

export function createDesktopPiHost(client: PiClient, dependencies: {
  reveal(path: string): Promise<unknown>;
  selectFiles(types: string[]): Promise<PiSelectedFile[]>;
}): PiHostAdapter {
  return {
    selectFiles: dependencies.selectFiles,
    parseAttachment(file) {
      if (!file.filePath) return Promise.reject(new Error('Missing local file path'));
      return client.attachments.parseLocal({ filePath: file.filePath, fileName: file.fileName });
    },
    selectDirectory: (_current, options) => client.workspace.selectDirectory(undefined, { timeoutMs: 0, ...options }),
    async downloadOutput(output, options) {
      const path = await client.outputs.save(output, { timeoutMs: 0, ...options });
      options?.signal?.throwIfAborted();
      if (path) await dependencies.reveal(path);
    },
  };
}
