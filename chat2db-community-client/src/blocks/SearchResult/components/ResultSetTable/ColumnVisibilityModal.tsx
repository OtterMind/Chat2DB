import { memo, useEffect, useMemo, useState } from 'react';
import { Checkbox, Input, Modal, Tooltip } from 'antd';
import { CircleHelp, Search } from 'lucide-react';
import i18n from '@/i18n';
import type { ITableHeaderItem } from '@/typings/database';
import { formatFieldType } from './headerMetadata';
import { updateHiddenResultColumnFields } from './columnState';
import { useStyles } from './style';

export interface ResultColumnVisibilityOption {
  field: string;
  header: ITableHeaderItem;
}

interface IProps {
  open: boolean;
  columns: ResultColumnVisibilityOption[];
  hiddenFields: ReadonlySet<string>;
  onCancel: () => void;
  onConfirm: (hiddenFields: Set<string>) => void;
}

const ColumnVisibilityModal = ({ open, columns, hiddenFields, onCancel, onConfirm }: IProps) => {
  const { styles } = useStyles();
  const [searchValue, setSearchValue] = useState('');
  const [draftHiddenFields, setDraftHiddenFields] = useState<Set<string>>(() => new Set(hiddenFields));
  const fields = useMemo(() => columns.map((column) => column.field), [columns]);

  useEffect(() => {
    if (!open) {
      return;
    }
    setSearchValue('');
    setDraftHiddenFields(new Set(hiddenFields));
  }, [hiddenFields, open]);

  const visibleCount = fields.length - draftHiddenFields.size;
  const normalizedSearchValue = searchValue.trim().toLocaleLowerCase();
  const filteredColumns = useMemo(() => {
    if (!normalizedSearchValue) {
      return columns;
    }
    return columns.filter(({ header }) =>
      [header.name, formatFieldType(header), header.comment]
        .filter(Boolean)
        .some((value) =>
          String(value)
            .toLocaleLowerCase()
            .includes(normalizedSearchValue),
        ),
    );
  }, [columns, normalizedSearchValue]);

  return (
    <Modal
      open={open}
      width={720}
      title={
        <span className={styles.columnVisibilityTitle}>
          <span>{i18n('common.text.showHideColumns')}</span>
          <Tooltip title={i18n('common.text.manageColumns.tooltip')} mouseEnterDelay={0.2}>
            <button
              type="button"
              className={styles.columnVisibilityHelp}
              aria-label={i18n('common.text.manageColumns.tooltip')}
            >
              <CircleHelp aria-hidden="true" size={14} />
            </button>
          </Tooltip>
        </span>
      }
      okText={i18n('common.button.confirm')}
      cancelText={i18n('common.button.cancel')}
      onCancel={onCancel}
      onOk={() => onConfirm(new Set(draftHiddenFields))}
      destroyOnClose
    >
      <Input
        allowClear
        value={searchValue}
        className={styles.columnSearch}
        prefix={<Search size={14} />}
        placeholder={i18n('common.text.searchPlaceholder')}
        onChange={(event) => setSearchValue(event.target.value)}
      />
      <div className={styles.columnVisibilityList}>
        {filteredColumns.map(({ field, header }) => {
          const checked = !draftHiddenFields.has(field);
          return (
            <Checkbox
              key={field}
              checked={checked}
              disabled={checked && visibleCount <= 1}
              className={styles.columnVisibilityItem}
              onChange={(event) => {
                setDraftHiddenFields((current) =>
                  updateHiddenResultColumnFields(fields, current, field, event.target.checked),
                );
              }}
            >
              <span className={styles.columnVisibilityName}>{header.name?.trim() || '--'}</span>
              <span className={styles.columnVisibilityType}>{formatFieldType(header)}</span>
              <span className={styles.columnVisibilityComment}>{header.comment?.trim() || '--'}</span>
            </Checkbox>
          );
        })}
      </div>
    </Modal>
  );
};

export default memo(ColumnVisibilityModal);
