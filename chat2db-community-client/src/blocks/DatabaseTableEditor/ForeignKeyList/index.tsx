import React, {
  ForwardedRef,
  forwardRef,
  useContext,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
} from 'react';
import classnames from 'classnames';
import { MenuOutlined } from '@ant-design/icons';
import { DndContext, type DragEndEvent } from '@dnd-kit/core';
import { restrictToVerticalAxis } from '@dnd-kit/modifiers';
import { arrayMove, SortableContext, useSortable, verticalListSortingStrategy } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { Alert, Form, Input, Modal, Select, Table } from 'antd';
import { v4 as uuidv4 } from 'uuid';
import i18n from '@/i18n';
import Iconfont from '@/components/Iconfont';
import sqlService from '@/service/sql';
import { EditColumnOperationType } from '@/constants/editTable';
import type { IColumnItemNew, IForeignKeyColumnMappingItem, IForeignKeyInfo, IForeignKeyItem } from '@/typings';
import { useStyles } from '../ColumnList/style';
import { Context } from '../index';
import {
  flattenForeignKeysForSubmit,
  getForeignKeyActionOptions,
  groupForeignKeysForEditor,
} from './foreignKeyEditor';

interface IProps {}

export type IForeignKeyListInfo = IForeignKeyInfo[];

export interface IForeignKeyListRef {
  getForeignKeyListInfo: () => IForeignKeyListInfo;
}

interface RowProps extends React.HTMLAttributes<HTMLTableRowElement> {
  'data-row-key': string;
}

const Row = ({ children, ...props }: RowProps) => {
  const { attributes, listeners, setNodeRef, setActivatorNodeRef, transform, transition, isDragging } = useSortable({
    id: props['data-row-key'],
  });

  const style: React.CSSProperties = {
    ...props.style,
    transform: CSS.Transform.toString(transform && { ...transform, scaleY: 1 }),
    transition,
    ...(isDragging ? { position: 'relative', zIndex: 9999 } : {}),
  };

  return (
    <tr {...props} ref={setNodeRef} style={style} {...attributes}>
      {React.Children.map(children, (child) => {
        if ((child as React.ReactElement).key === 'sort') {
          return React.cloneElement(child as React.ReactElement, {
            children: (
              <MenuOutlined ref={setActivatorNodeRef} style={{ touchAction: 'none', cursor: 'move' }} {...listeners} />
            ),
          });
        }
        return child;
      })}
    </tr>
  );
};

const createInitialData = (): IForeignKeyItem => ({
  key: uuidv4(),
  fkName: '',
  pkTableName: '',
  updateRule: 1,
  deleteRule: 1,
  columnList: [],
  editStatus: EditColumnOperationType.Add,
});

const formatColumnList = (columnList: IForeignKeyColumnMappingItem[], field: 'fkColumnName' | 'pkColumnName') => {
  return columnList
    .map((column) => column[field])
    .filter(Boolean)
    .join(', ');
};

