import type { StateCreator } from 'zustand/vanilla';
import { WorkspaceStore } from '../../store';
import { CommonState } from './initialState';
import { useGlobalStore } from '@/store/global';
import jcefApi from '@/jcef';
import { randomLargeLong } from '@/utils';
import { WorkspaceTabType } from '@/constants';
import { refreshLocalFileWorkspaceTab } from '../../utils/localFileWorkspaceTab';
import { normalizeLocalFileReadResult, type LocalFileReadResult } from '@/utils/localFileEncoding';

export interface CommonAction {
  setCurrentConnectionDetails: (data: CommonState['currentConnectionDetails']) => void;
  setCurrentWorkspaceExtend: (workspaceExtend: CommonState['currentWorkspaceExtend']) => void;
  setCurrentWorkspaceGlobalExtend: (workspaceGlobalExtend: CommonState['currentWorkspaceGlobalExtend']) => void;
  readFile: (
    filePath: string,
    fileExtension?: string,
    context?: {
      rootToken?: string;
      relativePath?: string;
      previewFile?: boolean;
      charset?: string;
      workspaceTabId?: string | number;
    },
  ) => Promise<LocalFileReadResult | undefined>;
}

export const createCommonAction: StateCreator<WorkspaceStore, [['zustand/devtools', never]], [], CommonAction> = (
  set,
  get,
) => ({
  setCurrentConnectionDetails: (data) => {
    set({ currentConnectionDetails: data });
  },
  setCurrentWorkspaceExtend: (workspaceExtend) => {
    set({ currentWorkspaceExtend: workspaceExtend });
  },
  setCurrentWorkspaceGlobalExtend: (workspaceGlobalExtend) => {
    set({ currentWorkspaceGlobalExtend: workspaceGlobalExtend });
  },
  readFile: async (filePath, fileExtension, context) => {
    let localFile: LocalFileReadResult | undefined;
    let fileContent: {
      ddl?: string;
      fileCharset?: string;
      fileBom?: boolean;
      url?: string;
      mimeType?: string;
    };

    if (context?.previewFile && context.rootToken) {
      fileContent = await jcefApi.readSqlDirectoryPreview({
        rootToken: context.rootToken,
        relativePath: context.relativePath || '',
      });
    } else {
      localFile = normalizeLocalFileReadResult(await jcefApi.readFile(filePath, context?.charset));
      fileContent = {
        ddl: localFile.content,
        fileCharset: localFile.charset,
        fileBom: localFile.bom,
      };
    }

    const workspaceTabList = get().workspaceTabList;
    const nextUniqueData = {
      filePath,
      fileExtension,
      fileRootToken: context?.rootToken,
      fileRelativePath: context?.relativePath,
      ...(context?.previewFile
        ? {
            filePreviewUrl: fileContent.url,
            filePreviewMimeType: fileContent.mimeType,
            ddl: undefined,
          }
        : {
            ddl: fileContent.ddl || '',
            fileCharset: fileContent.fileCharset,
            fileBom: fileContent.fileBom,
            filePreviewUrl: undefined,
            filePreviewMimeType: undefined,
          }),
    };
    const refreshedTab = refreshLocalFileWorkspaceTab(
      workspaceTabList,
      filePath,
      nextUniqueData,
      context?.workspaceTabId,
    );
    if (refreshedTab) {
      useGlobalStore.getState().setMainPageActiveTab({ page: 'workspace' });
      get().setActiveConsoleId(refreshedTab.activeTabId);
      get().setWorkspaceTabList(refreshedTab.workspaceTabList);
    } else if (context?.workspaceTabId === undefined) {
      useGlobalStore.getState().setMainPageActiveTab({ page: 'workspace' });
      setTimeout(() => {
        get().addWorkspaceTab({
          id: randomLargeLong(),
          type: WorkspaceTabType.LocalSQLFile,
          title: filePath,
          uniqueData: nextUniqueData,
        });
      }, 0);
    } else {
      return undefined;
    }
    return localFile;
  },
});
