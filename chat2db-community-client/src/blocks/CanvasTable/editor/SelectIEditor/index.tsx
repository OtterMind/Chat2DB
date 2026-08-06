import React, { forwardRef, useImperativeHandle, useMemo, useRef, useState } from 'react';
import * as ReactDOM from 'react-dom/client';
import type { EditContext, IEditor, RectProps } from '@visactor/vtable-editors';
import { ConfigProvider, Select, type ThemeConfig } from 'antd';
import type { IResultSetEditorOption } from '@/typings/database';
import { useStyles } from './style';

export interface SelectEditorTheme {
  colorBgContainer?: string;
  colorBgElevated?: string;
  colorText?: string;
  colorTextSecondary?: string;
  colorTextTertiary?: string;
  colorTextLightSolid?: string;
  colorBorder?: string;
  colorBorderSecondary?: string;
  colorPrimary?: string;
  colorPrimaryBg?: string;
  colorFillSecondary?: string;
  colorFillTertiary?: string;
  boxShadow?: string;
  boxShadowSecondary?: string;
  borderRadius?: number;
  fontFamily?: string;
  fontSize?: number;
}

export type SelectComponentValue = string | string[] | undefined;
type SelectPlacement = 'bottomLeft' | 'topLeft';

interface SelectComponentHandle {
  focus: () => void;
  setValue: (value: unknown) => void;
}

interface SelectComponentProps {
  defaultValue: unknown;
  multiple: boolean;
  options: IResultSetEditorOption[];
  theme: SelectEditorTheme;
  placement: SelectPlacement;
  popupWidth: number;
  onChange: (value: SelectComponentValue) => void;
  onCommit: () => void;
  onCancel: () => void;
}

const DEFAULT_VALUE = 'CHAT2DB_UPDATE_TABLE_DATA_USER_FILLED_DEFAULT';
const GENERATED_VALUE = 'CHAT2DB_UPDATE_TABLE_DATA_USER_FILLED_GENERATED';
const MAX_PICKER_HEIGHT = 224;
const MIN_PICKER_WIDTH = 160;
const OPTION_HEIGHT = 32;
const POPUP_CLASS_NAME = 'chat2db-result-set-select-popup';

export const normalizeSelectEditorOptions = (options?: readonly IResultSetEditorOption[]): IResultSetEditorOption[] => {
  if (!Array.isArray(options)) {
    return [];
  }

  return options
    .filter(
      (option): option is IResultSetEditorOption =>
        Boolean(option) && typeof option.label === 'string' && typeof option.value === 'string',
    )
    .map((option) => ({ label: option.label, value: option.value }));
};

export const getOriginalValueLabel = (value: unknown) => {
  if (value === null) {
    return '<null>';
  }
  if (value === DEFAULT_VALUE) {
    return '<default>';
  }
  if (value === GENERATED_VALUE) {
    return '<generated>';
  }
  if (value === undefined) {
    return '';
  }
  return String(value);
};

export const toSelectComponentValue = (value: unknown, multiple: boolean): SelectComponentValue => {
  if (multiple) {
    if (value === DEFAULT_VALUE || value === GENERATED_VALUE) {
      return [];
    }
    return typeof value === 'string' && value.length > 0 ? value.split(',') : [];
  }
  return value === null || value === undefined ? undefined : String(value);
};

export const fromSelectComponentValue = (value: SelectComponentValue, multiple: boolean) => {
  if (multiple) {
    return Array.isArray(value) ? value.join(',') : '';
  }
  return Array.isArray(value) ? value[0] || '' : value || '';
};

export const normalizeMultiSelectComponentValue = (
  value: readonly string[],
  options: readonly IResultSetEditorOption[],
) => {
  const selectedValues = new Set(value);
  const optionValues = new Set(options.map((option) => option.value));
  const normalizedValues: string[] = [];

  options.forEach((option) => {
    if (selectedValues.has(option.value) && !normalizedValues.includes(option.value)) {
      normalizedValues.push(option.value);
    }
  });
  value.forEach((item) => {
    if (!optionValues.has(item) && !normalizedValues.includes(item)) {
      normalizedValues.push(item);
    }
  });
  return normalizedValues;
};

export const handleSelectEditorKeyDown = (
  event: Pick<React.KeyboardEvent, 'key' | 'stopPropagation'>,
  onCancel: () => void,
) => {
  if (event.key !== 'Enter' && event.key !== 'Escape') {
    return;
  }
  event.stopPropagation();
  if (event.key === 'Escape') {
    onCancel();
  }
};

