import { isDesktop } from '@/utils/env';
import { useGlobalStore } from '@/store/global';
import jcefApi from '@/jcef';
import { commandLineRequest } from '../commandLine/commandLine';
import { createPiClient } from './client';
import { createHttpPiTransport } from './adapters/http';
import { createDesktopPiTransport } from './adapters/desktop';
import { createDesktopPiHost, createWebPiHost } from './adapters/host';
import { selectBrowserFiles, parseUploadedPiAttachment } from './adapters/webFiles';
import { promptServerDirectory } from './adapters/directoryPrompt';

const headers = () => ({
  'Accept-Language': useGlobalStore.getState().baseSetting.language,
  'Time-Zone': new Intl.DateTimeFormat().resolvedOptions().timeZone,
});
const client = createPiClient(isDesktop
  ? createDesktopPiTransport(commandLineRequest) : createHttpPiTransport(headers));
const host = isDesktop ? createDesktopPiHost(client, {
  reveal: (path) => jcefApi.revealInExplorer(path),
  selectFiles: async (types) => (await jcefApi.selectFile({ fileTypeList: types, multiple: true })) ?? [],
})
  : createWebPiHost(client, {
    selectDirectory: promptServerDirectory,
    selectFiles: selectBrowserFiles,
    parseAttachment: (file) => parseUploadedPiAttachment(file, headers()),
    download(url) {
      const link = document.createElement('a');
      link.href = url;
      link.download = '';
      link.click();
    },
  });

export default { ...client, host };
export * from './types';
export type { PiCallOptions, PiHostAdapter } from './contract';
