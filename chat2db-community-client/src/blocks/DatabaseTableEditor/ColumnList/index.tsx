import React, {
  useContext,
  useEffect,
  useState,
  useRef,
  forwardRef,
  ForwardedRef,
  useImperativeHandle,
  useMemo,
  useCallback,
} from 'react';
import { MenuOutlined } from '@ant-design/icons';
import { DndContext, type DragEndEvent } from '@dnd-kit/core';
import { restrictToVerticalAxis } from '@dnd-kit/modifiers';
import { Table, InputNumber, Input, Form, Select, Checkbox } from 'antd';
import { v4 as uuidv4 } from 'uuid';
import { arrayMove, SortableContext, useSortable, verticalListSortingStrategy } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { Context } from '../index';
import { IColumnItemNew, IColumnTypes } from '@/typings';
import i18n from '@/i18n';
import { EditColumnOperationType, NullableType } from '@/constants';
import { isSqliteExistingColumnReadonly, shouldShowSqlServerSparse } from '@/utils/databaseJudgments';
import CustomSelect from '@/components/CustomSelect';
import Iconfont from '@/components/Iconfont';
import { useStyles } from './style';
import { Resizable } from 'react-resizable';
import 'react-resizable/css/styles.css';
import { normalizeColumnForSubmit } from './normalizeColumn';

interface RowProps extends React.HTMLAttributes<HTMLTableRowElement> {
  'data-row-key': string;
}

interface IProps {}

// Edit configuration
interface IEditingConfig extends IColumnTypes {
  editKey: string;
}

