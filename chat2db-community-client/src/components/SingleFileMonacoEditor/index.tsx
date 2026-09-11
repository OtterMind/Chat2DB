import { memo, useCallback, useEffect, useMemo, ForwardedRef, forwardRef, useImperativeHandle, useRef } from 'react';
import styles from './index.less';
import classnames from 'classnames';
import MonacoEditor, { IExportRefFunction } from '@/components/MonacoEditor';
import { v4 as uuid } from 'uuid';
import { createSingleFileShortcutController } from './shortcut';
import {
  createSingleFileMonacoEditorRef,
  ISingleFileMonacoEditorRefFunction,
} from './refAdapter';

export type { ISingleFileMonacoEditorRefFunction } from './refAdapter';

interface IProps {
  className?: string;
  handelEnter?: (value: string) => void;
  focusChange?: (isActive: boolean) => void;
  ref: any; // TODO: Move this ref to the appropriate owner.
}

const options = {
  lineNumbers: false,
  renderLineHighlight: 'none',
  scrollBeyondLastLine: false,
  wordWrap: 'off',
  minimap: {
    enabled: false,
  },
  // Hide the scrollbar.
  scrollbar: {
    vertical: 'hidden',
    horizontal: 'hidden',
  },
  overviewRulerBorder: false,
  glyphMargin: false,
  folding: false,
  lineDecorationsWidth: 0, // Line-number width.
  lineNumbersMinChars: 0, // Minimum line-number width.
  lineHeight: 26,
};

const SingleFileMonacoEditor = memo<IProps>(
  forwardRef((props, ref: ForwardedRef<ISingleFileMonacoEditorRefFunction>) => {
    const { className, handelEnter, focusChange } = props;
    const monacoEditorRef = useRef<IExportRefFunction>(null);

    const editorId = useMemo(() => {
      return uuid();
    }, []);

    const handleEnterSearch = useCallback(() => {
      const value = monacoEditorRef.current?.getAllContent().trim() || '';
      handelEnter?.(value);
    }, [handelEnter]);

    const handleEnterSearchRef = useRef(handleEnterSearch);
    handleEnterSearchRef.current = handleEnterSearch;

    const shortcutController = useMemo(
      () => createSingleFileShortcutController(window, () => handleEnterSearchRef.current()),
      [],
    );

    // Listen for keydown and prevent Enter's default behavior.
    const registerShortcutKey = useCallback((_editor, _monaco, isActive) => {
      if (isActive) {
        shortcutController.activate(_editor);
      } else {
        shortcutController.deactivate();
      }
    }, [shortcutController]);

    useEffect(
      () => () => {
        shortcutController.dispose();
      },
      [shortcutController],
    );

    useImperativeHandle(ref, () =>
      createSingleFileMonacoEditorRef(() => monacoEditorRef.current, handleEnterSearch),
    );

    return (
      <div ref={ref as any} className={classnames(styles.singleFileMonacoEditor, className)}>
        <MonacoEditor
          ref={monacoEditorRef}
          id={editorId}
          disableFind
          options={options as any}
          shortcutKey={registerShortcutKey}
          focusChange={focusChange}
        />
      </div>
    );
  }),
);

export default SingleFileMonacoEditor;
