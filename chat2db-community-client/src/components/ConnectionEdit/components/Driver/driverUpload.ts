export interface IDriverSaveDraft {
  dbType: string;
  driverFiles?: File[];
  jdbcDriver?: string[];
  jdbcDriverClass?: string;
}

export interface IDriverSavePayload {
  dbType: string;
  jdbcDriver: string[];
  jdbcDriverClass?: string;
}

export async function resolveDriverSavePayload(
  draft: IDriverSaveDraft,
  desktop: boolean,
  upload: (file: File) => Promise<string[]>,
): Promise<IDriverSavePayload> {
  const { driverFiles, jdbcDriver = [], ...payload } = draft;
  const uploadedDriverNames = !desktop && driverFiles?.length ? await upload(driverFiles[0]) : jdbcDriver;

  if (!uploadedDriverNames.length) {
    throw new Error('A JDBC driver file is required');
  }

  return {
    ...payload,
    jdbcDriver: uploadedDriverNames,
  };
}

export function canSaveDriverDraft(draft: IDriverSaveDraft) {
  return Boolean(
    draft.jdbcDriverClass?.trim() && (draft.jdbcDriver?.length || draft.driverFiles?.length),
  );
}
