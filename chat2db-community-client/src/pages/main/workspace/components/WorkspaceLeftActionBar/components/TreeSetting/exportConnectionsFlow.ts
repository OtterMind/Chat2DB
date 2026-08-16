export type ExportConnectionsParams = { datasourceIds: number[] | null };
export type ExportConnectionsOutcome = 'saved' | 'cancelled' | 'failed';

interface ExportFile {
  fileName: string;
  fileContent: string;
  fileType: string;
}

interface SavedFile {
  path: string;
  size: number;
}

interface ExportConnectionsDependencies {
  exportDataSource: (params: ExportConnectionsParams) => Promise<{ message: string }>;
  saveFile: (file: ExportFile) => Promise<SavedFile | null | undefined> | SavedFile | null | undefined;
  onExporting: () => void;
  onSaved: () => void;
  onCancelled: () => void;
  onFailed: (error: unknown) => void;
}

//#region 导出连接流程

export async function runExportConnections(
  params: ExportConnectionsParams,
  dependencies: ExportConnectionsDependencies,
): Promise<ExportConnectionsOutcome> {
  dependencies.onExporting();
  try {
    const response = await dependencies.exportDataSource(params);
    const savedFile = await dependencies.saveFile({
      fileName: 'export_chat2db_connections',
      fileContent: response.message,
      fileType: 'json',
    });
    if (!savedFile) {
      dependencies.onCancelled();
      return 'cancelled';
    }

    dependencies.onSaved();
    return 'saved';
  } catch (error) {
    dependencies.onFailed(error);
    return 'failed';
  }
}

//#endregion
