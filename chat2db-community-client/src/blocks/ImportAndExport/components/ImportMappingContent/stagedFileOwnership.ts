export type ReleaseStagedFile = (fileId: string) => Promise<unknown>;

export interface StagedFileOwnership {
  own: (fileId: string) => void;
  owns: (fileId: string) => boolean;
  release: (fileId: string) => Promise<void>;
  releaseAll: () => Promise<void>;
  transfer: (fileIds: Iterable<string>) => void;
}

export const createStagedFileOwnership = (releaseStagedFile: ReleaseStagedFile): StagedFileOwnership => {
  const ownedFileIds = new Set<string>();

  const release = async (fileId: string) => {
    if (!ownedFileIds.delete(fileId)) return;
    try {
      await releaseStagedFile(fileId);
    } catch {
      // The server also expires abandoned staging files; release is best-effort during UI teardown.
    }
  };

  return {
    own: (fileId) => ownedFileIds.add(fileId),
    owns: (fileId) => ownedFileIds.has(fileId),
    release,
    releaseAll: async () => {
      const fileIds = Array.from(ownedFileIds);
      await Promise.all(fileIds.map(release));
    },
    transfer: (fileIds) => {
      for (const fileId of fileIds) ownedFileIds.delete(fileId);
    },
  };
};