// The method exposed by this component to the parent component
export interface IColumnListRef {
  getColumnListInfo: () => IColumnItemNew[];
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

// creates an empty data structure
const createInitialData = () => {
  return {
    key: uuidv4(),
    oldName: null,
    name: null,
    tableName: null,
    columnType: null,
    dataType: null,
    defaultValue: null,
    autoIncrement: null,
    comment: null,
    primaryKey: null,
    primaryKeyOrder: null,
    schemaName: null,
    databaseName: null,
    typeName: null,
    columnSize: null,
    bufferLength: null,
    decimalDigits: null,
    numPrecRadix: null,
    nullableInt: null,
    sqlDataType: null,
    sqlDatetimeSub: null,
    charOctetLength: null,
    ordinalPosition: null,
    nullable: null,
    generatedColumn: null,
    charSetName: null,
    collationName: null,
    value: null,
    editStatus: EditColumnOperationType.Add,
  };
};

const ColumnList = forwardRef((props: IProps, ref: ForwardedRef<IColumnListRef>) => {
  const { styles, cx } = useStyles();
  const {
    databaseSupportField,
    databaseBaseInfo: { databaseType, databaseName, schemaName },
    tableDetails,
  } = useContext(Context);
  const [dataSource, setDataSource] = useState<IColumnItemNew[]>([createInitialData()]);
  const [form] = Form.useForm();
  const [editingData, setEditingData] = useState<IColumnItemNew | null>(null);
  const [editingConfig, setEditingConfig] = useState<IEditingConfig | null>(null);
  const tableRef = useRef<any>(null);
  const tableBoxRef = useRef<any>(null);
  const [tableScrollY, setTableScrollY] = useState(0);
  // column width array
  const [columnsWidth, setColumnsWidth] = useState([40, 160, 200, 120, 100, 50, undefined, 40]);

  // monitors the height change of tableBoxRef and sets tableScrollY. You need to consider the resize situation.
  useEffect(() => {
    const resizeObserver = new ResizeObserver((entries) => {
      for (const entry of entries) {
        const { height } = entry.contentRect;
        setTableScrollY(height - 82);
      }
    });

    if (tableBoxRef.current) {
      resizeObserver.observe(tableBoxRef.current);
    }

    return () => {
      resizeObserver.disconnect();
    };
  }, [tableBoxRef.current]);

  const ResizableTitle = useCallback((titleProps) => {
    const { onResize, width, ...restProps } = titleProps;

    if (!width) {
      return <th {...restProps} />;
    }

    return (
      <Resizable
        width={width}
        height={0}
        handle={<span className="react-resizable-handle" />}
        onResize={onResize}
        draggableOpts={{ enableUserSelectHack: false }}
      >
        <th {...restProps} />
      </Resizable>
    );
  }, []);

  const edit = (record: IColumnItemNew) => {
    if (record.key) {
      setEditingData(record);
      form.resetFields();
      form.setFieldsValue({ ...record });
      // Set the editing configuration according to the current field type
      databaseSupportField.columnTypes.forEach((i) => {
        if (i.typeName === record.columnType) {
          setEditingConfig({
            ...i,
            editKey: record.key!,
          });
        }
      });
    }
  };

  // organizes the data returned by the server and constructs it into the data structure required by the front end.
  useEffect(() => {
    if (tableDetails) {
      const list =
        tableDetails?.columnList?.map((t) => {
          return {
            ...t,
            oldName: t.name,
            key: uuidv4(),
          };
        }) || [];
      setEditingConfig(null);
      setDataSource(list);
    }
  }, [tableDetails]);

  const columns = useMemo(() => {
    const isEditing = (record: IColumnItemNew) => record.key === editingData?.key;
    return [
      {
        key: 'sort',
        width: columnsWidth[0],
        align: 'center',
        fixed: 'left',
      },
      {
        title: i18n('editTable.label.columnName'),
        dataIndex: 'name',
        width: columnsWidth[1],
        fixed: 'left',
        render: (text: string, record: IColumnItemNew) => {
          const editable = isEditing(record);
          return (
            <div>
              {editable ? (
                <Form.Item name="name" style={{ margin: 0 }}>
                  <Input className={styles.columnNameInput} autoComplete="off" />
                </Form.Item>
              ) : (
                <div className={styles.editableCell}>{text}</div>
              )}
            </div>
          );
        },
      },
      {
        title: i18n('editTable.label.columnType'),
        dataIndex: 'columnType',
        width: columnsWidth[2],
        render: (text: string, record: IColumnItemNew) => {
          const editable = isEditing(record);
          return (
            <div>
              {editable ? (
                <Form.Item name="columnType" style={{ margin: 0, maxWidth: '184px' }}>
                  <Select showSearch options={databaseSupportField.columnTypes} />
                </Form.Item>
              ) : (
                <div style={{ maxWidth: '184px' }} className={styles.editableCell}>
                  {text}
                </div>
              )}
            </div>
          );
        },
      },
      {
        title: i18n('editTable.label.columnSize'),
        dataIndex: 'columnSize',
        width: columnsWidth[3],
        render: (text: string, record: IColumnItemNew) => {
          const editable = isEditing(record);
          return editable ? (
            <Form.Item name="columnSize" style={{ margin: 0 }}>
              <InputNumber className={styles.inputNumber} disabled={!editingConfig?.supportLength} />
            </Form.Item>
          ) : (
            <div className={styles.editableCell}>{text}</div>
          );
        },
      },
      {
        title: i18n('editTable.label.nullable'),
        dataIndex: 'nullable',
        width: columnsWidth[4],
        render: (nullable: NullableType | null, record: IColumnItemNew) => {
          // const editable = isEditing(record);
          return (
            <div>
              <Checkbox
                onChange={() => {
                  if (isSqliteExistingColumnReadonly(databaseType, record.editStatus)) {
                    return null;
                  }
                  handelNullable(record);
                }}
                checked={nullable === NullableType.Null}
                disabled={
                  editingConfig?.supportNullable === false ||
                  !!record.primaryKey ||
                  isSqliteExistingColumnReadonly(databaseType, record.editStatus)
                }
              />
            </div>
          );
        },
      },
      {
        title: i18n('editTable.label.primaryKey'),
        dataIndex: 'primaryKey',
        width: columnsWidth[5],
        render: (primaryKey: boolean, record: IColumnItemNew) => {
          return (
            <div
              className={cx(styles.keyBox)}
              onClick={() => {
                if (isSqliteExistingColumnReadonly(databaseType, record.editStatus)) {
                  return null;
                }
                handelPrimaryKey(record);
              }}
            >
              {primaryKey && <Iconfont code="&#xe775;" />}
              {primaryKey && <span>{record.primaryKeyOrder}</span>}
            </div>
          );
        },
      },
      {
        title: i18n('editTable.label.comment'),
        dataIndex: 'comment',
        render: (text: string, record: IColumnItemNew) => {
          const editable = isEditing(record);
          return editable ? (
            <Form.Item name="comment" style={{ margin: 0 }}>
              <Input autoComplete="off" disabled={!editingConfig?.supportComments} />
            </Form.Item>
          ) : (
            <div className={styles.editableCell}>{text}</div>
          );
        },
      },
      {
        width: columnsWidth[7],
        render: (text: string, record: IColumnItemNew) => {
          // sqlLite does not support deleting fields. New fields can be deleted.
          if (isSqliteExistingColumnReadonly(databaseType, record.editStatus)) {
            return null;
          }
          return (
            <div
              className={styles.operationBar}
              onClick={() => {
                deleteData(record);
              }}
            >
              <div className={styles.deleteIconBox}>
                <Iconfont code="&#xe64e;" />
              </div>
            </div>
          );
        },
      },
    ];
  }, [columnsWidth, editingData, editingConfig, databaseType, dataSource]);

  const handleResize = useCallback(
    (index) =>
      (e, { size }) => {
        if (typeof size.width === 'number' && size.width > 40) {
          setColumnsWidth((prev) => {
            const nextColumns = [...prev];
            nextColumns[index] = size.width;
            return nextColumns;
          });
        }
      },
    [],
  );

  const columnsWithResize = useMemo(() => {
    return columns.map((col, index) => ({
      ...col,
      onHeaderCell: (column) => {
        return {
          width: column.width,
          onResize: handleResize(index),
        };
      },
    }));
  }, [columns]);

  const handelPrimaryKey = (_data: IColumnItemNew) => {
    const newData = dataSource.map((item) => {
      let primaryKeyOrder: null | number = item.primaryKeyOrder;

      // Cancel primary key if
      if (_data.primaryKey) {
        // If the current field is canceled, the primary key sequence is null
        if (_data.key === item.key) {
          primaryKeyOrder = null;
        } else {
          // When removing this primary key, shift every following primary-key position down by one.
          if (_data.primaryKeyOrder && item.primaryKeyOrder && item.primaryKeyOrder >= _data.primaryKeyOrder) {
            primaryKeyOrder = item.primaryKeyOrder - 1;
          }
        }
      } else {
        // Add primary key if
        // When adding a primary key, the primary key order is the maximum primary key order of the current table + 1
        if (_data.key === item.key) {
          primaryKeyOrder =
            Math.max(
              ...dataSource.map((i) => {
                return i.primaryKeyOrder || 0;
              }),
            ) + 1;
        }
        // For the fields before the current field, the primary key order remains unchanged.
      }

      if (item.key === _data?.key) {
        // Determine whether the current data is new data or edited data
        let editStatus = item.editStatus;
        if (editStatus !== EditColumnOperationType.Add) {
          editStatus = EditColumnOperationType.Modify;
        }

        const editingDataItem = {
          ...item,
          primaryKey: !item.primaryKey,
          primaryKeyOrder,
          nullable: !item.primaryKey ? NullableType.NotNull : item.nullable,
          editStatus,
        };
        return editingDataItem;
      }

      return {
        ...item,
        primaryKeyOrder,
      };
    });
    setDataSource(newData);
  };

  const handelNullable = (_data: IColumnItemNew) => {
    const newData = dataSource.map((item) => {
      if (item.key === _data?.key) {
        // Determine whether the current data is new data or edited data
        let editStatus = item.editStatus;
        if (editStatus !== EditColumnOperationType.Add) {
          editStatus = EditColumnOperationType.Modify;
        }
        const editingDataItem = {
          ...item,
          nullable: !item.nullable ? NullableType.Null : NullableType.NotNull,
          editStatus,
        };
        return editingDataItem;
      }
      return item;
    });
    setDataSource(newData);
  };

  const onDragEnd = ({ active, over }: DragEndEvent) => {
    if (active.id !== over?.id) {
      setDataSource((previous) => {
        const activeIndex = previous.findIndex((i) => i.key === active.id);
        const overIndex = previous.findIndex((i) => i.key === over?.id);
        return arrayMove(previous, activeIndex, overIndex);
      });
    }
  };

  const handleFieldsChange = (field: any) => {
    let { value } = field[0];
    const { name: nameList } = field[0];
    const name = nameList[0];

    if (name === 'nullable') {
      value = value ? NullableType.Null : NullableType.NotNull;
    }

    setDataSource((prv) => {
      return prv.map((item) => {
        if (item.key === editingData?.key) {
          // Determine whether the current data is new data or edited data
          let editStatus = item.editStatus;
          if (editStatus !== EditColumnOperationType.Add) {
            editStatus = EditColumnOperationType.Modify;
          }
          const editingDataItem = {
            ...item,
            [name]: value,
            editStatus,
          };

          if (name === 'columnType') {
            // Set the editing configuration according to the current field type
            databaseSupportField.columnTypes.forEach((i) => {
              if (i.typeName === value) {
                setEditingConfig({
                  ...editingConfig!,
                  ...i,
                });
              }
            });
            // Special processing The default length of VARCHAR is 255
            if (value === 'VARCHAR' && editingDataItem.columnSize === null) {
              editingDataItem.columnSize = 255;
              form.setFieldsValue({
                columnSize: 255,
              });
            }
          }
          return editingDataItem;
        }
        return item;
      });
    });
  };

  const addData = () => {
    const newData = {
      ...createInitialData(),
    };
    setDataSource([...dataSource, newData]);
    edit(newData);
    setTimeout(() => {
      tableRef.current.scrollTo({
        // There will be a bug when using key here, and the entire table will move upward.
        // key: newData.key,
        top: 99999999,
      });
    }, 0);
  };

  const deleteData = (record) => {
    let list: any = [];
    if (record?.editStatus === EditColumnOperationType.Add) {
      list = dataSource.filter((i) => i.key !== record?.key);
    } else {
      list = dataSource.map((i) => {
        if (i.key === record?.key) {
          setEditingData(null);
          setEditingConfig(null);
          return {
            ...i,
            editStatus: EditColumnOperationType.Delete,
          };
        }
        return i;
      });
    }
    setDataSource(list);
  };

  function getColumnListInfo(): IColumnItemNew[] {
    return dataSource.map((i) => {
      const data = {
        ...normalizeColumnForSubmit(i),
        tableName: tableDetails?.name,
        databaseName: databaseName || null,
        schemaName: schemaName || null,
      };
      delete data.key;
      return data;
    });
  }

  useImperativeHandle(ref, () => ({
    getColumnListInfo,
  }));

  const renderOtherInfoForm = () => {
    const labelCol = {
      style: { width: 100 },
    };

    return (
      <>
        {editingConfig?.supportAutoIncrement && (
          <Form.Item
            labelCol={labelCol}
            className={styles.checkboxContainer}
            name="autoIncrement"
            valuePropName="checked"
          >
            <Checkbox>{i18n('editTable.label.autoIncrement')}</Checkbox>
          </Form.Item>
        )}
        {shouldShowSqlServerSparse(databaseType) && (
          <Form.Item className={styles.checkboxContainer} labelCol={labelCol} name="sparse" valuePropName="checked">
            <Checkbox>{i18n('editTable.label.sparse')}</Checkbox>
          </Form.Item>
        )}
        {editingConfig?.supportDefaultValue && (
          <Form.Item labelCol={labelCol} label={i18n('editTable.label.defaultValue')} name="defaultValue">
            <CustomSelect options={databaseSupportField.defaultValues} />
          </Form.Item>
        )}
        {editingConfig?.supportOnUpdateCurrentTimestamp && (
          <Form.Item
            className={styles.checkboxContainer}
            labelCol={labelCol}
            name="onUpdateCurrentTimestamp"
            valuePropName="checked"
          >
            <Checkbox>{i18n('editTable.label.updateTime')}</Checkbox>
          </Form.Item>
        )}
        {editingConfig?.supportCharset && (
          <Form.Item labelCol={labelCol} label={i18n('editTable.label.characterSet')} name="charSetName">
            <CustomSelect options={databaseSupportField.charsets} />
          </Form.Item>
        )}
        {editingConfig?.supportCollation && (
          <Form.Item labelCol={labelCol} label={i18n('editTable.label.collation')} name="collationName">
            <CustomSelect options={databaseSupportField.collations} />
          </Form.Item>
        )}
        {editingConfig?.supportScale && (
          <Form.Item labelCol={labelCol} label={i18n('editTable.label.decimalPoint')} name="decimalDigits">
            <Input autoComplete="off" />
          </Form.Item>
        )}
        {editingConfig?.supportUnit && (
          <Form.Item labelCol={labelCol} label={i18n('editTable.label.unit')} name="unit">
            <Select style={{ width: '100%' }}>
              {['CHAR', 'BYTE'].map((i) => (
                <Select.Option key={i} value={i}>
                  {i}
                </Select.Option>
              ))}
            </Select>
          </Form.Item>
        )}
        {editingConfig?.supportValue && (
          <Form.Item labelCol={labelCol} label={i18n('editTable.label.value')} name="value">
            <Input autoComplete="off" />
          </Form.Item>
        )}
      </>
    );
  };

  const onRow = (record: any) => {
    return {
      onClick: () => {
        // sqlLite does not support modifying fields. New fields can be modified.
        if (isSqliteExistingColumnReadonly(databaseType, record.editStatus)) {
          return;
        }
        if (editingData?.key !== record.key) {
          edit(record);
        }
      },
    };
  };

  return (
    <div className={styles.container}>
      {/* <div className={styles.columnListHeader}>
        <Button onClick={addData}>{i18n('editTable.button.add')}</Button>
        <Button onClick={deleteData}>{i18n('editTable.button.delete')}</Button>
        <Button onClick={moveData.bind(null, 'up')}>{i18n('editTable.button.up')}</Button>
        <Button onClick={moveData.bind(null, 'down')}>{i18n('editTable.button.down')}</Button>
      </div> */}
      <Form className={styles.formBox} form={form} autoComplete="off" onFieldsChange={handleFieldsChange}>
        <div className={styles.tableBox} ref={tableBoxRef}>
          <DndContext modifiers={[restrictToVerticalAxis]} onDragEnd={onDragEnd}>
            <SortableContext items={dataSource.map((i) => i.key!)} strategy={verticalListSortingStrategy}>
              <Table
                ref={tableRef}
                components={{
                  header: {
                    cell: ResizableTitle,
                  },
                  body: {
                    row: Row,
                  },
                }}
                style={{
                  maxHeight: '100%',
                  overflow: 'auto',
                }}
                sticky
                onRow={onRow}
                pagination={false}
                rowKey="key"
                columns={columnsWithResize as any}
                scroll={{ x: '100%', y: tableScrollY }}
                dataSource={dataSource.filter((i) => i.editStatus !== EditColumnOperationType.Delete)}
              />
            </SortableContext>
          </DndContext>
          <div onClick={addData} className={styles.addColumnButton}>
            <Iconfont code="&#xe631;" />
            {i18n('editTable.button.addColumn')}
          </div>
        </div>

        <div className={styles.otherInfo}>
          <div className={styles.otherInfoFormBox}>{renderOtherInfoForm()}</div>
        </div>
      </Form>
    </div>
  );
});

export default React.memo(ColumnList);
