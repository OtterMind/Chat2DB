import { FolderOpen, X } from 'lucide-react';
import { createStyles } from 'antd-style';

interface DirectoryPickerProps {
  id: string;
  value: string;
  emptyLabel: string;
  clearLabel: string;
  disabled: boolean;
  onSelect: () => void;
  onClear: () => void;
}

const useStyles = createStyles(({ css, token }) => ({
  field: css`
    display: flex;
    min-width: 0;
    border: 1px solid ${token.colorBorder};
    border-radius: 6px;
    background: ${token.colorBgContainer};
    &:hover, &:focus-within { border-color: ${token.colorPrimary}; }
    button {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 8px 10px;
      border: 0;
      color: ${token.colorText};
      background: transparent;
      cursor: pointer;
      &:disabled { cursor: default; color: ${token.colorTextDisabled}; }
      &:focus-visible { outline: 2px solid ${token.colorPrimary}; outline-offset: -2px; }
    }
    svg { flex-shrink: 0; }
    [data-directory-clear] { opacity: 0; }
    &:hover [data-directory-clear], &:focus-within [data-directory-clear] { opacity: 1; }
    @media (hover: none) { [data-directory-clear] { opacity: 1; } }
  `,
  select: css`
    flex: 1;
    min-width: 0;
    text-align: left;
    span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  `,
}));

export default function DirectoryPicker({
  id, value, emptyLabel, clearLabel, disabled, onSelect, onClear,
}: DirectoryPickerProps) {
  const { styles } = useStyles();
  return (
    <div className={styles.field}>
      <button id={id} type="button" className={styles.select} disabled={disabled} onClick={onSelect} title={value || emptyLabel}>
        <FolderOpen size={16} aria-hidden="true" />
        <span>{value || emptyLabel}</span>
      </button>
      {value && <button type="button" data-directory-clear disabled={disabled} onClick={onClear} aria-label={clearLabel}>
        <X size={14} aria-hidden="true" />
      </button>}
    </div>
  );
}
