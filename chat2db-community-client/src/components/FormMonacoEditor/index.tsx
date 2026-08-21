import { memo, useEffect, useMemo, useRef } from 'react';
import { v4 as uuidv4 } from 'uuid';
import MonacoEditor, { type IEditorIns } from '@/components/MonacoEditor';

interface FormMonacoEditorProps {
  value?: string;
  onChange?: (value: string) => void;
  className?: string;
  language?: string;
  height?: number | string;
  lineNumbers?: 'on' | 'off';
}

const FormMonacoEditor = memo((props: FormMonacoEditorProps) => {
  const { value = '', onChange, className, language = 'sql', height = 150, lineNumbers = 'on' } = props;
  const editorId = useMemo(() => uuidv4(), []);
  const editorRef = useRef<IEditorIns>();
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;

  useEffect(() => {
    if (editorRef.current && editorRef.current.getValue() !== value) {
      editorRef.current.setValue(value);
    }
  }, [value]);

  return (
    <div className={className} style={{ height }}>
      <MonacoEditor
        id={editorId}
        language={language}
        defaultValue={value}
        options={{
          minimap: { enabled: false },
          scrollBeyondLastLine: false,
          wordWrap: 'on',
          lineNumbers,
          folding: false,
          fontSize: 13,
          occurrencesHighlight: 'off',
        }}
        didMount={(editor) => {
          editorRef.current = editor;
          editor.onDidChangeModelContent(() => onChangeRef.current?.(editor.getValue()));
        }}
      />
    </div>
  );
});

export default FormMonacoEditor;
