export interface WorkspaceObjectTabTitleContext {
  dataSourceName?: string | null;
  databaseName?: string | null;
  schemaName?: string | null;
  objectName: string;
}

export function buildWorkspaceObjectTabTitle({
  dataSourceName,
  databaseName,
  schemaName,
  objectName,
}: WorkspaceObjectTabTitleContext): string {
  const qualifiedName = [databaseName, schemaName, objectName].filter(Boolean).join('.');
  return dataSourceName ? `${qualifiedName}[${dataSourceName}]` : qualifiedName;
}
