import type { ImportTaskParams } from '@/service/importExport';

type UploadImportFile = (params: { file: File }) => Promise<string>;

type ClientSubmissionIdFactory = () => string;

export interface ImportSubmissionIdentity {
  clientSubmissionId: string;
  payloadSnapshot: string;
}

export const createClientSubmissionId = (): string => {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  const firstRandomPart = Math.random().toString(36);
  const secondRandomPart = Math.random().toString(36);
  return `${Date.now()}-${firstRandomPart.slice(2)}-${secondRandomPart.slice(2)}`;
};

export const isUnknownSubmissionResponse = (error: unknown) =>
  !error || typeof error !== 'object' || !('errorCode' in error);

export const importSubmissionIdentityAfterFailure = (
  current: ImportSubmissionIdentity | undefined,
  error: unknown,
) =>
  isUnknownSubmissionResponse(error) ? current : undefined;

const canonicalize = (value: unknown): unknown => {
  if (Array.isArray(value)) return value.map(canonicalize);
  if (!value || typeof value !== 'object') return value;
  const record = value as Record<string, unknown>;
  return Object.keys(record)
    .sort()
    .reduce<Record<string, unknown>>((result, key) => {
      if (record[key] !== undefined) result[key] = canonicalize(record[key]);
      return result;
    }, {});
};

export const importSubmissionPayloadSnapshot = (params: ImportTaskParams) =>
  JSON.stringify(canonicalize({ ...params, clientSubmissionId: undefined }));

export const prepareImportSubmission = (
  params: ImportTaskParams,
  current: ImportSubmissionIdentity | undefined,
  create: ClientSubmissionIdFactory = createClientSubmissionId,
) => {
  const payloadSnapshot = importSubmissionPayloadSnapshot(params);
  const clientSubmissionId =
    current?.payloadSnapshot === payloadSnapshot
      ? current.clientSubmissionId
      : current
        ? create()
        : params.clientSubmissionId || create();
  return {
    identity: { clientSubmissionId, payloadSnapshot },
    params: { ...params, clientSubmissionId },
  };
};

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

export const getServerStagedImportFileIds = (params: ImportTaskParams) =>
  Array.from(
    new Set(
      [params.fileId, ...(params.tableSources?.map((source) => source.fileId) || [])].filter(
        (fileId): fileId is string => Boolean(fileId),
      ),
    ),
  );

export const hasServerStagedImportFiles = (params: ImportTaskParams) =>
  getServerStagedImportFileIds(params).length > 0;
