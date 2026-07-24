import React, { useImperativeHandle, forwardRef, useRef, useState, useEffect, useLayoutEffect } from 'react';
import * as VTable from '@visactor/vtable';
import { ReferencePosition } from '@visactor/vtable-editors';
import * as ReactDOM from 'react-dom/client';
import { DatePicker, TimePicker } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import customParseFormat from 'dayjs/plugin/customParseFormat';
import { useStyles } from './style';
import { useStylesStore } from '@/store/styles';
import { copyToClipboard } from '@/utils';
import DivTextarea from '@/components/DivTextarea';

dayjs.extend(customParseFormat);

export interface IProps {
  defaultValue: string;
  textarea?: boolean;
}

// function value filter
const valueFilter = (value: string) => {
  switch (value) {
    case 'CHAT2DB_UPDATE_TABLE_DATA_USER_FILLED_DEFAULT':
      return '';
    case 'CHAT2DB_UPDATE_TABLE_DATA_USER_FILLED_GENERATED':
      return '';
    default:
      return value;
  }
};

type DateEditorMode = 'date' | 'time' | 'datetime' | 'timestamp';

const DATE_EDITOR_FORMAT_MAP: Record<DateEditorMode, string> = {
  date: 'YYYY-MM-DD',
  time: 'HH:mm:ss',
  datetime: 'YYYY-MM-DD HH:mm:ss',
  timestamp: 'YYYY-MM-DD HH:mm:ss',
};

type DateEditorPlacement = 'bottomLeft' | 'topLeft';
type DateEditorReferenceRect = {
  top: number;
  left: number;
  width: number;
  height: number;
};

interface InputEditorCreateElementParams {
  value: any;
  referencePosition?: ReferencePosition | null;
  container?: HTMLElement | null;
}

const DATE_EDITOR_PANEL_HEIGHT_MAP: Record<DateEditorMode, number> = {
  date: 340,
  time: 300,
  datetime: 340,
  timestamp: 340,
};

const PICKER_PANEL_SAFE_GAP = 48;

const getNativeElement = (refValue: any): HTMLElement | null => {
  const element = refValue?.nativeElement || refValue;
  return element instanceof HTMLElement ? element : null;
};

const resolveDateEditorPlacement = ({
  mode,
  inputElement,
  referenceRect,
  editorContainer,
}: {
  mode: DateEditorMode;
  inputElement?: HTMLElement | null;
  referenceRect?: DateEditorReferenceRect | null;
  editorContainer?: HTMLElement | null;
}): DateEditorPlacement => {
  const expectedPanelHeight = DATE_EDITOR_PANEL_HEIGHT_MAP[mode] + PICKER_PANEL_SAFE_GAP;
  let inputBottom: number | null = null;
  let boundaryBottom = window.innerHeight;

  if (editorContainer) {
    boundaryBottom = Math.min(boundaryBottom, editorContainer.getBoundingClientRect().bottom);
  }

  if (inputElement) {
    inputBottom = inputElement.getBoundingClientRect().bottom;
  } else if (referenceRect && editorContainer) {
    inputBottom = editorContainer.getBoundingClientRect().top + referenceRect.top + referenceRect.height;
  }

  if (inputBottom === null) {
    return 'bottomLeft';
  }

  return boundaryBottom - inputBottom < expectedPanelHeight ? 'topLeft' : 'bottomLeft';
};

const parseDateEditorValue = (value: string, format: string) => {
  const normalizedValue = valueFilter(value);
  if (!normalizedValue) {
    return null;
  }
  const parsedValue = dayjs(normalizedValue, format, true);
  return parsedValue.isValid() ? parsedValue : null;
};

