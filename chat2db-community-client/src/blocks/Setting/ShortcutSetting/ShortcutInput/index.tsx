import React, { useEffect, useRef, useState } from 'react';
import { useStyles } from './style';
import { getEventShortcutBinding, normalizeShortcutBinding } from '@/constants/shortcut';
import { i18n } from '@/i18n';

interface IProps {
  value: string | null;
  onChange: (value: string) => void;
  disabled?: boolean;
  placeholder?: string;
}

const keySymbolMap: Record<string, string> = {
  meta: '⌘',
  control: 'Ctrl',
  alt: 'Alt',
  shift: 'Shift',
};
// Sort modifier keys in the specified order
const modifierOrder = {
  meta: 1,
  control: 1, // meta and control are the same level
  alt: 2,
  shift: 3,
};

const getShortcutKeys = (shortcut?: string | null) => {
  const normalizedShortcut = normalizeShortcutBinding(shortcut);
  if (!normalizedShortcut) {
    return [];
  }
  return normalizedShortcut.split(' + ');
};

const ShortcutInput: React.FC<IProps> = ({ value, onChange, disabled, placeholder }) => {
  const { styles, cx } = useStyles();
  const [displayValue, setDisplayValue] = useState(normalizeShortcutBinding(value) || '');
  const [focused, setFocused] = useState(false);
  const keysPressed = useRef<Set<string>>(new Set());
  const pendingShortcutRef = useRef(value || '');

  useEffect(() => {
    setDisplayValue(normalizeShortcutBinding(value) || '');
    pendingShortcutRef.current = value || '';
  }, [value]);

  const handleKeyDown = (e: React.KeyboardEvent<HTMLButtonElement>) => {
    if (disabled) return;
    e.preventDefault();

    const binding = getEventShortcutBinding(e);
    if (binding) {
      pendingShortcutRef.current = binding;
      setDisplayValue(binding);
      if (binding !== value) {
        onChange(binding);
      }
      return;
    }

    const key = e.key.toLowerCase();

    keysPressed.current.add(key);

    const shortcut = Array.from(keysPressed.current)
      .sort((a, b) => {
        const orderA = modifierOrder[a as keyof typeof modifierOrder] || 999;
        const orderB = modifierOrder[b as keyof typeof modifierOrder] || 999;
        return orderA - orderB;
      })
      .map((k) => keySymbolMap[k] || k.charAt(0).toUpperCase() + k.slice(1))
      .join(' + ');

    const normalizedShortcut = normalizeShortcutBinding(shortcut) || shortcut;
    pendingShortcutRef.current = normalizedShortcut;
    setDisplayValue(normalizedShortcut);
  };

  const handleKeyUp = () => {
    if (disabled) return;
    const normalizedValue = normalizeShortcutBinding(pendingShortcutRef.current);
    if (normalizedValue && normalizedValue !== value) {
      onChange(normalizedValue);
    }
    keysPressed.current.clear();
  };

  const shortcutKeys = getShortcutKeys(displayValue);

  return (
    <button
      className={cx(styles.shortcutInput, focused && styles.shortcutInputFocused)}
      type="button"
      disabled={disabled}
      onKeyDown={handleKeyDown}
      onKeyUp={handleKeyUp}
      onFocus={() => setFocused(true)}
      onBlur={() => setFocused(false)}
    >
      {shortcutKeys.length ? (
        shortcutKeys.map((key) => (
          <span className={styles.shortcutKey} key={key}>
            {key}
          </span>
        ))
      ) : (
        <span className={styles.placeholder}>{placeholder || i18n('setting.shortcut.placeholder.input')}</span>
      )}
    </button>
  );
};

export default ShortcutInput;
