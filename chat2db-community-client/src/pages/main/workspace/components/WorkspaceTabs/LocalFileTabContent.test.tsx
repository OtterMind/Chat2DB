import assert from 'node:assert/strict';
import Module, { createRequire } from 'node:module';
import { JSDOM } from 'jsdom';
import { createElement, type ButtonHTMLAttributes, type ReactNode } from 'react';
import { createRoot } from 'react-dom/client';
import { act } from 'react-dom/test-utils';
import { WorkspaceTabType } from '@/constants/workspace';
import type { IWorkspaceTab } from '@/typings';

const dom = new JSDOM('<div id="root"></div>');
const loadModule = createRequire(__filename);
Object.assign(globalThis, { window: dom.window, document: dom.window.document, IS_REACT_ACT_ENVIRONMENT: true });
const originalModules = new Map<string, NodeModule | undefined>();

function mockModule(name: string, exports: unknown) {
  const filename = loadModule.resolve(name);
  originalModules.set(filename, loadModule.cache[filename]);
  const mock = new Module(filename);
  mock.filename = filename;
  mock.loaded = true;
  mock.exports = exports;
  loadModule.cache[filename] = mock;
}

const reads: Array<{
  path: string;
  context: { activate: boolean; workspaceTabId: string | number };
  resolve: () => void;
  reject: () => void;
}> = [];
mockModule('@/store/workspace', {
  useWorkspaceStore: {
    getState: () => ({
      readFile: (path: string, _extension: string, context: (typeof reads)[number]['context']) =>
        new Promise<void>((resolve, reject) => {
          reads.push({ path, context, resolve, reject: () => reject(new Error('Fixture read failure')) });
        }),
    }),
  },
});
mockModule('@/i18n', { __esModule: true, default: (key: string) => key });
mockModule('antd', {
  Button: ({ children, ...props }: ButtonHTMLAttributes<HTMLButtonElement>) => createElement('button', props, children),
  Result: ({ extra }: { extra: ReactNode }) => createElement('div', { role: 'alert' }, extra),
  Spin: () => createElement('span', { role: 'status' }),
});
const LocalFileTabContent = (loadModule('./LocalFileTabContent') as typeof import('./LocalFileTabContent')).default;
const container = document.getElementById('root')!;
const root = createRoot(container);
const file: IWorkspaceTab = {
  id: 'restored-file',
  type: WorkspaceTabType.LocalSQLFile,
  title: 'restored.sql',
  uniqueData: { filePath: '/test/restored.sql', fileExtension: 'sql' },
};

function render(tab: IWorkspaceTab) {
  act(() => root.render(
    <LocalFileTabContent tab={tab}>
      <textarea defaultValue={tab.uniqueData?.ddl} />
    </LocalFileTabContent>,
  ));
}

async function run() {
  try {
    render(file);
    assert.equal(reads.length, 1);
    assert.equal(container.querySelector('textarea'), null, 'the editor must wait for disk content');
    assert.equal(reads[0].context.workspaceTabId, file.id);
    assert.equal(reads[0].context.activate, false);
    render({ ...file, pinned: true, title: 'renamed tab', uniqueData: { ...file.uniqueData } });
    assert.equal(reads.length, 1, 'pinning or renaming a loading tab must not start another read');

    await act(async () => reads[0].reject());
    assert.ok(container.querySelector('[role="alert"]'), 'read failures expose a retry action');
    act(() => container.querySelector('button')!.click());
    assert.equal(reads.length, 2, 'retry starts one new read');
    assert.equal(container.querySelector('[role="alert"]'), null);
    await act(async () => reads[1].resolve());
    render({ ...file, uniqueData: { ...file.uniqueData, ddl: 'disk content' } });
    const editor = container.querySelector('textarea')!;
    assert.equal(editor.value, 'disk content');
    editor.value = 'unsaved changes';
    render({ ...file, pinned: true, uniqueData: { ...file.uniqueData, ddl: 'disk content' } });
    assert.equal(container.querySelector('textarea'), editor);
    assert.equal(editor.value, 'unsaved changes', 'metadata changes preserve the loaded editor');
    assert.equal(reads.length, 2);
    console.log('Local file tab loading lifecycle tests passed');
  } finally {
    act(() => root.unmount());
    for (const [filename, original] of originalModules) {
      if (original) loadModule.cache[filename] = original;
      else delete loadModule.cache[filename];
    }
    dom.window.close();
  }
}

void run();
