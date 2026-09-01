/**
 * This component is only responsible for getting the table name selected by the user.
 *  */
import {
  useMemo,
  useState,
  useRef,
  useContext,
  useEffect,
  forwardRef,
  ForwardedRef,
  useImperativeHandle,
} from 'react';
import classnames from 'classnames';
import { Table, Form, Select, Button, Input, InputNumber } from 'antd';
import { v4 as uuidv4 } from 'uuid';
import { Context } from '../index';
import { IColumnItemNew, IIndexIncludeColumnItem } from '@/typings';
import { shouldShowSqliteIncludeCollation } from '@/utils/databaseJudgments';
import i18n from '@/i18n';
import Iconfont from '@/components/Iconfont';
import { useStyles } from '../ColumnList/style';
import {
  applyIndexColumnKind,
  getEditableIndexColumns,
  getIndexColumnKind,
  IEditableIndexIncludeColumnItem,
  IndexColumnKind,
  normalizeIndexIncludeColumn,
  supportsMysqlExpressionIndex,
  validateMysqlExpressionIndexRows,
} from './model';

interface IProps {
  includedColumnList: IIndexIncludeColumnItem[];
}

const createInitialData = (): IEditableIndexIncludeColumnItem => {
  return {
    key: uuidv4(),
    indexColumnKind: IndexColumnKind.COLUMN,
    ascOrDesc: null, // ascending or descending order
    cardinality: null, // base
    collation: null, // sorting rules
    columnName: null, // Listed
    comment: null, // Comments
    filterCondition: null, // filter conditions
    indexName: null, // index name
    indexQualifier: null, // index qualifier
    nonUnique: null, // unique?
    ordinalPosition: null, // location
    schemaName: null, // mode name
    type: null, // type
    pages: null, // Pages

    databaseName: null, // database name
    tableName: null, // table name
    subPart: null,
    expression: null,
  };
};

export interface IIncludeColRef {
  getIncludeColInfo: () => IIndexIncludeColumnItem[];
}

