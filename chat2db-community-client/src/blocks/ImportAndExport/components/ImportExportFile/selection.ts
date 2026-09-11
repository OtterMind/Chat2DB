interface ImportFileSelection {
  filePath?: string;
  file?: File;
}

export const hasSelectedImportFile = (files: ImportFileSelection[]) =>
  files.some(({ filePath, file }) => !!filePath || !!file);
