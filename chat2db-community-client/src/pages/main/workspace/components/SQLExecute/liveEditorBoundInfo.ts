import type { IBoundInfo } from '@/typings';

function getLocalFileMetadata(boundInfo?: IBoundInfo) {
  if (!boundInfo) {
    return {};
  }
  return {
    ddl: boundInfo.ddl,
    filePath: boundInfo.filePath,
    fileExtension: boundInfo.fileExtension,
    fileCharset: boundInfo.fileCharset,
    fileBom: boundInfo.fileBom,
    filePreviewUrl: boundInfo.filePreviewUrl,
    filePreviewMimeType: boundInfo.filePreviewMimeType,
    fileRootToken: boundInfo.fileRootToken,
    fileRelativePath: boundInfo.fileRelativePath,
  };
}

export function mergeLatestLocalFileBoundInfo(
  currentBoundInfo: IBoundInfo,
  update: Partial<IBoundInfo>,
  latestWorkspaceBoundInfo?: IBoundInfo,
): IBoundInfo {
  return {
    ...currentBoundInfo,
    ...getLocalFileMetadata(latestWorkspaceBoundInfo),
    ...update,
  };
}
