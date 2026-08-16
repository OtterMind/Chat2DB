export type FileManagerLabelTarget = 'workspace' | 'shortcut';

type FileManagerI18nName = 'Finder' | 'FileExplorer' | 'FileManager';

const FILE_MANAGER_LABEL_PREFIX: Record<FileManagerLabelTarget, string> = {
  workspace: 'workspace.localSqlFileTree.revealIn',
  shortcut: 'setting.shortcut.localSqlFileTreeRevealIn',
};

export function resolveFileManagerI18nName(userAgent: string): FileManagerI18nName {
  if (/macintosh|mac os x|iphone|ipad|ipod/i.test(userAgent)) {
    return 'Finder';
  }
  if (/windows|win32|win64|wow32|wow64/i.test(userAgent)) {
    return 'FileExplorer';
  }
  return 'FileManager';
}

export function getFileManagerLabelKey(
  target: FileManagerLabelTarget,
  userAgent = navigator.userAgent,
): string {
  return `${FILE_MANAGER_LABEL_PREFIX[target]}${resolveFileManagerI18nName(userAgent)}`;
}
