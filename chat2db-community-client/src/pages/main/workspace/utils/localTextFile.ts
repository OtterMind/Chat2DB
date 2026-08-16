export const SQL_FILE_EXTENSION_NAME = 'sql';

export const LOCAL_TEXT_FILE_FALLBACK_ICON = 'icon-24gl-fileText';

export const LOCAL_TEXT_FILE_ICON_MAP: Record<string, string> = {
  sql: 'icon-jiediansql',
  txt: 'icon-txt',
  md: 'icon-markdown',
  markdown: 'icon-markdown',
};

export const getLocalTextFileIcon = (fileExtension?: string) => {
  return LOCAL_TEXT_FILE_ICON_MAP[(fileExtension || '').toLowerCase()] || LOCAL_TEXT_FILE_FALLBACK_ICON;
};

const getLocalTextFileName = (filePath: string) => {
  const normalizedPath = (filePath || '').replace(/\\/g, '/');
  const fileName = normalizedPath.slice(normalizedPath.lastIndexOf('/') + 1);
  return fileName || filePath;
};

export const getLocalTextFileTabPresentation = (filePath: string | undefined, fallbackLabel: string) => {
  if (!filePath) {
    return {
      label: fallbackLabel,
      popover: undefined,
    };
  }
  return {
    label: getLocalTextFileName(filePath),
    popover: filePath,
  };
};
