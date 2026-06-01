import Iconfont from '@/components/Iconfont';
import { TreeNodeType } from '@/constants';
import i18n from '@/i18n';
import sqlService, { IForeignKeyVO } from '@/service/sql';
import { ITreeNode } from '@/typings';
import { Button, Form, Input, message, Modal, Select, Space, Table, Tag } from 'antd';
import React, { memo, useCallback, useEffect, useMemo, useState } from 'react';
import styles from './index.less';

interface IProps {
  treeNodeData: ITreeNode;
}

interface IAddRelationForm {
  tableName: string;
  columnName: string;
  referencedTable: string;
  referencedColumnName: string;
  comment?: string;
}

const TableRelationModal = memo((props: IProps) => {
  const { treeNodeData } = props;
  const { dataSourceId, databaseName, schemaName } = treeNodeData.extraParams || {};
  const isForeignKeyNode =
    treeNodeData.treeNodeType === TreeNodeType.KEY || treeNodeData.treeNodeType === TreeNodeType.V_KEY;
  const tableName =
    treeNodeData.treeNodeType === TreeNodeType.TABLE ? treeNodeData.name : treeNodeData.extraParams?.tableName;
  const keyName = isForeignKeyNode ? treeNodeData.name : undefined;

  const [form] = Form.useForm<IAddRelationForm>();
  const [loading, setLoading] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [showForm, setShowForm] = useState(false);
  const [searchKeyword, setSearchKeyword] = useState('');
  const [foreignKeys, setForeignKeys] = useState<IForeignKeyVO[]>([]);
  const [tables, setTables] = useState<Array<{ name: string; comment?: string }>>([]);
  const [fieldMap, setFieldMap] = useState<Record<string, Array<{ name: string }>>>({});

  const sortedForeignKeys = useMemo(() => {
    if (!foreignKeys.length) return [];
    const refCount: Record<string, number> = {};
    foreignKeys.forEach((fk) => {
      refCount[fk.referencedTable] = (refCount[fk.referencedTable] || 0) + 1;
    });
    return [...foreignKeys].sort((a, b) => {
      const countA = refCount[a.referencedTable] || 0;
      const countB = refCount[b.referencedTable] || 0;
      if (countB !== countA) return countB - countA;
      return a.referencedTable.localeCompare(b.referencedTable);
    });
  }, [foreignKeys]);

  const filteredForeignKeys = useMemo(() => {
    const keyword = searchKeyword.trim().toLowerCase();
    if (!keyword) return sortedForeignKeys;

    return sortedForeignKeys.filter((fk) =>
      [
        fk.referencedTable,
        fk.referencedColumnName,
        fk.tableName,
        fk.columnName,
        fk.sourceType,
        fk.sourceType === 'VIRTUAL' ? i18n('editTable.tooltip.virtualFK') : i18n('editTable.tooltip.realFK'),
        fk.comment,
      ].some((value) => `${value || ''}`.toLowerCase().includes(keyword)),
    );
  }, [searchKeyword, sortedForeignKeys]);

  const tableOptions = useMemo(() => tables.map((item) => ({ label: item.name, value: item.name })), [tables]);

  const getFields = useCallback(
    async (selectedTableName?: string) => {
      if (!selectedTableName || fieldMap[selectedTableName]) {
        return;
      }
      const fields = await sqlService.getColumnList({
        dataSourceId: dataSourceId!,
        databaseName: databaseName!,
        schemaName,
        tableName: selectedTableName,
      });
      setFieldMap((prev) => ({
        ...prev,
        [selectedTableName]: (fields || []).map((item) => ({ name: item.name })),
      }));
    },
    [dataSourceId, databaseName, fieldMap, schemaName],
  );

  const fetchForeignKeys = useCallback(async () => {
    if (!dataSourceId || !databaseName) return;
    setLoading(true);
    try {
      const list = await sqlService.getForeignKeyList({
        dataSourceId: dataSourceId!,
        databaseName: databaseName!,
        schemaName,
      });
      const tableForeignKeys = tableName
        ? (list || []).filter((item) => item.tableName === tableName || item.referencedTable === tableName)
        : list || [];
      setForeignKeys(keyName ? tableForeignKeys.filter((item) => item.name === keyName) : tableForeignKeys);
    } catch {
      message.error(i18n('workspace.tableRelation.loadError'));
    } finally {
      setLoading(false);
    }
  }, [dataSourceId, databaseName, schemaName, tableName, keyName]);

  const fetchTables = useCallback(async () => {
    if (!dataSourceId || !databaseName) return;
    try {
      const list = await sqlService.getAllTableList({
        dataSourceId: dataSourceId!,
        databaseName,
        schemaName,
      });
      setTables(list || []);
    } catch {
      setTables([]);
    }
  }, [dataSourceId, databaseName, schemaName]);

  useEffect(() => {
    fetchForeignKeys();
    fetchTables();
  }, [fetchForeignKeys, fetchTables]);

  const handleSync = async () => {
    if (!dataSourceId || !databaseName) return;
    setSyncing(true);
    try {
      const result = await sqlService.syncForeignKeys({
        dataSourceId: dataSourceId!,
        databaseName,
        schemaName,
      });
      message.success(
        i18n('editTable.message.syncFKSuccess', result?.added || 0, result?.deleted || 0, result?.unchanged || 0),
      );
      fetchForeignKeys();
    } catch {
      message.error(i18n('editTable.message.syncFKError'));
    } finally {
      setSyncing(false);
    }
  };

  const handleDelete = (record: IForeignKeyVO) => {
    if (!record.id) {
      message.error(i18n('workspace.tableRelation.missingId'));
      return;
    }
    Modal.confirm({
      title: i18n('workspace.tableRelation.deleteConfirmTitle'),
      content:
        record.sourceType === 'REAL'
          ? i18n('workspace.tableRelation.deleteRealConfirmContent')
          : i18n('workspace.tableRelation.deleteVirtualConfirmContent'),
      okText: i18n('common.button.confirm'),
      cancelText: i18n('common.button.cancel'),
      onOk: async () => {
        const result = await sqlService.deleteForeignKey({ id: record.id!, sourceType: record.sourceType });
        if (result?.executedDDL) {
          Modal.info({
            title: i18n('workspace.tableRelation.realDeleteDDLTitle'),
            content: <pre className={styles.ddlPreview}>{result.executedDDL}</pre>,
            width: 640,
          });
        }
        message.success(i18n('workspace.tableRelation.deleteSuccess'));
        fetchForeignKeys();
      },
    });
  };

  const handleCreate = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      await sqlService.createVirtualForeignKey({
        dataSourceId: dataSourceId!,
        databaseName,
        schemaName,
        ...values,
      });
      message.success(i18n('workspace.tableRelation.createSuccess'));
      form.resetFields();
      setShowForm(false);
      fetchForeignKeys();
    } catch {
      message.error(i18n('workspace.tableRelation.createError'));
    } finally {
      setSubmitting(false);
    }
  };

  const columns = [
    {
      title: i18n('workspace.tableRelation.masterTable'),
      dataIndex: 'referencedTable',
      width: 160,
    },
    {
      title: i18n('workspace.tableRelation.uniqueColumn'),
      dataIndex: 'referencedColumnName',
      width: 160,
    },
    {
      title: i18n('workspace.tableRelation.childTable'),
      dataIndex: 'tableName',
      width: 160,
    },
    {
      title: i18n('workspace.tableRelation.relationColumn'),
      dataIndex: 'columnName',
      width: 160,
    },
    {
      title: i18n('editTable.label.sourceType'),
      dataIndex: 'sourceType',
      width: 100,
      render: (sourceType: IForeignKeyVO['sourceType']) => (
        <Tag color={sourceType === 'VIRTUAL' ? 'blue' : 'green'}>
          {sourceType === 'VIRTUAL' ? i18n('editTable.tooltip.virtualFK') : i18n('editTable.tooltip.realFK')}
        </Tag>
      ),
    },
    {
      title: i18n('editTable.label.comment'),
      dataIndex: 'comment',
      ellipsis: true,
    },
    {
      title: i18n('workspace.tableRelation.operation'),
      width: 80,
      render: (_: unknown, record: IForeignKeyVO) => (
        <Button type="text" danger icon={<Iconfont code="&#xe64e;" />} onClick={() => handleDelete(record)} />
      ),
    },
  ];

  const renderFieldOptions = (selectedTableName?: string) =>
    (fieldMap[selectedTableName || ''] || []).map((item) => ({ label: item.name, value: item.name }));

  return (
    <div className={styles.tableRelationModal}>
      <div className={styles.toolbar}>
        <Input.Search
          allowClear
          className={styles.search}
          placeholder={i18n('workspace.tableRelation.searchPlaceholder')}
          value={searchKeyword}
          onChange={(event) => setSearchKeyword(event.target.value)}
        />
        <Space>
          <Button onClick={handleSync} loading={syncing}>
            {i18n('editTable.button.syncForeignKeys')}
          </Button>
          <Button type="primary" onClick={() => setShowForm((prev) => !prev)}>
            {i18n('editTable.button.addForeignKey')}
          </Button>
        </Space>
      </div>
      {showForm ? (
        <Form
          className={styles.form}
          form={form}
          layout="vertical"
          initialValues={tableName ? { tableName } : undefined}
          onValuesChange={(changedValues) => {
            if (changedValues.tableName) {
              form.setFieldValue('columnName', undefined);
              getFields(changedValues.tableName);
            }
            if (changedValues.referencedTable) {
              form.setFieldValue('referencedColumnName', undefined);
              getFields(changedValues.referencedTable);
            }
          }}
        >
          <div className={styles.formGrid}>
            <Form.Item label={i18n('workspace.tableRelation.childTable')} name="tableName" rules={[{ required: true }]}>
              <Select showSearch options={tableOptions} onFocus={() => getFields(form.getFieldValue('tableName'))} />
            </Form.Item>
            <Form.Item
              label={i18n('workspace.tableRelation.relationColumn')}
              name="columnName"
              rules={[{ required: true }]}
            >
              <Select
                showSearch
                options={renderFieldOptions(form.getFieldValue('tableName'))}
                onFocus={() => getFields(form.getFieldValue('tableName'))}
              />
            </Form.Item>
            <Form.Item
              label={i18n('workspace.tableRelation.masterTable')}
              name="referencedTable"
              rules={[{ required: true }]}
            >
              <Select showSearch options={tableOptions} />
            </Form.Item>
            <Form.Item
              label={i18n('workspace.tableRelation.uniqueColumn')}
              name="referencedColumnName"
              rules={[{ required: true }]}
            >
              <Select
                showSearch
                options={renderFieldOptions(form.getFieldValue('referencedTable'))}
                onFocus={() => getFields(form.getFieldValue('referencedTable'))}
              />
            </Form.Item>
          </div>
          <Form.Item label={i18n('editTable.label.comment')} name="comment">
            <Input />
          </Form.Item>
          <Space>
            <Button type="primary" loading={submitting} onClick={handleCreate}>
              {i18n('common.button.confirm')}
            </Button>
            <Button onClick={() => setShowForm(false)}>{i18n('common.button.cancel')}</Button>
          </Space>
        </Form>
      ) : null}
      <Table
        rowKey={(record) => `${record.sourceType}-${record.id || record.name}`}
        loading={loading}
        columns={columns as any}
        dataSource={filteredForeignKeys}
        pagination={false}
        scroll={{ x: 900, y: 420 }}
      />
    </div>
  );
});

export const openTableRelationModal = (treeNodeData: ITreeNode) => {
  Modal.info({
    title: i18n('workspace.menu.viewTableRelation'),
    icon: null,
    width: 980,
    content: <TableRelationModal treeNodeData={treeNodeData} />,
    okText: i18n('common.button.close'),
    maskClosable: true,
  });
};

export default TableRelationModal;
