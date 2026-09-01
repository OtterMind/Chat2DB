import type { LocalSQLFileTreeNodeType } from './type';

export function createLocalFileTreePathOperations(isWindows: boolean) {
  const normalizeComparablePath = (value: string) =>
    (isWindows ? value.replace(/\\/g, '/') : value).replace(/\/+$/, '');

  const getComparablePath = (value: string) => {
    const normalizedPath = normalizeComparablePath(value);
    return isWindows ? normalizedPath.toLowerCase() : normalizedPath;
  };

  const isSameOrChildPath = (
    filePath: string,
    targetPath: string,
    targetType: LocalSQLFileTreeNodeType,
  ) => {
    const normalizedFilePath = getComparablePath(filePath);
    const normalizedTargetPath = getComparablePath(targetPath);
    if (targetType === 'file') {
      return normalizedFilePath === normalizedTargetPath;
    }
    return normalizedFilePath === normalizedTargetPath || normalizedFilePath.startsWith(`${normalizedTargetPath}/`);
  };

  const replacePathPrefix = (filePath: string, sourcePath: string, targetPath: string) => {
    const normalizedFilePath = isWindows ? filePath.replace(/\\/g, '/') : filePath;
    const normalizedSourcePath = normalizeComparablePath(sourcePath);
    const suffix =
      getComparablePath(filePath) === getComparablePath(sourcePath)
        ? ''
        : normalizedFilePath.slice(normalizedSourcePath.length);
    const separator = isWindows && targetPath.includes('\\') ? '\\' : '/';
    return `${targetPath}${suffix.replace(/\//g, separator)}`;
  };

  const getParentPath = (filePath: string) => {
    const separatorIndex = isWindows
      ? Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'))
      : filePath.lastIndexOf('/');
    return separatorIndex > 0 ? filePath.slice(0, separatorIndex) : '';
  };

  const getRenamedFilePaths = ({
    filePath,
    fileRelativePath,
    sourcePath,
    sourceRelativePath,
    targetPath,
    targetRelativePath,
  }: {
    filePath: string;
    fileRelativePath?: string;
    sourcePath: string;
    sourceRelativePath: string;
    targetPath: string;
    targetRelativePath: string;
  }) => ({
    filePath: replacePathPrefix(filePath, sourcePath, targetPath),
    fileRelativePath: fileRelativePath
      ? replacePathPrefix(fileRelativePath, sourceRelativePath, targetRelativePath)
      : fileRelativePath,
  });

  return {
    getComparablePath,
    getParentPath,
    getRenamedFilePaths,
    isSameOrChildPath,
    replacePathPrefix,
  };
}
