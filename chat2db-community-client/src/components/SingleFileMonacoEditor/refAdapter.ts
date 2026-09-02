import type { IExportRefFunction } from '@/components/MonacoEditor';

export interface ISingleFileMonacoEditorRefFunction {
  getAllContent?: () => string;
  setValue?: IExportRefFunction['setValue'];
  onSearch?: () => void;
}

type SingleFileMonacoEditor = Pick<IExportRefFunction, 'getAllContent' | 'setValue'>;

export const createSingleFileMonacoEditorRef = (
  getEditor: () => SingleFileMonacoEditor | null | undefined,
  onSearch: () => void,
): ISingleFileMonacoEditorRefFunction => ({
  getAllContent: () => getEditor()?.getAllContent() || '',
  setValue: (...args) => getEditor()?.setValue(...args),
  onSearch,
});
