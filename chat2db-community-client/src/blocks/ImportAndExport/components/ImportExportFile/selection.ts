import { ImportExportFileType } from '@/constants/importExport';
import { inferImportFileFormat } from '../../utils/csvOptions';

interface ImportFileSelection {
  fileName?: string;
  filePath?: string;
  file?: File;
}

export const hasSelectedImportFile = (files: ImportFileSelection[]) =>
  files.some(({ filePath, file }) => !!filePath || !!file);

/** Supported filename extensions own the import format on both web and desktop. */
export const resolveSelectedImportFileType = (
  file: ImportFileSelection | undefined,
  selectedType: ImportExportFileType,
): ImportExportFileType => {
  const name = file?.fileName || file?.file?.name || file?.filePath || '';
  return /\.(csv|xlsx|xls|json|sql)$/i.test(name) ? inferImportFileFormat(name) : selectedType;
};