const ForeignKeyList = forwardRef((props: IProps, ref: ForwardedRef<IForeignKeyListRef>) => {
  const { styles } = useStyles();
  const { tableDetails, columnListRef, databaseBaseInfo } = useContext(Context);
  const { dataSourceId, databaseName, schemaName } = databaseBaseInfo;
  const [dataSource, setDataSource] = useState<IForeignKeyItem[]>([]);
  const [editingData, setEditingData] = useState<IForeignKeyItem | null>(null);
  const [columnModalOpen, setColumnModalOpen] = useState(false);
  const [columnMappings, setColumnMappings] = useState<IForeignKeyColumnMappingItem[]>([]);
  const [referencedColumns, setReferencedColumns] = useState<Array<{ label: string; value: string }>>([]);
  const [tableOptions, setTableOptions] = useState<Array<{ label: string; value: string }>>([]);
  const [form] = Form.useForm();
  const tableRef = useRef<any>(null);
  const tableBoxRef = useRef<any>(null);
  const [tableScrollY, setTableScrollY] = useState(0);
  const foreignKeyActionOptions = useMemo(() => getForeignKeyActionOptions(i18n), []);

  useEffect(() => {
    const resizeObserver = new ResizeObserver((entries) => {
      for (const entry of entries) {
        setTableScrollY(entry.contentRect.height - 118);
      }
    });

    if (tableBoxRef.current) {
      resizeObserver.observe(tableBoxRef.current);
    }

    return () => {
      resizeObserver.disconnect();
    };
  }, [tableBoxRef.current]);

  useEffect(() => {
    const data = groupForeignKeysForEditor(tableDetails.foreignKeyList).map((foreignKey) => ({
      ...foreignKey,
      key: uuidv4(),
      columnList: foreignKey.columnList.map((column) => ({ ...column, key: uuidv4() })),
    }));
    setDataSource(data);
    setEditingData(null);
  }, [tableDetails]);

  useEffect(() => {
    if (!dataSourceId || !databaseName) {
      return;
    }
    sqlService
      .getAllTableList({ dataSourceId, databaseName, schemaName })
      .then((tables) => {
        setTableOptions((tables || []).map((table) => ({ label: table.name, value: table.name })));
      });
  }, [dataSourceId, databaseName, schemaName]);

  useEffect(() => {
    if (!dataSourceId || !editingData?.pkTableName) {
      setReferencedColumns([]);
      return;
    }
    sqlService
      .getAllFieldByTable({
        dataSourceId,
        databaseName,
        schemaName,
        tableName: editingData.pkTableName,
      })
      .then((columns) => {
        setReferencedColumns((columns || []).map((column) => ({ label: column.name, value: column.name })));
      });
  }, [dataSourceId, databaseName, schemaName, editingData?.pkTableName]);

  const localColumnOptions = useMemo(() => {
    const columns: IColumnItemNew[] = columnListRef.current?.getColumnListInfo()?.filter((column) => column.name) || [];
    return columns.map((column) => ({ label: column.name!, value: column.name! }));
  }, [tableDetails, columnModalOpen]);

  const isEditing = (record: IForeignKeyItem) => record.key === editingData?.key;

  const edit = (record: IForeignKeyItem) => {
    form.setFieldsValue({ ...record });
    if (record.key !== editingData?.key) {
      setEditingData(record || null);
    }
  };

  const markChanged = (item: IForeignKeyItem): IForeignKeyItem => ({
    ...item,
    editStatus:
      item.editStatus === EditColumnOperationType.Add ? EditColumnOperationType.Add : EditColumnOperationType.Modify,
  });

  const handleFieldsChange = (field: any) => {
    const { value } = field[0];
    const { name: nameList } = field[0];
    const name = nameList[0];

    setDataSource((previous) =>
      previous.map((item) => {
        if (item.key !== editingData?.key) {
          return item;
        }
        const nextItem = markChanged({
          ...item,
          [name]: value,
        });
        setEditingData(nextItem);
        return nextItem;
      }),
    );
  };

  const addData = () => {
    const newData = createInitialData();
    setDataSource([...dataSource, newData]);
    edit(newData);
    setTimeout(() => {
      tableRef.current?.scrollTo({ top: 99999999 });
    }, 0);
  };

  const deleteData = (record: IForeignKeyItem) => {
    setDataSource((previous) =>
      previous
        .map((item) => {
          if (item.key !== record.key) {
            return item;
          }
          setEditingData(null);
          if (item.editStatus === EditColumnOperationType.Add) {
            return null;
          }
          return {
            ...item,
            editStatus: EditColumnOperationType.Delete,
          };
        })
        .filter(Boolean) as IForeignKeyItem[],
    );
  };

  const openColumnModal = (record: IForeignKeyItem) => {
    edit(record);
    setColumnMappings(record.columnList.map((column) => ({ ...column, key: uuidv4() })));
    setColumnModalOpen(true);
  };

  const addColumnMapping = () => {
    setColumnMappings([
      ...columnMappings,
      {
        key: uuidv4(),
        fkColumnName: null,
        pkColumnName: null,
        keySeq: columnMappings.length + 1,
      },
    ]);
  };

  const deleteColumnMapping = (record: IForeignKeyColumnMappingItem) => {
    setColumnMappings(
      columnMappings
        .filter((column) => column.key !== record.key)
        .map((column, index) => ({ ...column, keySeq: index + 1 })),
    );
  };

  const changeColumnMapping = (
    record: IForeignKeyColumnMappingItem,
    name: keyof IForeignKeyColumnMappingItem,
    value: string | null,
  ) => {
    setColumnMappings(
      columnMappings.map((column) => {
        if (column.key !== record.key) {
          return column;
        }
        return {
          ...column,
          [name]: value,
        };
      }),
    );
  };

  const saveColumnMappings = () => {
    setDataSource((previous) =>
      previous.map((item) => {
        if (item.key !== editingData?.key) {
          return item;
        }
        const nextItem = markChanged({
          ...item,
          columnList: columnMappings.map((column, index) => ({
            fkColumnName: column.fkColumnName,
            pkColumnName: column.pkColumnName,
            keySeq: index + 1,
          })),
        });
        setEditingData(nextItem);
        form.setFieldsValue(nextItem);
        return nextItem;
      }),
    );
    setColumnModalOpen(false);
  };

  const onDragEnd = ({ active, over }: DragEndEvent) => {
    if (active.id !== over?.id) {
      setDataSource((previous) => {
        const activeIndex = previous.findIndex((item) => item.key === active.id);
        const overIndex = previous.findIndex((item) => item.key === over?.id);
        return arrayMove(previous, activeIndex, overIndex);
      });
    }
  };

  function getForeignKeyListInfo(): IForeignKeyListInfo {
    return flattenForeignKeysForSubmit(dataSource, tableDetails, databaseBaseInfo);
  }

  useImperativeHandle(ref, () => ({
    getForeignKeyListInfo,
  }));

  const columns = useMemo(
    () => [
      {
        key: 'sort',
        width: '40px',
        align: 'center',
        fixed: 'left',
      },
      {
        title: i18n('editTable.label.foreignKeyName'),
        dataIndex: 'fkName',
        width: '180px',
        fixed: 'left',
        render: (text: string, record: IForeignKeyItem) =>
          isEditing(record) ? (
            <Form.Item name="fkName" style={{ margin: 0 }}>
              <Input autoComplete="off" />
            </Form.Item>
          ) : (
            <div className={styles.editableCell}>{text}</div>
          ),
      },
      {
        title: i18n('editTable.label.localColumns'),
        dataIndex: 'columnList',
        width: '220px',
        render: (columnList: IForeignKeyColumnMappingItem[], record: IForeignKeyItem) => (
          <div className={styles.columnListCell}>
            {isEditing(record) && (
              <span onClick={() => openColumnModal(record)}>{i18n('common.button.edit')}</span>
            )}
            {formatColumnList(columnList, 'fkColumnName')}
          </div>
        ),
      },
      {
        title: i18n('editTable.label.referencedTable'),
        dataIndex: 'pkTableName',
        width: '180px',
        render: (text: string, record: IForeignKeyItem) =>
          isEditing(record) ? (
            <Form.Item name="pkTableName" style={{ margin: 0 }}>
              <Select showSearch allowClear options={tableOptions} />
            </Form.Item>
          ) : (
            <div className={styles.editableCell}>{text}</div>
          ),
      },
      {
        title: i18n('editTable.label.referencedColumns'),
        dataIndex: 'columnList',
        width: '220px',
        render: (columnList: IForeignKeyColumnMappingItem[]) => (
          <div className={styles.editableCell}>{formatColumnList(columnList, 'pkColumnName')}</div>
        ),
      },
      {
        title: i18n('editTable.label.onDelete'),
        dataIndex: 'deleteRule',
        width: '180px',
        render: (text: number, record: IForeignKeyItem) =>
          isEditing(record) ? (
            <Form.Item name="deleteRule" style={{ margin: 0 }}>
              <Select options={foreignKeyActionOptions} />
            </Form.Item>
          ) : (
            <div className={styles.editableCell}>
              {foreignKeyActionOptions.find((option) => option.value === text)?.label || text}
            </div>
          ),
      },
      {
        title: i18n('editTable.label.onUpdate'),
        dataIndex: 'updateRule',
        width: '180px',
        render: (text: number, record: IForeignKeyItem) =>
          isEditing(record) ? (
            <Form.Item name="updateRule" style={{ margin: 0 }}>
              <Select options={foreignKeyActionOptions} />
            </Form.Item>
          ) : (
            <div className={styles.editableCell}>
              {foreignKeyActionOptions.find((option) => option.value === text)?.label || text}
            </div>
          ),
      },
      {
        width: '40px',
        render: (_text: string, record: IForeignKeyItem) => (
          <div className={styles.operationBar} onClick={() => deleteData(record)}>
            <div className={styles.deleteIconBox}>
              <Iconfont code="&#xe64e;" />
            </div>
          </div>
        ),
      },
    ],
    [dataSource, editingData, foreignKeyActionOptions, tableOptions],
  );

  const mappingColumns = [
    {
      title: i18n('editTable.label.index'),
      dataIndex: 'keySeq',
      width: '60px',
    },
    {
      title: i18n('editTable.label.localColumns'),
      dataIndex: 'fkColumnName',
      render: (text: string, record: IForeignKeyColumnMappingItem) => (
        <Select
          showSearch
          allowClear
          style={{ width: '100%' }}
          options={localColumnOptions}
          value={text}
          onChange={(value) => changeColumnMapping(record, 'fkColumnName', value)}
        />
      ),
    },
    {
      title: i18n('editTable.label.referencedColumns'),
      dataIndex: 'pkColumnName',
      render: (text: string, record: IForeignKeyColumnMappingItem) => (
        <Select
          showSearch
          allowClear
          style={{ width: '100%' }}
          options={referencedColumns}
          value={text}
          onChange={(value) => changeColumnMapping(record, 'pkColumnName', value)}
        />
      ),
    },
    {
      width: '40px',
      render: (_text: string, record: IForeignKeyColumnMappingItem) => (
        <div className={styles.operationBar} onClick={() => deleteColumnMapping(record)}>
          <div className={styles.deleteIconBox}>
            <Iconfont code="&#xe64e;" />
          </div>
        </div>
      ),
    },
  ];

  const onRow = (record: IForeignKeyItem) => ({
    onClick: () => {
      if (editingData?.key !== record.key) {
        edit(record);
      }
    },
  });

  return (
    <div className={classnames(styles.container)}>
      <Form className={styles.formBox} form={form} onFieldsChange={handleFieldsChange}>
        <div className={styles.tableBox} ref={tableBoxRef}>
          <Alert
            type="warning"
            showIcon
            message={i18n('editTable.foreignKey.integrityWarning')}
            style={{ marginBottom: 8 }}
          />
          <DndContext modifiers={[restrictToVerticalAxis]} onDragEnd={onDragEnd}>
            <SortableContext items={dataSource.map((item) => item.key!)} strategy={verticalListSortingStrategy}>
              <Table
                ref={tableRef as any}
                components={{
                  body: {
                    row: Row,
                  },
                }}
                sticky
                onRow={onRow}
                pagination={false}
                rowKey="key"
                columns={columns as any}
                scroll={{ x: '100%', y: tableScrollY }}
                dataSource={dataSource.filter((item) => item.editStatus !== EditColumnOperationType.Delete)}
              />
            </SortableContext>
          </DndContext>
          <div onClick={addData} className={styles.addColumnButton}>
            <Iconfont code="&#xe631;" />
            {i18n('editTable.button.addForeignKey')}
          </div>
        </div>
      </Form>
      <Modal
        open={columnModalOpen}
        width={720}
        title={i18n('editTable.label.foreignKeyColumns')}
        onOk={saveColumnMappings}
        onCancel={() => setColumnModalOpen(false)}
        maskClosable={false}
        destroyOnClose={true}
      >
        <Table
          pagination={false}
          rowKey="key"
          columns={mappingColumns as any}
          dataSource={columnMappings}
          footer={() => (
            <div onClick={addColumnMapping} className={styles.addColumnButton}>
              <Iconfont code="&#xe631;" />
              {i18n('editTable.button.addColumn')}
            </div>
          )}
        />
      </Modal>
    </div>
  );
});

export default React.memo(ForeignKeyList);
