import { forwardRef, useContext, useEffect, useImperativeHandle, useState } from 'react';
import { Button, Checkbox, Input, Modal, Popconfirm, Table } from 'antd';
import { Context } from '..';
import { EditColumnOperationType } from '@/constants';
import { ICheckConstraintItem } from '@/typings';
import i18n from '@/i18n';
import {
  CheckConstraintField,
  createCheckConstraintDraft,
  markCheckConstraintDeleted,
  markCheckConstraintUpdated,
  prepareCheckConstraintsForSubmit,
  visibleCheckConstraints,
} from './checkConstraintList';

export interface ICheckConstraintListRef {
  getCheckConstraintListInfo: () => ICheckConstraintItem[];
}

const CheckConstraintList = forwardRef<ICheckConstraintListRef>((_props, ref) => {
  const { tableDetails, databaseBaseInfo } = useContext(Context);
  const [constraints, setConstraints] = useState<ICheckConstraintItem[]>([]);

  useEffect(() => {
    setConstraints(
      (tableDetails.checkConstraintList || []).map((item, index) => ({
        ...item,
        key: item.key || `${item.name || 'check'}-${index}`,
        enforced: item.enforced !== false,
      })),
    );
  }, [tableDetails]);
  useImperativeHandle(ref, () => ({
    getCheckConstraintListInfo: () =>
      prepareCheckConstraintsForSubmit(constraints, {
        databaseName: databaseBaseInfo.databaseName,
        schemaName: databaseBaseInfo.schemaName,
        tableName: tableDetails.name || databaseBaseInfo.tableName,
      }),
  }));

  const update = (record: ICheckConstraintItem, field: CheckConstraintField, value: string | boolean) => {
    setConstraints((current) =>
      current.map((item) =>
        item.key === record.key ? markCheckConstraintUpdated(item, field, value) : item,
      ),
    );
  };

  const updateEnforced = (record: ICheckConstraintItem, enforced: boolean) => {
    if (!enforced) {
      update(record, 'enforced', enforced);
      return;
    }
    Modal.confirm({
      title: i18n('editTable.check.warning.title'),
      content: i18n('editTable.check.warning.enforcedContent'),
      okText: i18n('common.button.confirm'),
      cancelText: i18n('common.button.cancel'),
      onOk: () => update(record, 'enforced', enforced),
    });
  };

  const deleteConstraint = (item: ICheckConstraintItem) => {
    setConstraints((current) =>
      current
        .map((entry) => (entry.key === item.key ? markCheckConstraintDeleted(entry) : entry))
        .filter((entry): entry is ICheckConstraintItem => Boolean(entry)),
    );
  };

  return (
    <>
      <Button
        onClick={() =>
          setConstraints((current) => [...current, createCheckConstraintDraft(`check-${Date.now()}`)])
        }
      >
        {i18n('editTable.button.addCheckConstraint')}
      </Button>
      <Table
        rowKey={(item) => item.key || item.name}
        pagination={false}
        dataSource={visibleCheckConstraints(constraints)}
        columns={[
          {
            title: i18n('editTable.check.label.name'),
            dataIndex: 'name',
            render: (value, record) => (
              <Input
                value={value}
                disabled={record.editStatus !== EditColumnOperationType.Add}
                onChange={(event) => update(record, 'name', event.target.value)}
              />
            ),
          },
          {
            title: i18n('editTable.check.label.expression'),
            dataIndex: 'expression',
            render: (value, record) => (
              <Input value={value} onChange={(event) => update(record, 'expression', event.target.value)} />
            ),
          },
          {
            title: i18n('editTable.check.label.enforced'),
            dataIndex: 'enforced',
            render: (value, record) => (
              <Checkbox
                checked={value !== false}
                onChange={(event) => updateEnforced(record, event.target.checked)}
              />
            ),
          },
          {
            title: '',
            render: (_value, item) => (
              <Popconfirm
                title={i18n('editTable.check.deleteConfirm.title')}
                okText={i18n('common.button.confirm')}
                cancelText={i18n('common.button.cancel')}
                onConfirm={() => deleteConstraint(item)}
              >
                <Button danger>{i18n('editTable.button.delete')}</Button>
              </Popconfirm>
            ),
          },
        ]}
      />
    </>
  );
});

export default CheckConstraintList;
