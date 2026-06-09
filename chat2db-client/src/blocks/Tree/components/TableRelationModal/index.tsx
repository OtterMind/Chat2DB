import Iconfont from '@/components/Iconfont';
import { TreeNodeType } from '@/constants';
import i18n from '@/i18n';
import sqlService, { IForeignKeyVO } from '@/service/sql';
import { ITreeNode } from '@/typings';
import { DownloadOutlined, EditOutlined, UploadOutlined } from '@ant-design/icons';
import { Button, Form, Input, message, Modal, Select, Space, Table, Tag, Upload } from 'antd';
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

interface IVirtualFkImportItem {
  tableName?: string;
  columnName?: string;
  referencedTable?: string;
  referencedColumnName?: string;
  comment?: string;
}

const buildVirtualFkKey = (item: IVirtualFkImportItem) =>
  [item.tableName, item.columnName].map((value) => `${value || ''}`.trim().toLowerCase()).join('\u0001');

const getNormalizedName = (value?: string) => `${value || ''}`.trim().toLowerCase();

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
  const [importing, setImporting] = useState(false);
  const [deletingAllVirtual, setDeletingAllVirtual] = useState(false);
  const [showForm, setShowForm] = useState(false);
  const [editingRecord, setEditingRecord] = useState<IForeignKeyVO | null>(null);
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

  const virtualForeignKeys = useMemo(() => foreignKeys.filter((item) => item.sourceType === 'VIRTUAL'), [foreignKeys]);

  const filteredVirtualForeignKeys = useMemo(
    () => filteredForeignKeys.filter((item) => item.sourceType === 'VIRTUAL'),
    [filteredForeignKeys],
  );

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

  const handleDeleteAllVirtual = () => {
    if (!virtualForeignKeys.length) {
      message.warning(i18n('workspace.tableRelation.noVirtualFk'));
      return;
    }

    Modal.confirm({
      title: i18n('workspace.tableRelation.deleteAllVirtualConfirmTitle'),
      content: i18n('workspace.tableRelation.deleteAllVirtualConfirmContent', virtualForeignKeys.length),
      okText: i18n('common.button.confirm'),
      okButtonProps: { danger: true },
      cancelText: i18n('common.button.cancel'),
      onOk: async () => {
        setDeletingAllVirtual(true);
        try {
          await Promise.all(
            virtualForeignKeys
              .filter((item) => item.id)
              .map((item) => sqlService.deleteForeignKey({ id: item.id!, sourceType: 'VIRTUAL' })),
          );
          message.success(i18n('workspace.tableRelation.deleteAllVirtualSuccess'));
          fetchForeignKeys();
        } catch {
          message.error(i18n('workspace.tableRelation.deleteAllVirtualError'));
        } finally {
          setDeletingAllVirtual(false);
        }
      },
    });
  };

  const openCreateForm = () => {
    setEditingRecord(null);
    form.resetFields();
    form.setFieldsValue(tableName ? { tableName } : {});
    if (tableName) {
      getFields(tableName);
    }
    setShowForm(true);
  };

  const openEditForm = (record: IForeignKeyVO) => {
    setEditingRecord(record);
    form.setFieldsValue({
      tableName: record.tableName,
      columnName: record.columnName,
      referencedTable: record.referencedTable,
      referencedColumnName: record.referencedColumnName,
      comment: record.comment,
    });
    getFields(record.tableName);
    getFields(record.referencedTable);
    setShowForm(true);
  };

  const closeForm = () => {
    setShowForm(false);
    setEditingRecord(null);
    form.resetFields();
  };

  const handleSubmit = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      if (editingRecord?.id) {
        await sqlService.updateVirtualForeignKey({
          id: editingRecord.id,
          dataSourceId: dataSourceId!,
          databaseName,
          schemaName,
          ...values,
        });
        message.success(i18n('workspace.tableRelation.updateSuccess'));
      } else {
        await sqlService.createVirtualForeignKey({
          dataSourceId: dataSourceId!,
          databaseName,
          schemaName,
          ...values,
        });
        message.success(i18n('workspace.tableRelation.createSuccess'));
      }
      closeForm();
      fetchForeignKeys();
    } catch {
      message.error(
        editingRecord ? i18n('workspace.tableRelation.updateError') : i18n('workspace.tableRelation.createError'),
      );
    } finally {
      setSubmitting(false);
    }
  };

  const normalizeImportData = (data: unknown): IVirtualFkImportItem[] => {
    const rawList = Array.isArray(data)
      ? data
      : Array.isArray((data as { virtualForeignKeys?: unknown[] })?.virtualForeignKeys)
      ? (data as { virtualForeignKeys: unknown[] }).virtualForeignKeys
      : [];

    const itemMap = new Map<string, IVirtualFkImportItem>();
    rawList
      .map((item) => item as IVirtualFkImportItem)
      .filter((item) => item.tableName && item.columnName && item.referencedTable && item.referencedColumnName)
      .forEach((item) => {
        itemMap.set(buildVirtualFkKey(item), item);
      });
    return Array.from(itemMap.values());
  };

  const validateAndNormalizeImportList = async (importList: IVirtualFkImportItem[]) => {
    const tableList = await sqlService.getAllTableList({
      dataSourceId: dataSourceId!,
      databaseName,
      schemaName,
    });
    const tableNameMap = new Map((tableList || []).map((item) => [getNormalizedName(item.name), item.name]));
    const involvedTableNames = Array.from(
      new Set(
        importList
          .flatMap((item) => [item.tableName, item.referencedTable])
          .map(getNormalizedName)
          .filter(Boolean),
      ),
    );
    const columnNameMap: Record<string, Map<string, string>> = {};

    await Promise.all(
      involvedTableNames
        .filter((normalizedTableName) => tableNameMap.has(normalizedTableName))
        .map(async (normalizedTableName) => {
          const actualTableName = tableNameMap.get(normalizedTableName)!;
          const columns = await sqlService.getColumnList({
            dataSourceId: dataSourceId!,
            databaseName,
            schemaName,
            tableName: actualTableName,
          });
          columnNameMap[normalizedTableName] = new Map(
            (columns || []).map((column) => [getNormalizedName(column.name), column.name]),
          );
        }),
    );

    const invalidItems: string[] = [];
    const normalizedList: IVirtualFkImportItem[] = [];
    importList.forEach((item) => {
      const sourceTableKey = getNormalizedName(item.tableName);
      const sourceColumnKey = getNormalizedName(item.columnName);
      const referencedTableKey = getNormalizedName(item.referencedTable);
      const referencedColumnKey = getNormalizedName(item.referencedColumnName);
      const sourceTable = tableNameMap.get(sourceTableKey);
      const referencedTable = tableNameMap.get(referencedTableKey);
      const sourceColumn = columnNameMap[sourceTableKey]?.get(sourceColumnKey);
      const referencedColumn = columnNameMap[referencedTableKey]?.get(referencedColumnKey);
      const relationText = [
        `${item.tableName}.${item.columnName}`,
        `${item.referencedTable}.${item.referencedColumnName}`,
      ].join(' -> ');

      if (!sourceTable || !referencedTable || !sourceColumn || !referencedColumn) {
        invalidItems.push(relationText);
        return;
      }

      normalizedList.push({
        tableName: sourceTable,
        columnName: sourceColumn,
        referencedTable,
        referencedColumnName: referencedColumn,
        comment: item.comment,
      });
    });

    return { invalidItems, normalizedList };
  };

  const handleImport = async (file: File) => {
    if (!dataSourceId || !databaseName) return false;
    setImporting(true);
    try {
      const data = JSON.parse(await file.text());
      const importList = normalizeImportData(data);
      if (!importList.length) {
        message.error(i18n('workspace.tableRelation.importEmpty'));
        return false;
      }

      const { invalidItems, normalizedList } = await validateAndNormalizeImportList(importList);
      if (invalidItems.length) {
        Modal.error({
          title: i18n('workspace.tableRelation.importInvalidTitle'),
          content: (
            <div>
              <div>{i18n('workspace.tableRelation.importInvalidContent', invalidItems.length)}</div>
              <pre className={styles.importInvalidPreview}>{invalidItems.slice(0, 10).join('\n')}</pre>
            </div>
          ),
          width: 640,
        });
        return false;
      }

      const currentVirtualFKs = await sqlService.getForeignKeyList({
        dataSourceId: dataSourceId!,
        databaseName,
        schemaName,
      });
      const currentMap = new Map(
        (currentVirtualFKs || [])
          .filter((item) => item.sourceType === 'VIRTUAL' && item.id)
          .map((item) => [buildVirtualFkKey(item), item]),
      );

      let created = 0;
      let updated = 0;
      for (const item of normalizedList) {
        const existing = currentMap.get(buildVirtualFkKey(item));
        if (existing?.id) {
          await sqlService.updateVirtualForeignKey({
            id: existing.id,
            dataSourceId: dataSourceId!,
            databaseName,
            schemaName,
            tableName: item.tableName,
            columnName: item.columnName,
            referencedTable: item.referencedTable,
            referencedColumnName: item.referencedColumnName,
            comment: item.comment,
          });
          updated++;
        } else {
          await sqlService.createVirtualForeignKey({
            dataSourceId: dataSourceId!,
            databaseName,
            schemaName,
            tableName: item.tableName!,
            columnName: item.columnName!,
            referencedTable: item.referencedTable!,
            referencedColumnName: item.referencedColumnName!,
            comment: item.comment,
          });
          created++;
        }
      }

      message.success(i18n('workspace.tableRelation.importSuccess', created, updated));
      fetchForeignKeys();
    } catch {
      message.error(i18n('workspace.tableRelation.importError'));
    } finally {
      setImporting(false);
    }
    return false;
  };

  const handleExport = () => {
    if (!filteredVirtualForeignKeys.length) {
      message.warning(i18n('workspace.tableRelation.exportEmpty'));
      return;
    }

    const content = JSON.stringify(
      {
        version: 1,
        databaseName,
        schemaName,
        virtualForeignKeys: filteredVirtualForeignKeys.map((item) => ({
          tableName: item.tableName,
          columnName: item.columnName,
          referencedTable: item.referencedTable,
          referencedColumnName: item.referencedColumnName,
          comment: item.comment,
        })),
      },
      null,
      2,
    );
    const blobUrl = URL.createObjectURL(new Blob([content], { type: 'application/json;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = blobUrl;
    link.download = `virtual-foreign-keys-${databaseName || 'database'}.json`;
    link.click();
    URL.revokeObjectURL(blobUrl);
    message.success(i18n('workspace.tableRelation.exportSuccess'));
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
      width: 100,
      render: (_: unknown, record: IForeignKeyVO) => (
        <Space>
          {record.sourceType === 'VIRTUAL' ? (
            <Button type="text" icon={<EditOutlined />} onClick={() => openEditForm(record)} />
          ) : null}
          <Button type="text" danger icon={<Iconfont code="&#xe64e;" />} onClick={() => handleDelete(record)} />
        </Space>
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
          <Upload accept=".json" showUploadList={false} beforeUpload={handleImport}>
            <Button icon={<UploadOutlined />} loading={importing}>
              {i18n('workspace.tableRelation.importVirtualFk')}
            </Button>
          </Upload>
          <Button icon={<DownloadOutlined />} disabled={!filteredVirtualForeignKeys.length} onClick={handleExport}>
            {i18n('workspace.tableRelation.exportVirtualFk')}
          </Button>
          <Button
            danger
            loading={deletingAllVirtual}
            disabled={!virtualForeignKeys.length}
            onClick={handleDeleteAllVirtual}
          >
            {i18n('workspace.tableRelation.deleteAllVirtualFk')}
          </Button>
          <Button onClick={handleSync} loading={syncing}>
            {i18n('editTable.button.syncForeignKeys')}
          </Button>
          <Button type="primary" onClick={showForm && !editingRecord ? closeForm : openCreateForm}>
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
            <Button type="primary" loading={submitting} onClick={handleSubmit}>
              {i18n('common.button.confirm')}
            </Button>
            <Button onClick={closeForm}>{i18n('common.button.cancel')}</Button>
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
    className: 'tableRelationModalRoot',
    content: <TableRelationModal treeNodeData={treeNodeData} />,
    okText: i18n('common.button.close'),
    maskClosable: true,
  });
};

export default TableRelationModal;
