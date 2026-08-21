import i18n from '@/i18n';
import { ColorPicker, Tooltip, type ColorPickerProps } from 'antd';
import { Check, Palette, X } from 'lucide-react';
import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type KeyboardEvent,
  type MouseEvent,
  type SyntheticEvent,
} from 'react';
import {
  DATA_SOURCE_COLOR_CONTROL_COUNT,
  DATA_SOURCE_COLOR_PRESETS,
  getDataSourceColorSelectionIndex,
  getNextDataSourceColorControlIndex,
  resolveDataSourceColorSelection,
  type DataSourceColorNavigationKey,
} from './model';
import { useStyles } from './style';

export interface DataSourceColorPickerProps {
  value?: string | null;
  onChange?: (identityColor: string | null) => void;
  disabled?: boolean;
  showLabel?: boolean;
  responsive?: boolean;
  placement?: ColorPickerProps['placement'];
  stopPropagation?: boolean;
  onPickerOpenChange?: (open: boolean) => void;
  onEscape?: () => void;
  registerFocusTarget?: (focus: () => void) => () => void;
}

const stopEvent = (event: SyntheticEvent) => {
  event.stopPropagation();
};

const DataSourceColorPicker = (props: DataSourceColorPickerProps) => {
  const {
    value,
    onChange,
    disabled,
    showLabel = true,
    responsive,
    placement = 'bottomLeft',
    stopPropagation,
    onPickerOpenChange,
    onEscape,
    registerFocusTarget,
  } = props;
  const { styles, cx } = useStyles();
  const selection = resolveDataSourceColorSelection(value);
  const selectedColor = selection.color;
  const selectedIndex = getDataSourceColorSelectionIndex(value);
  const [activeIndex, setActiveIndex] = useState(selectedIndex);
  const controlRefs = useRef<Array<HTMLButtonElement | null>>([]);

  useEffect(() => {
    setActiveIndex(selectedIndex);
  }, [selectedIndex]);

  const focusSelectedControl = useCallback(() => {
    const focusIndex = controlRefs.current[selectedIndex] ? selectedIndex : 0;
    setActiveIndex(focusIndex);
    controlRefs.current[focusIndex]?.focus();
  }, [selectedIndex]);

  useEffect(() => registerFocusTarget?.(focusSelectedControl), [focusSelectedControl, registerFocusTarget]);

  const selectColor = (event: MouseEvent<HTMLButtonElement>, color: string | null) => {
    event.preventDefault();
    if (stopPropagation) {
      event.stopPropagation();
    }
    onChange?.(color);
  };

  const handleControlKeyDown = (event: KeyboardEvent<HTMLButtonElement>, currentIndex: number) => {
    if (['Enter', ' ', 'Spacebar'].includes(event.key)) {
      if (stopPropagation) {
        event.stopPropagation();
      }
      return;
    }
    if (event.key === 'Tab' && onEscape) {
      window.setTimeout(onEscape, 0);
      return;
    }
    if (event.key === 'Escape' && onEscape) {
      event.preventDefault();
      event.stopPropagation();
      onEscape();
      return;
    }
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) {
      return;
    }
    event.preventDefault();
    if (stopPropagation) {
      event.stopPropagation();
    }
    const nextIndex = getNextDataSourceColorControlIndex(
      currentIndex,
      event.key as DataSourceColorNavigationKey,
    );
    setActiveIndex(nextIndex);
    controlRefs.current[nextIndex]?.focus();
  };

  const getControlProps = (index: number) => ({
    ref: (element: HTMLButtonElement | null) => {
      controlRefs.current[index] = element;
    },
    tabIndex: activeIndex === index ? 0 : -1,
    onFocus: () => setActiveIndex(index),
    onKeyDown: (event: KeyboardEvent<HTMLButtonElement>) => handleControlKeyDown(event, index),
  });

  const selectionMark = (
    <span className={styles.selectionMark} aria-hidden="true">
      <Check size={9} strokeWidth={3} />
    </span>
  );

  return (
    <div
      className={cx(styles.palette, responsive && styles.responsivePalette)}
      role="toolbar"
      aria-label={i18n('workspace.identityColor.title')}
      onClick={stopPropagation ? stopEvent : undefined}
      onMouseDown={stopPropagation ? stopEvent : undefined}
    >
      {showLabel && <div className={styles.label}>{i18n('workspace.identityColor.label')}</div>}
      <div className={styles.swatches}>
        <Tooltip title={i18n('workspace.identityColor.clear')} mouseEnterDelay={0.4}>
          <button
            type="button"
            disabled={disabled}
            className={cx(styles.swatch, styles.utilitySwatch, selection.type === 'clear' && styles.selected)}
            aria-label={i18n('workspace.identityColor.clear')}
            aria-pressed={selection.type === 'clear'}
            onClick={(event) => selectColor(event, null)}
            {...getControlProps(0)}
          >
            <X size={15} />
            {selection.type === 'clear' && selectionMark}
          </button>
        </Tooltip>

        {DATA_SOURCE_COLOR_PRESETS.map((color, presetIndex) => {
          const controlIndex = presetIndex + 1;
          const selected = selection.type === 'preset' && selectedColor === color;
          const label = `${i18n('workspace.identityColor.presets')}: ${color}`;
          return (
            <Tooltip key={color} title={label} mouseEnterDelay={0.4}>
              <button
                type="button"
                disabled={disabled}
                className={cx(styles.swatch, selected && styles.selected)}
                style={{ backgroundColor: color }}
                aria-label={label}
                aria-pressed={selected}
                onClick={(event) => selectColor(event, color)}
                {...getControlProps(controlIndex)}
              >
                {selected && selectionMark}
              </button>
            </Tooltip>
          );
        })}

        <Tooltip title={i18n('workspace.identityColor.custom')} mouseEnterDelay={0.4}>
          <ColorPicker
            value={selectedColor || DATA_SOURCE_COLOR_PRESETS[0]}
            disabled={disabled}
            disabledAlpha
            format="hex"
            placement={placement}
            onOpenChange={onPickerOpenChange}
            onChangeComplete={(color) => onChange?.(color.toHexString().toUpperCase())}
          >
            <button
              type="button"
              disabled={disabled}
              className={cx(styles.swatch, styles.utilitySwatch, selection.type === 'custom' && styles.selected)}
              style={selection.type === 'custom' ? { color: selectedColor || undefined } : undefined}
              aria-label={i18n('workspace.identityColor.custom')}
              aria-pressed={selection.type === 'custom'}
              onClick={stopPropagation ? stopEvent : undefined}
              onMouseDown={stopPropagation ? stopEvent : undefined}
              {...getControlProps(DATA_SOURCE_COLOR_CONTROL_COUNT - 1)}
            >
              <Palette size={15} />
              {selection.type === 'custom' && selectionMark}
            </button>
          </ColorPicker>
        </Tooltip>
      </div>
    </div>
  );
};

export default DataSourceColorPicker;
