import { useEffect, useRef, useState } from 'react';
import { Input, type InputRef } from 'antd';

interface InlineRenameInputProps {
  className?: string;
  initialValue: string;
  maxLength: number;
  onCancel: () => void;
  onSubmit: (value: string) => Promise<void>;
}

const InlineRenameInput = ({
  className,
  initialValue,
  maxLength,
  onCancel,
  onSubmit,
}: InlineRenameInputProps) => {
  const [draft, setDraft] = useState(initialValue);
  const [saving, setSaving] = useState(false);
  const inputRef = useRef<InputRef>(null);
  const savingRef = useRef(false);
  const cancelRequestedRef = useRef(false);

  useEffect(() => {
    inputRef.current?.focus({ cursor: 'all' });
  }, []);

  const cancel = () => {
    cancelRequestedRef.current = true;
    onCancel();
  };

  const submit = async () => {
    if (cancelRequestedRef.current) {
      cancelRequestedRef.current = false;
      return;
    }
    if (savingRef.current) {
      return;
    }
    const value = draft.trim();
    if (!value || value === initialValue) {
      onCancel();
      return;
    }

    savingRef.current = true;
    setSaving(true);
    try {
      await onSubmit(value);
      onCancel();
    } catch {
      requestAnimationFrame(() => inputRef.current?.focus({ cursor: 'end' }));
    } finally {
      savingRef.current = false;
      setSaving(false);
    }
  };

  return (
    <Input
      ref={inputRef}
      className={className}
      size="small"
      maxLength={maxLength}
      value={draft}
      disabled={saving}
      onClick={(event) => event.stopPropagation()}
      onChange={(event) => setDraft(event.target.value)}
      onPressEnter={(event) => {
        event.preventDefault();
        event.stopPropagation();
        submit();
      }}
      onBlur={submit}
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          event.preventDefault();
          event.stopPropagation();
          cancel();
        }
      }}
    />
  );
};

export default InlineRenameInput;
