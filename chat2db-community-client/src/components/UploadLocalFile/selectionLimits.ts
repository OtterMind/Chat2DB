export interface SizeAwareFileSelection {
  fileSize?: number;
  file?: {
    size?: number;
  };
}

export interface FileSelectionLimits {
  multiple?: boolean;
  maxFiles?: number;
  maxTotalSizeBytes?: number;
}

export type FileSelectionLimitViolation = 'maxFiles' | 'maxTotalSizeBytes';

export interface FileSelectionLimitRejection<T> {
  file: T;
  violation: FileSelectionLimitViolation;
}

export interface FileSelectionLimitResult<T> {
  fileList: T[];
  accepted: T[];
  rejected: FileSelectionLimitRejection<T>[];
  violations: FileSelectionLimitViolation[];
}

const BYTES_PER_MEGABYTE = 1024 * 1024;

const getNonNegativeFiniteNumber = (value: unknown): number | undefined => {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : undefined;
};

export const getKnownFileSize = (selection: SizeAwareFileSelection): number | undefined => {
  return getNonNegativeFiniteNumber(selection.fileSize) ?? getNonNegativeFiniteNumber(selection.file?.size);
};

export const exceedsSingleFileSizeLimit = (fileSizeBytes: number, maxSizeMegabytes?: number): boolean => {
  return Boolean(maxSizeMegabytes) && fileSizeBytes > Number(maxSizeMegabytes) * BYTES_PER_MEGABYTE;
};

export const mergeFileSelections = <T extends SizeAwareFileSelection>(
  current: T[],
  incoming: T[],
  { multiple, maxFiles, maxTotalSizeBytes }: FileSelectionLimits,
): FileSelectionLimitResult<T> => {
  const normalizedMaxFiles = getNonNegativeFiniteNumber(maxFiles);
  const normalizedMaxTotalSizeBytes = getNonNegativeFiniteNumber(maxTotalSizeBytes);
  const candidates = multiple ? incoming : incoming.slice(0, 1);
  const fileList = multiple ? [...current] : [];
  const accepted: T[] = [];
  const rejected: FileSelectionLimitRejection<T>[] = [];
  const violations: FileSelectionLimitViolation[] = [];
  let knownTotalSize = fileList.reduce((total, selection) => total + (getKnownFileSize(selection) ?? 0), 0);

  const reject = (file: T, violation: FileSelectionLimitViolation) => {
    rejected.push({ file, violation });
    if (!violations.includes(violation)) {
      violations.push(violation);
    }
  };

  candidates.forEach((file) => {
    if (normalizedMaxFiles !== undefined && fileList.length >= Math.floor(normalizedMaxFiles)) {
      reject(file, 'maxFiles');
      return;
    }

    const fileSize = getKnownFileSize(file);
    if (
      fileSize !== undefined &&
      normalizedMaxTotalSizeBytes !== undefined &&
      knownTotalSize + fileSize > normalizedMaxTotalSizeBytes
    ) {
      reject(file, 'maxTotalSizeBytes');
      return;
    }

    fileList.push(file);
    accepted.push(file);
    knownTotalSize += fileSize ?? 0;
  });

  return {
    fileList: accepted.length || multiple ? fileList : current,
    accepted,
    rejected,
    violations,
  };
};
