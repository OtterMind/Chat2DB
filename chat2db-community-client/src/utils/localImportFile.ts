export interface LocalImportFileSelection {
  fileName?: string;
  filePath?: string;
  file?: File;
}

interface BrowserUploadFile {
  name?: string;
  path?: string;
  originFileObj?: File;
}

export function toWebLocalFileSelection(uploadFile: BrowserUploadFile): LocalImportFileSelection {
  const file = uploadFile.originFileObj || (uploadFile as File);
  return {
    file,
    filePath: (file as File & { path?: string }).path,
    fileName: uploadFile.name || file.name,
  };
}

export function resolveLocalImportSource(
  selection: LocalImportFileSelection | undefined,
  fallbackSourceFile = '',
) {
  return {
    sourceFile: selection?.filePath || fallbackSourceFile,
    displayFileName: selection?.fileName,
    file: selection?.file,
  };
}
