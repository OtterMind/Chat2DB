export interface RunSqlFormValues {
  encoding?: string;
  errorPolicy?: 'STOP' | 'CONTINUE';
  commitMode?: 'SCRIPT' | 'BATCH' | 'SINGLE_TRANSACTION';
  batchSize?: number;
  fileUrl?: string;
}

export const normalizeRunSqlFormValues = <T extends RunSqlFormValues>(values: T): T => {
  if (values.commitMode !== 'SINGLE_TRANSACTION' || values.errorPolicy === 'STOP') {
    return values;
  }
  return {
    ...values,
    errorPolicy: 'STOP',
  };
};
