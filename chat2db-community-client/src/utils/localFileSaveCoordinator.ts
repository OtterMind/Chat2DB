export interface LocalFileSaveRequest {
  filePath: string;
  fileContent: string;
  charset?: string;
  bom?: boolean;
}

export interface LocalFileSaveResult {
  filePath: string;
  fileContent: string;
}

type LocalFileSaveMutation = (request: LocalFileSaveRequest) => Promise<unknown>;

interface SaveWaiter {
  resolve: (result: LocalFileSaveResult) => void;
  reject: (error: unknown) => void;
}

interface PendingSave {
  request: LocalFileSaveRequest;
  mutation: LocalFileSaveMutation;
  waiters: SaveWaiter[];
}

interface FileSaveState {
  pending?: PendingSave;
  draining: boolean;
  idle: Promise<void>;
  resolveIdle: () => void;
}

export function getLocalFileSaveKey(filePath: string, isWindows = false) {
  const path = isWindows ? filePath.replace(/\\/g, '/') : filePath;
  const drive = isWindows ? /^([a-zA-Z]:)(\/?)(.*)$/.exec(path) : null;
  const unc = isWindows && path.startsWith('//');
  const absolute = unc || !!drive?.[2] || (!drive && path.startsWith('/'));
  const prefix = unc ? '//' : drive?.[1] || (absolute ? '/' : '');
  const remainder = unc ? path.replace(/^\/+/, '') : drive ? drive[3] : path.replace(/^\/+/, '');
  const minimumDepth = unc ? 2 : 0;
  const segments: string[] = [];

  remainder.split('/').forEach((segment) => {
    if (!segment || segment === '.') {
      return;
    }
    if (segment === '..') {
      if (segments.length > minimumDepth && segments[segments.length - 1] !== '..') {
        segments.pop();
      } else if (!absolute) {
        segments.push(segment);
      }
      return;
    }
    segments.push(segment);
  });

  const separator = drive && absolute ? '/' : '';
  const normalizedPath = `${prefix}${separator}${segments.join('/')}`;
  return isWindows ? normalizedPath.toLowerCase() : normalizedPath;
}

export class LocalFileSaveCoordinator {
  private readonly states = new Map<string, FileSaveState>();

  constructor(private readonly isWindows = false) {}

  save(request: LocalFileSaveRequest, mutation: LocalFileSaveMutation) {
    const key = getLocalFileSaveKey(request.filePath, this.isWindows);
    let state = this.states.get(key);
    if (!state) {
      let resolveIdle!: () => void;
      const idle = new Promise<void>((resolve) => {
        resolveIdle = resolve;
      });
      state = { draining: false, idle, resolveIdle };
      this.states.set(key, state);
    }

    return new Promise<LocalFileSaveResult>((resolve, reject) => {
      if (state!.pending) {
        state!.pending.request = request;
        state!.pending.mutation = mutation;
        state!.pending.waiters.push({ resolve, reject });
      } else {
        state!.pending = {
          request,
          mutation,
          waiters: [{ resolve, reject }],
        };
      }
      if (!state!.draining) {
        state!.draining = true;
        void this.drain(key, state!);
      }
    });
  }

  waitForIdle(filePath: string) {
    return this.states.get(getLocalFileSaveKey(filePath, this.isWindows))?.idle ?? Promise.resolve();
  }

  private async drain(key: string, state: FileSaveState) {
    while (state.pending) {
      const pending = state.pending;
      state.pending = undefined;
      try {
        await pending.mutation(pending.request);
        if (state.pending) {
          state.pending.waiters.push(...pending.waiters);
          continue;
        }
        const result = {
          filePath: pending.request.filePath,
          fileContent: pending.request.fileContent,
        };
        pending.waiters.forEach(({ resolve }) => resolve(result));
      } catch (error) {
        if (state.pending) {
          state.pending.waiters.push(...pending.waiters);
          continue;
        }
        pending.waiters.forEach(({ reject }) => reject(error));
      }
    }

    state.draining = false;
    state.resolveIdle();
    if (!state.pending) {
      this.states.delete(key);
    }
  }
}

const isWindowsRuntime =
  typeof navigator !== 'undefined' && /windows|win32|wow32|win64|wow64/i.test(navigator.userAgent);

export const localFileSaveCoordinator = new LocalFileSaveCoordinator(isWindowsRuntime);