/* Editor */
const InputReact = forwardRef((props: IProps, ref) => {
  const { defaultValue } = props;
  const inputRef = useRef<any>(null);
  const [value, setValue] = useState(valueFilter(defaultValue));
  const theme = useStylesStore((s) => s.theme);
  const { styles, cx } = useStyles({ theme });

  useImperativeHandle(ref, () => ({
    focus: () => {
      inputRef.current?.focus({ cursor: 'end' });
    },
    setValue: (val) => {
      setValue(val);
    },
    getValue: () => {
      return value;
    },
  }));

  const handleChange = (_value) => {
    setValue(_value);
  };

  useEffect(() => {
    const handleKeyDown = (e) => {
      if (e.code === 'KeyA' && (e.metaKey || e.ctrlKey)) {
        e.preventDefault();
        e.stopPropagation();
        const inputElement = inputRef.current;
        if (inputElement) {
          inputElement.selectAll();
        }
      }
      if (e.code === 'KeyC' && (e.metaKey || e.ctrlKey)) {
        // VTable's copy event interferes here, so stop propagation and handle copying locally.
        e.stopPropagation();
        e.preventDefault();
        const selection = window.getSelection()?.toString();
        if (selection) {
          copyToClipboard(selection);
        }
      }
    };

    const inputElement = inputRef.current.textarea;
    if (inputElement) {
      inputElement.addEventListener('keydown', handleKeyDown);
    }

    return () => {
      if (inputElement) {
        inputElement.removeEventListener('keydown', handleKeyDown);
      }
    };
  }, []);

  return (
    <DivTextarea ref={inputRef} value={value} onChange={handleChange} className={cx(styles.input, styles.textArea)} />
  );

  // if (textarea) {
  //   return (
  //   );
  // }

  // return <Input spellCheck={false} className={styles.input} ref={inputRef} value={value} onChange={handleChange} />;
});

class InputEditor {
  /** The container element where the VTable instance is located */
  container: HTMLElement | null = null;
  /** Editor element frame content */
  inputContainer: HTMLDivElement | null = null;
  /** React root for the editor content; unmounted in onEnd to avoid leaks. */
  root: ReturnType<typeof ReactDOM.createRoot> | null = null;
  /** Ref of input box */
  inputRef: any = React.createRef();
  /** onEnd successful callback */
  successCallback: any;
  /** Cell location information being edited */
  referencePosition: ReferencePosition | null = null;
  // Whether to edit multiple lines
  textarea: boolean;

  constructor({ textarea }: { textarea: boolean }) {
    this.textarea = textarea || false;
  }

  createElement({ value }: InputEditorCreateElementParams) {
    const inputContainer = document.createElement('div');
    inputContainer.style.position = 'absolute';
    this.inputContainer = inputContainer;
    this.container?.appendChild(inputContainer);
    this.root = ReactDOM.createRoot(inputContainer);
    this.root.render(
      <InputReact textarea={this.textarea} defaultValue={value} ref={this.inputRef} />,
    );
  }

  setValue(value) {
    this.inputRef.current?.setValue(value);
  }

  getValue() {
    return this.inputRef.current?.getValue();
  }

  onStart({ value, referencePosition, container, endEdit }) {
    this.container = container;
    this.successCallback = endEdit;
    if (!this.inputContainer) {
      this.createElement({ value, referencePosition, container });
      if (referencePosition?.rect) {
        this.adjustPosition(referencePosition.rect);
      }
      setTimeout(() => {
        this.inputRef.current?.focus();
      }, 0);
    }
  }

  adjustPosition(rect) {
    if (!this.inputContainer) {
      return;
    }
    this.inputContainer.style.top = rect.top + 1 + 'px';
    this.inputContainer.style.left = rect.left + 1 + 'px';
    this.inputContainer.style.width = rect.width - 2 + 'px';
    this.inputContainer.style.height = rect.height - 2 + 'px';
  }

  endEditing() {}

  onEnd() {
    if (this.inputContainer && this.container?.contains(this.inputContainer)) {
      this.container.removeChild(this.inputContainer);
    }
    this.inputContainer = null;
    this.root?.unmount();
    this.root = null;
  }

  isEditorElement(target) {
    return target === this.inputContainer || Boolean(this.inputContainer?.contains(target));
  }
}

interface DateInputProps {
  defaultValue: string;
  mode: DateEditorMode;
  referenceRect?: DateEditorReferenceRect | null;
  editorContainer?: HTMLElement | null;
}