const IncludeCol = forwardRef((props: IProps, ref: ForwardedRef<IIncludeColRef>) => {
  const { includedColumnList } = props;
  const { styles } = useStyles();
  const {
    columnListRef,
    databaseBaseInfo: { databaseType, dbVersion },
    tableDetails,
  } = useContext(Context);
  const [dataSource, setDataSource] = useState<IEditableIndexIncludeColumnItem[]>([createInitialData()]);
  const [form] = Form.useForm();
  const [editingKey, setEditingKey] = useState<string | null>(null);
  const isEditing = (record: IIndexIncludeColumnItem) => record.key === editingKey;
  const tableRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (includedColumnList.length) {
      setDataSource(
        includedColumnList.map((t) => {
          return {
            ...t,
            key: uuidv4(),
            indexColumnKind: getIndexColumnKind(t),
          };
        }),
      );
    }
  }, [includedColumnList]);

  const columnList: IColumnItemNew[] = useMemo(() => {
    const columnListInfo = columnListRef.current?.getColumnListInfo() || tableDetails.columnList || [];
    return getEditableIndexColumns(columnListInfo);
  }, [columnListRef, tableDetails]);

  const expressionIndexSupported = supportsMysqlExpressionIndex(databaseType, dbVersion || tableDetails.dbVersion);

  const edit = (record: any) => {
    form.setFieldsValue({ ...record });
    setEditingKey(record.key || null);
  };

  const addData = () => {
    const newData = createInitialData();
    setDataSource([...dataSource, newData]);
    edit(newData);
    setTimeout(() => {
      tableRef.current?.scrollTo({
        top: 999999999,
      });
    }, 0);
  };

  const deleteData = (record) => {
    setDataSource(dataSource.filter((i) => i.key !== record.key));
  };

  const columns = [
    {
      title: i18n('editTable.label.index'),
      dataIndex: 'index',
      width: '50px',
      align: 'center',
      render: (text: string, record: IEditableIndexIncludeColumnItem) => {
        return dataSource.findIndex((i) => i.key === record.key) + 1;
      },
    },
    ...(expressionIndexSupported
      ? [
          {
            title: i18n('editTable.label.indexColumnKind'),
            dataIndex: 'indexColumnKind',
            width: '120px',
            render: (text: IndexColumnKind, record: IEditableIndexIncludeColumnItem) => {
              const editable = isEditing(record);
              return editable ? (
                <Form.Item name="indexColumnKind" style={{ margin: 0 }}>
                  <Select
                    options={[
                      { label: i18n('editTable.label.columnName'), value: IndexColumnKind.COLUMN },
                      { label: i18n('editTable.label.expression'), value: IndexColumnKind.EXPRESSION },
                    ]}
                  />
                </Form.Item>
              ) : (
                <div className={styles.editableCell} onClick={() => edit(record)}>
                  {text === IndexColumnKind.EXPRESSION
                    ? i18n('editTable.label.expression')
                    : i18n('editTable.label.columnName')}
                </div>
              );
            },
          },
        ]
      : []),
    {
      title: i18n('editTable.label.columnName'),
      dataIndex: 'columnName',
      // width: '45%',
      render: (text: string, record: IEditableIndexIncludeColumnItem) => {
        const editable = isEditing(record);
        if (getIndexColumnKind(record) === IndexColumnKind.EXPRESSION) {
          return editable ? (
            <Form.Item
              name="expression"
              style={{ margin: 0 }}
              rules={[
                {
                  validator: async (_, value) => {
                    if (!value || !validateMysqlExpressionIndexRows([{ ...record, expression: value }])) {
                      return;
                    }
                    throw new Error(i18n('editTable.validation.invalidMysqlExpressionIndex'));
                  },
                },
              ]}
            >
              <Input autoComplete="off" placeholder="lower(`email`)" />
            </Form.Item>
          ) : (
            <div className={styles.editableCell} onClick={() => edit(record)}>
              {`(${record.expression})`}
            </div>
          );
        }
        return editable ? (
          <Form.Item name="columnName" style={{ margin: 0 }}>
            <Select options={columnList.map((i) => ({ label: i.name, value: i.name }))} />
          </Form.Item>
        ) : (
          <div className={styles.editableCell} onClick={() => edit(record)}>
            {text}
          </div>
        );
      },
    },
    {
      title: i18n('editTable.label.prefixLength'),
      dataIndex: 'subPart',
      width: '110px',
      render: (text: number | null, record: IEditableIndexIncludeColumnItem) => {
        const editable = isEditing(record);
        if (getIndexColumnKind(record) === IndexColumnKind.EXPRESSION) {
          return <div className={styles.editableCell} />;
        }
        return editable ? (
          <Form.Item name="subPart" style={{ margin: 0 }}>
            <InputNumber min={1} precision={0} style={{ width: '100%' }} />
          </Form.Item>
        ) : (
          <div className={styles.editableCell} onClick={() => edit(record)}>
            {text}
          </div>
        );
      },
    },
    {
      title: i18n('editTable.label.order'),
      dataIndex: 'ascOrDesc',
      render: (text: string, record: IEditableIndexIncludeColumnItem) => {
        const editable = isEditing(record);
        return editable ? (
          <Form.Item name="ascOrDesc" style={{ margin: 0 }}>
            <Select
              options={[
                { label: 'ASC', value: 'ASC' },
                { label: 'DESC', value: 'DESC' },
              ]}
            />
          </Form.Item>
        ) : (
          <div className={styles.editableCell} onClick={() => edit(record)}>
            {text}
          </div>
        );
      },
    },
    {
      width: '40px',
      render: (text: string, record: IEditableIndexIncludeColumnItem) => {
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

    // {
    //   title: i18n('editTable.label.prefixLength'),
    //   dataIndex: 'prefixLength',
    //   width: '45%',
    //   render: (text: string, record: IIndexIncludeColumnItem) => {
    //     const editable = isEditing(record);
    //     return editable ? (
    //       <Form.Item name="prefixLength" style={{ margin: 0 }}>
    //         <InputNumber style={{ width: '100%' }} />
    //       </Form.Item>
    //     ) : (
    //       <div className={styles.editableCell} onClick={() => edit(record)}>
    //         {text}
    //       </div>
    //     );
    //   },
    // },
  ];
  // sqlLite Add sorting rules
  if (shouldShowSqliteIncludeCollation(databaseType)) {
    columns.splice(2, 0, {
      title: i18n('editTable.label.collation'),
      dataIndex: 'collation',
      render: (text: string, record: IEditableIndexIncludeColumnItem) => {
        const editable = isEditing(record);
        return editable ? (
          <Form.Item name="collation" style={{ margin: 0 }}>
            <Select
              options={[
                { label: 'BINARY', value: 'BINARY' },
                { label: 'NOCASE', value: 'NOCASE' },
                { label: 'RTRIM', value: 'RTRIM' },
              ]}
            />
          </Form.Item>
        ) : (
          <div className={styles.editableCell} onClick={() => edit(record)}>
            {text}
          </div>
        );
      },
    });
  }

  const handleFieldsChange = (field: any) => {
    const { value } = field[0];
    const { name: nameList } = field[0];
    const name = nameList[0];
    const newData = dataSource.map((item) => {
      if (item.key === editingKey) {
        if (name === 'indexColumnKind') {
          return applyIndexColumnKind(item, value);
        }
        const next = {
          ...item,
          [name]: value,
        };
        return {
          ...next,
          indexColumnKind: getIndexColumnKind(next),
        };
      }
      return item;
    });
    setDataSource(newData);
  };

  const getIncludeColInfo = (): IIndexIncludeColumnItem[] => {
    const normalized = dataSource.map(normalizeIndexIncludeColumn).filter((t) => t.columnName || t.expression);
    const invalidExpression = expressionIndexSupported
      ? validateMysqlExpressionIndexRows(normalized)
      : null;
    if (invalidExpression) {
      throw new Error(i18n('editTable.validation.invalidMysqlExpressionIndex'));
    }
    return normalized;
  };

  useImperativeHandle(ref, () => ({
    getIncludeColInfo,
  }));

  return (
    <div className={classnames(styles.container)}>
      <div className={styles.containerHeader}>
        <Button onClick={addData}>{i18n('editTable.button.add')}</Button>
        {/* <Button onClick={deleteData}>{i18n('editTable.button.delete')}</Button> */}
      </div>
      <Form className={styles.formBox} form={form} onFieldsChange={handleFieldsChange}>
        <Table
          ref={tableRef as any}
          style={{
            maxHeight: '100%',
            overflow: 'auto',
          }}
          sticky
          pagination={false}
          rowKey="key"
          columns={columns as any}
          dataSource={dataSource}
          scroll={{ x: '100%', y: 500 }}
        />
      </Form>
    </div>
  );
});

export default IncludeCol;
