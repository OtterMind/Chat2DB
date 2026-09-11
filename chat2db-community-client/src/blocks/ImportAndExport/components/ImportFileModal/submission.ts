import { ImportTaskParams } from '@/service/importExport';

type UploadImportFile = (params: { file: File }) => Promise<string>;

export const prepareWebImportParams = async (
  params: ImportTaskParams,
  file: File,
  uploadImportFile: UploadImportFile,
): Promise<ImportTaskParams> => ({
  ...params,
  sourceFile: undefined,
  fileId: await uploadImportFile({ file }),
  displayFileName: file.name,
});
