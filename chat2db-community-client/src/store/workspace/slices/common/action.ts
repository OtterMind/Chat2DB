import type { StateCreator } from 'zustand/vanilla';
import { WorkspaceStore } from '../../store';
import { CommonState } from './initialState';
import { useGlobalStore } from '@/store/global';
import jcefApi from '@/jcef';
import { randomLargeLong } from '@/utils';
import { WorkspaceTabType } from '@/constants';
import { refreshLocalFileWorkspaceTab } from '../../utils/localFileWorkspaceTab';
import { normalizeLocalFileReadResult } from '@/utils/localFileEncoding';

export interface CommonAction {
  setCurrentConnectionDetails: (data: CommonState['currentConnectionDetails']) => void;
  setCurrentWorkspaceExtend: (workspaceExtend: CommonState['currentWorkspaceExtend']) => void;
  setCurrentWorkspaceGlobalExtend: (workspaceGlobalExtend: CommonState['currentWorkspaceGlobalExtend']) => void;
  readFile: (
    filePath: string,
    fileExtension?: string,
    context?: { rootToken?: string; relativePath?: string; previewFile?: boolean },
  ) => void;
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
  readFile: (filePath, fileExtension, context) => {
    const contentPromise =
      context?.previewFile && context.rootToken
        ? jcefApi.readSqlDirectoryPreview({
            rootToken: context.rootToken,
            relativePath: context.relativePath || '',
          })
        : jcefApi.readFile(filePath).then((result) => {
            const file = normalizeLocalFileReadResult(result);
            return {
              ddl: file.content,
              fileCharset: file.charset,
              fileBom: file.bom,
            };
          });

    contentPromise.then((fileContent) => {
      useGlobalStore.getState().setMainPageActiveTab({ page: 'workspace' });
      const workspaceTabList = get().workspaceTabList;
      const nextUniqueData = {
        filePath,
        fileExtension,
        fileRootToken: context?.rootToken,
        fileRelativePath: context?.relativePath,
        ...(context?.previewFile
          ? {
              filePreviewUrl: 'url' in fileContent ? fileContent.url : undefined,
              filePreviewMimeType: 'mimeType' in fileContent ? fileContent.mimeType : undefined,
              ddl: undefined,
            }
          : {
              ddl: 'ddl' in fileContent ? fileContent.ddl : '',
              fileCharset: 'fileCharset' in fileContent ? fileContent.fileCharset : undefined,
              fileBom: 'fileBom' in fileContent ? fileContent.fileBom : undefined,
              filePreviewUrl: undefined,
              filePreviewMimeType: undefined,
            }),
      };
      const refreshedTab = refreshLocalFileWorkspaceTab(workspaceTabList, filePath, nextUniqueData);
      if (refreshedTab) {
        get().setActiveConsoleId(refreshedTab.activeTabId);
        get().setWorkspaceTabList(refreshedTab.workspaceTabList);
      } else {
        setTimeout(() => {
          get().addWorkspaceTab({
            id: randomLargeLong(),
            type: WorkspaceTabType.LocalSQLFile,
            title: filePath,
            uniqueData: nextUniqueData,
          });
        }, 0);
      }
    });
  },
});
