import type { FileUrl } from '@/components/UploadLocalFile';

type UploadBrowserFile = (params: { file: File }) => Promise<string>;
type StageDesktopFile = (params: { sourceFile: string; originalFileName: string }) => Promise<string>;

export const stageSelectedImportFile = (
  selection: FileUrl,
  uploadBrowserFile: UploadBrowserFile,
  stageDesktopFile: StageDesktopFile,
): Promise<string> => {
  if (selection.file) {
    return uploadBrowserFile({ file: selection.file });
  }
  if (selection.filePath && selection.fileName) {
    return stageDesktopFile({ sourceFile: selection.filePath, originalFileName: selection.fileName });
  }
  return Promise.reject(new Error('Import file selection is incomplete'));
};