const DateInputReact = forwardRef((props: DateInputProps, ref) => {
  const { defaultValue, mode, referenceRect, editorContainer } = props;
  const format = DATE_EDITOR_FORMAT_MAP[mode];
  const theme = useStylesStore((s) => s.theme);
  const { styles, cx } = useStyles({ theme });
  const originalValue = valueFilter(defaultValue);
  const [value, setValue] = useState<Dayjs | null>(parseDateEditorValue(defaultValue, format));
  const [changed, setChanged] = useState(false);
  const [open, setOpen] = useState(true);
  const [placement, setPlacement] = useState<DateEditorPlacement>(() =>
    resolveDateEditorPlacement({ mode, referenceRect, editorContainer }),
  );
  const inputRef = useRef<any>(null);
  const valueRef = useRef(value);
  const changedRef = useRef(changed);

  const updateValue = (nextValue: Dayjs | null, nextChanged: boolean) => {
    valueRef.current = nextValue;
    changedRef.current = nextChanged;
    setValue(nextValue);
    setChanged(nextChanged);
  };

  const handlePickerValueChange = (nextValue: Dayjs | Dayjs[] | null) => {
    const normalizedValue = Array.isArray(nextValue) ? nextValue[0] || null : nextValue;
    updateValue(normalizedValue, true);
  };

  const resolvePlacement = () => {
    return resolveDateEditorPlacement({
      mode,
      inputElement: getNativeElement(inputRef.current),
      referenceRect,
      editorContainer,
    });
  };

  const handleOpenChange = (nextOpen: boolean) => {
    if (nextOpen) {
      setPlacement(resolvePlacement());
    }
    setOpen(nextOpen);
  };

  useLayoutEffect(() => {
    if (open) {
      setPlacement(resolvePlacement());
    }
  }, [open, mode]);

  useImperativeHandle(ref, () => ({
    focus: () => {
      inputRef.current?.focus();
    },
    setValue: (val) => {
      updateValue(parseDateEditorValue(val, format), false);
    },
    getValue: () => {
      if (!changedRef.current) {
        return originalValue;
      }
      return valueRef.current ? valueRef.current.format(format) : '';
    },
  }));

  if (mode === 'time') {
    return (
      <TimePicker
        ref={inputRef}
        value={value}
        onChange={(nextValue) => {
          handlePickerValueChange(nextValue);
        }}
        onCalendarChange={(nextValue) => {
          handlePickerValueChange(nextValue as Dayjs | null);
        }}
        format={format}
        open={open}
        onOpenChange={handleOpenChange}
        placement={placement}
        changeOnScroll
        needConfirm={false}
        className={cx(styles.input, styles.datePicker)}
        popupClassName={styles.datePickerPopup}
        getPopupContainer={() => document.body}
      />
    );
  }

  return (
    <DatePicker
      ref={inputRef}
      value={value}
      onChange={(nextValue) => {
        handlePickerValueChange(nextValue);
      }}
      onCalendarChange={(nextValue) => {
        handlePickerValueChange(nextValue as Dayjs | null);
      }}
      format={format}
      open={open}
      onOpenChange={handleOpenChange}
      placement={placement}
      showTime={mode !== 'date'}
      needConfirm={false}
      className={cx(styles.input, styles.datePicker)}
      popupClassName={styles.datePickerPopup}
      getPopupContainer={() => document.body}
    />
  );
});

class DateEditor extends InputEditor {
  mode: DateEditorMode;

  constructor(mode: DateEditorMode) {
    super({ textarea: false });
    this.mode = mode;
  }

  createElement({ value, referencePosition, container }: InputEditorCreateElementParams) {
    if (valueFilter(value) && !parseDateEditorValue(value, DATE_EDITOR_FORMAT_MAP[this.mode])) {
      return super.createElement({ value });
    }
    const inputContainer = document.createElement('div');
    inputContainer.style.position = 'absolute';
    this.inputContainer = inputContainer;
    this.container?.appendChild(inputContainer);
    this.root = ReactDOM.createRoot(inputContainer);
    this.root.render(
      <DateInputReact
        defaultValue={value}
        mode={this.mode}
        referenceRect={referencePosition?.rect}
        editorContainer={container}
        ref={this.inputRef}
      />,
    );
  }

  isEditorElement(target) {
    return (
      super.isEditorElement(target) ||
      (target instanceof HTMLElement && Boolean(target.closest('.ant-picker-dropdown')))
    );
  }
}

const registeredEditor = () => {
  const custom_input_editor = new InputEditor({
    textarea: true,
  });
  VTable.register.editor('custom-input-editor', custom_input_editor);
  VTable.register.editor('custom-date-editor', new DateEditor('date'));
  VTable.register.editor('custom-time-editor', new DateEditor('time'));
  VTable.register.editor('custom-datetime-editor', new DateEditor('datetime'));
  VTable.register.editor('custom-timestamp-editor', new DateEditor('timestamp'));
};

export default registeredEditor;