export const resolveSelectEditorPlacement = (
  rect: RectProps | undefined,
  containerHeight: number,
  optionCount: number,
): SelectPlacement => {
  if (!rect || containerHeight <= 0) {
    return 'bottomLeft';
  }
  const estimatedHeight = Math.min(optionCount * OPTION_HEIGHT + 8, MAX_PICKER_HEIGHT);
  const availableBelow = Math.max(containerHeight - rect.top - rect.height, 0);
  return availableBelow < Math.min(estimatedHeight, 96) && rect.top > availableBelow ? 'topLeft' : 'bottomLeft';
};

const buildThemeConfig = (theme: SelectEditorTheme): ThemeConfig => ({
  token: {
    colorPrimary: theme.colorPrimary,
    colorBgContainer: theme.colorBgContainer,
    colorBgElevated: theme.colorBgElevated,
    colorText: theme.colorText,
    colorTextSecondary: theme.colorTextSecondary,
    colorTextTertiary: theme.colorTextTertiary,
    colorTextLightSolid: theme.colorTextLightSolid,
    colorBorder: theme.colorBorder,
    colorBorderSecondary: theme.colorBorderSecondary,
    colorFillSecondary: theme.colorFillSecondary,
    colorFillTertiary: theme.colorFillTertiary,
    borderRadius: theme.borderRadius,
    fontFamily: theme.fontFamily,
    fontSize: theme.fontSize,
  },
  components: {
    Select: {
      optionSelectedBg: theme.colorPrimaryBg,
      optionActiveBg: theme.colorFillTertiary,
      selectorBg: theme.colorBgContainer,
    },
  },
});

const ResultSetSelect = forwardRef<SelectComponentHandle, SelectComponentProps>((props, ref) => {
  const { defaultValue, multiple, options, theme, placement, popupWidth, onChange, onCommit, onCancel } = props;
  const [value, setValue] = useState<SelectComponentValue>(() => toSelectComponentValue(defaultValue, multiple));
  const [open, setOpen] = useState(true);
  const selectRef = useRef<any>(null);
  const tabKeyDownRef = useRef(false);
  const { styles, cx } = useStyles({ theme });
  const optionValues = useMemo(() => new Set(options.map((option) => option.value)), [options]);
  const renderedOptions = useMemo(() => {
    if (multiple || typeof value !== 'string' || optionValues.has(value)) {
      return options;
    }
    return [{ label: getOriginalValueLabel(defaultValue), value, disabled: true }, ...options];
  }, [defaultValue, multiple, optionValues, options, value]);

  useImperativeHandle(ref, () => ({
    focus: () => selectRef.current?.focus(),
    setValue: (nextValue: unknown) => setValue(toSelectComponentValue(nextValue, multiple)),
  }));

  const handleChange = (nextValue: string | string[]) => {
    // rc-select treats Tab as an option selection before the event reaches VTable.
    // Ignore that synthetic change so Tab only commits and moves to the next cell.
    if (tabKeyDownRef.current) {
      return;
    }
    const normalizedValue = multiple
      ? normalizeMultiSelectComponentValue(Array.isArray(nextValue) ? nextValue : [], options)
      : Array.isArray(nextValue)
        ? nextValue[0]
        : nextValue;
    setValue(normalizedValue);
    onChange(normalizedValue);
  };

  const handleSelect = () => {
    if (!multiple && !tabKeyDownRef.current) {
      onCommit();
    }
  };

  const handleInputKeyDown = (event: React.KeyboardEvent<HTMLInputElement | HTMLTextAreaElement>) => {
    if (event.key !== 'Tab') {
      return;
    }
    tabKeyDownRef.current = true;
    queueMicrotask(() => {
      tabKeyDownRef.current = false;
    });
  };

  const handleKeyDown = (event: React.KeyboardEvent) => {
    handleSelectEditorKeyDown(event, onCancel);
    if (!multiple && event.key === 'Enter') {
      onCommit();
    }
    if (event.key === 'Tab') {
      // VTable normally completes and navigates synchronously as this event bubbles.
      // This queued completion is only used when VTable does not handle Tab, such as its last cell.
      onCommit();
      tabKeyDownRef.current = false;
    }
  };

  return (
    <ConfigProvider componentSize="small" theme={buildThemeConfig(theme)}>
      <Select
        ref={selectRef}
        className={styles.select}
        popupClassName={cx(styles.popup, POPUP_CLASS_NAME)}
        mode={multiple ? 'multiple' : undefined}
        value={value}
        options={renderedOptions}
        defaultActiveFirstOption={false}
        open={open}
        onDropdownVisibleChange={setOpen}
        onChange={handleChange}
        onSelect={handleSelect}
        onInputKeyDown={handleInputKeyDown}
        onKeyDown={handleKeyDown}
        placement={placement}
        popupMatchSelectWidth={popupWidth}
        getPopupContainer={() => document.body}
        placeholder={defaultValue === null ? '<null>' : undefined}
        maxTagCount={multiple ? 'responsive' : undefined}
        allowClear={multiple}
        showSearch={false}
        virtual={options.length > 20}
        autoFocus
      />
    </ConfigProvider>
  );
});

