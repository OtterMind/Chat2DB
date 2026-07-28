export const normalizeSavedConsoleName = (value?: string) => value?.trim() || '';

export const resolveInitialSavedConsoleName = (params: {
  workspaceTitle?: string;
  databaseName?: string;
  schemaName?: string;
  fallback: string;
}) => {
  const { workspaceTitle, databaseName, schemaName, fallback } = params;
  return [workspaceTitle, databaseName, schemaName, fallback].map(normalizeSavedConsoleName).find(Boolean) || '';
};
