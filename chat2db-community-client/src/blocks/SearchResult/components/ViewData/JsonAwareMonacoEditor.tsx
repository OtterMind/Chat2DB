import { useEffect, useMemo, useRef } from 'react';
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api';
import 'monaco-editor/esm/vs/language/json/monaco.contribution';
import MonacoEditor, { IEditorIns } from '@/components/MonacoEditor';

interface IProps {
  id: string;
  value: string;
  readOnly: boolean;
  onChange: (value: string) => void;
  onJsonChange: (isJson: boolean) => void;
}

interface JsonValidationWorker {
  doValidation: (uri: string) => Promise<unknown[]>;
  parseJSONDocument: (uri: string) => Promise<monaco.languages.json.JSONDocument | null>;
}

const JsonAwareMonacoEditor = ({ id, value, readOnly, onChange, onJsonChange }: IProps) => {
  const editorRef = useRef<IEditorIns | null>(null);
  const changeDisposerRef = useRef<{ dispose: () => void } | null>(null);
  const validationModelRef = useRef<monaco.editor.ITextModel | null>(null);
  const validationTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const validationVersionRef = useRef(0);
  const syncingValueRef = useRef(false);
  const onChangeRef = useRef(onChange);
  const onJsonChangeRef = useRef(onJsonChange);
  const validationUri = useMemo(() => monaco.Uri.parse(`inmemory://chat2db/value-inspector/${id}.json`), [id]);

  onChangeRef.current = onChange;
  onJsonChangeRef.current = onJsonChange;

  const validateJson = async (content: string, version: number) => {
    let model = validationModelRef.current;
    if (!model) {
      model = monaco.editor.getModel(validationUri) || monaco.editor.createModel(content, 'json', validationUri);
      validationModelRef.current = model;
    } else if (model.getValue() !== content) {
      model.setValue(content);
    }

    let isJson = false;
    try {
      const getWorker = await monaco.languages.json.getWorker();
      const worker = (await getWorker(validationUri)) as unknown as JsonValidationWorker;
      const [document, diagnostics] = await Promise.all([
        worker.parseJSONDocument(validationUri.toString()),
        worker.doValidation(validationUri.toString()),
      ]);
      isJson =
        diagnostics.length === 0 &&
        (document?.root?.type === 'object' || document?.root?.type === 'array');
    } catch {
      isJson = false;
    }

    if (version !== validationVersionRef.current) {
      return;
    }
    onJsonChangeRef.current(isJson);
    const editorModel = editorRef.current?.getModel();
    if (editorModel) {
      monaco.editor.setModelLanguage(editorModel, isJson ? 'json' : 'plaintext');
    }
  };

  const scheduleValidation = (content: string) => {
    const version = ++validationVersionRef.current;
    if (validationTimerRef.current) {
      clearTimeout(validationTimerRef.current);
    }
    validationTimerRef.current = setTimeout(() => validateJson(content, version), 120);
  };

  const handleEditorDidMount = (editor: IEditorIns) => {
    changeDisposerRef.current?.dispose();
    editorRef.current = editor;
    editor.updateOptions({ readOnly });
    if (editor.getValue() !== value) {
      syncingValueRef.current = true;
      editor.setValue(value);
      syncingValueRef.current = false;
    }
    changeDisposerRef.current = editor.onDidChangeModelContent(() => {
      const nextValue = editor.getValue();
      scheduleValidation(nextValue);
      if (!syncingValueRef.current) {
        onChangeRef.current(nextValue);
      }
    });
    scheduleValidation(value);
  };

  useEffect(() => {
    const editor = editorRef.current;
    if (!editor || editor.getValue() === value) {
      scheduleValidation(value);
      return;
    }
    syncingValueRef.current = true;
    editor.setValue(value);
    syncingValueRef.current = false;
    scheduleValidation(value);
  }, [value]);

  useEffect(() => {
    editorRef.current?.updateOptions({ readOnly });
  }, [readOnly]);

  useEffect(() => {
    return () => {
      validationVersionRef.current += 1;
      changeDisposerRef.current?.dispose();
      validationModelRef.current?.dispose();
      if (validationTimerRef.current) {
        clearTimeout(validationTimerRef.current);
      }
    };
  }, []);

  return (
    <MonacoEditor
      id={id}
      language="plaintext"
      didMount={handleEditorDidMount}
      options={{ lineNumbers: 'off', readOnly }}
    />
  );
};

export default JsonAwareMonacoEditor;