export class SelectEditor implements IEditor<unknown> {
  readonly options: IResultSetEditorOption[];
  readonly multiple: boolean;
  private readonly theme: SelectEditorTheme;
  private container: HTMLElement | null = null;
  private element: HTMLDivElement | null = null;
  private root: ReactDOM.Root | null = null;
  private selectRef = React.createRef<SelectComponentHandle>();
  private successCallback: (() => void) | null = null;
  private originalValue: unknown = null;
  private currentValue: unknown = null;
  private changed = false;

  constructor(options: readonly IResultSetEditorOption[], theme: SelectEditorTheme, multiple = false) {
    this.options = normalizeSelectEditorOptions(options);
    this.theme = theme;
    this.multiple = multiple;
  }

  handleSelectionChange(value: SelectComponentValue) {
    const normalizedValue = this.multiple
      ? normalizeMultiSelectComponentValue(Array.isArray(value) ? value : [], this.options)
      : value;
    this.currentValue = fromSelectComponentValue(normalizedValue, this.multiple);
    this.changed = true;
  }

  cancelEditing() {
    this.currentValue = this.originalValue;
    this.changed = false;
    this.requestFinishEditing();
  }

  getValue() {
    return this.changed ? this.currentValue : this.originalValue;
  }

  setValue(value: unknown) {
    this.originalValue = value;
    this.currentValue = value;
    this.changed = false;
    this.selectRef.current?.setValue(value);
  }

  onStart({ container, value, referencePosition, endEdit }: EditContext<unknown>) {
    this.container = container;
    this.successCallback = endEdit;
    this.setValue(value);

    const rect = referencePosition?.rect;
    const placement = resolveSelectEditorPlacement(rect, container.clientHeight, this.options.length);
    const popupWidth = Math.max(rect?.width || 0, MIN_PICKER_WIDTH);
    const element = document.createElement('div');
    element.style.position = 'absolute';
    element.style.zIndex = '10';
    this.element = element;
    this.container.appendChild(element);
    this.root = ReactDOM.createRoot(element);
    this.root.render(
      <ResultSetSelect
        defaultValue={value}
        multiple={this.multiple}
        options={this.options}
        theme={this.theme}
        placement={placement}
        popupWidth={popupWidth}
        onChange={(nextValue) => this.handleSelectionChange(nextValue)}
        onCommit={() => this.requestFinishEditing()}
        onCancel={() => this.cancelEditing()}
        ref={this.selectRef}
      />,
    );

    if (rect) {
      this.adjustPosition(rect);
    }
    setTimeout(() => this.selectRef.current?.focus(), 0);
  }

  private requestFinishEditing() {
    const callback = this.successCallback;
    const container = this.container;
    if (!callback) {
      return;
    }
    queueMicrotask(() => {
      if (this.successCallback === callback) {
        callback();
        if (container?.isConnected) {
          container.focus();
        }
      }
    });
  }

  private adjustPosition(rect: RectProps) {
    if (!this.element) {
      return;
    }
    this.element.style.top = `${rect.top + 1}px`;
    this.element.style.left = `${rect.left + 1}px`;
    this.element.style.width = `${Math.max(rect.width - 2, 0)}px`;
    this.element.style.height = `${Math.max(rect.height - 2, 0)}px`;
  }

  onEnd() {
    this.root?.unmount();
    this.root = null;
    if (this.element && this.container?.contains(this.element)) {
      this.container.removeChild(this.element);
    }
    this.element = null;
    this.container = null;
    this.successCallback = null;
    this.selectRef = React.createRef<SelectComponentHandle>();
  }

  isEditorElement(target: HTMLElement) {
    return (
      target === this.element ||
      Boolean(this.element?.contains(target)) ||
      (target instanceof Element && Boolean(target.closest(`.${POPUP_CLASS_NAME}`)))
    );
  }
}
