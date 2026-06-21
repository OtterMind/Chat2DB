import React, { useEffect, useMemo, useState } from 'react';
import { Dropdown, Input, MenuProps, message, Modal, Space, Popover, Spin, Button, DatePicker } from 'antd';
import { BaseTable, ArtColumn, useTablePipeline, features, SortItem } from 'ali-react-table';
import styled from 'styled-components';
import classnames from 'classnames';
import lodash from 'lodash';
import { v4 as uuid } from 'uuid';
import i18n from '@/i18n';
import ScreeningResult from '@/components/SearchResult/components/ScreeningResult';
// import { Context } from '@/components/SearchResult';

// 样式
import styles from './index.less';

// 工具函数
import { compareStrings } from '@/utils/sort';
import { downloadFile } from '@/utils/file';
import { transformInputValue, transformDateTimeValue } from '../../utils';

// 类型定义
import { CRUD } from '@/constants';
import { TableDataType } from '@/constants/table';
import { IManageResultData, IResultConfig } from '@/typings/database';
import { ExportSizeEnum, ExportTypeEnum } from '@/typings/resultTable';

// api
import sqlService, { IExportParams, IExecuteSqlParams } from '@/service/sql';

// store
import { setFocusedContent } from '@/store/common/copyFocusedContent';

// 依赖组件
import ExecuteSQL from '@/components/ExecuteSQL';
import { DownOutlined } from '@ant-design/icons';
import { copy, tableCopy } from '@/utils';
import Iconfont from '../../../Iconfont';
import StateIndicator from '../../../StateIndicator';
import MonacoEditor from '../../../MonacoEditor';
import MyPagination from '../Pagination';
import StatusBar from '../StatusBar';
import RightClickMenu, { AllSupportedMenusType } from '../RightClickMenu';

// 自定义hooks
import useCurdTableData from '../../hooks/useCurdTableData';
import useMultipleSelect from '../../hooks/useMultipleSelect';
import usePasteData from '../../hooks/usePasteData';

interface ITableProps {
  className?: string;
  outerQueryResultData: IManageResultData;
  executeSqlParams: any;
  tableBoxId: string;
  isActive?: boolean;
  concealTabHeader?: boolean; // concealTabHeader 是否隐藏tab头部, 目前来说隐藏头部都是单表查询。需要显示筛选
}

interface IViewTableCellData {
  name: string;
  value: any;
  colId: string;
  rowId: string;
}

export interface IUpdateData {
  oldDataList?: Array<string | null>;
  dataList?: Array<string | null>;
  type: CRUD;
  rowId: string;
}

export enum USER_FILLED_VALUE {
  DEFAULT = 'CHAT2DB_UPDATE_TABLE_DATA_USER_FILLED_DEFAULT',
}

export enum DATE_TIME {
  DATEFORMAT = 'YYYY-MM-DD HH:mm:ss',
}

const SupportBaseTable: any = styled(BaseTable)`
  &.supportBaseTable {
    --bgcolor: var(--color-bg-base);
    --header-bgcolor: var(--color-bg-subtle);
    --hover-bgcolor: transparent;
    --header-hover-bgcolor: var(--color-bg-subtle);
    --highlight-bgcolor: transparent;
    --header-highlight-bgcolor: var(--color-bg-subtle);
    --color: var(--color-text);
    --header-color: var(--color-text);
    --lock-shadow: rgb(37 37 37 / 0.5) 0 0 6px 2px;
    --border-color: var(--color-border-secondary);
    --cell-padding: 0px;
    --row-height: 32px;
    --lock-shadow: 0px 1px 2px 0px var(--color-border);
  }
`;

const preCode = '$$chat2db_';

// No列的code
const colNoCode = `${preCode}0No.`;

const defaultPaginationConfig: IResultConfig = {
  pageNo: 1,
  pageSize: 200,
  total: 0,
  hasNextPage: true,
};

export const TableContext = React.createContext({} as any);

export default function TableBox(props: ITableProps) {
  // const {} = useContext(Context);
  const { className, outerQueryResultData, isActive, concealTabHeader } = props;
  const [viewTableCellData, setViewTableCellData] = useState<IViewTableCellData | null>(null);
  const [, contextHolder] = message.useMessage();
  const [paginationConfig, setPaginationConfig] = useState<IResultConfig>(defaultPaginationConfig);
  // sql查询结果
  const [queryResultData, setQueryResultData] = useState<IManageResultData>(outerQueryResultData);
  // tableData：带列标识的表数据 可以传给Table组件 进行渲染
  // 保存原始的表数据，用于撤销
  const [oldTableData, setOldTableData] = useState<{ [key: string]: string }[]>([]);
  // 实时更新的表数据
  const [tableData, setTableData] = useState<{ [key: string]: string | null }[]>([]);
  // DataList不带列标识的表数据
  // 保存原始的表数据，用于对比新老数据看是否有变化
  const [oldDataList, setOldDataList] = useState<string[][]>([]);
  // 当前聚焦的单元格的坐标，以及是否正在编辑，为false时，代表正在聚焦，但是没有编辑
  const [editingCell, setEditingCell] = useState<[string, string, boolean] | null>(null);
  // input受控的正在编辑的数据
  const [editingData, setEditingData] = useState<string>('');
  // 当前选中的行号
  const [curOperationRowNo, setCurOperationRowNo] = useState<Array<string> | null>(null);
  // 操作过的数据列表
  const [updateData, setUpdateData] = useState<IUpdateData[] | []>([]);
  // 更新数据的sql
  const [updateDataSql, setUpdateDataSql] = useState<string | null>(null);
  // ExecuteSQL弹窗 初始化的错误信息
  const [initError, setInitError] = useState<string | null>(null);
  // 是否显示更新数据的sql
  const [viewUpdateDataSqlModal, setViewUpdateDataSqlModal] = useState<boolean>(false);
  // 用于滚动到底部
  const tableBoxRef = React.useRef<HTMLDivElement>(null);
  // 所有数据准备好了
  const [allDataReady, setAllDataReady] = useState<boolean>(false);
  // 编辑数据的inputRef
  const editDataInputRef = React.useRef<any>(null);
  // monacoEditorRef
  const monacoEditorRef = React.useRef<any>(null);
  // 表格loading
  const [tableLoading, setTableLoading] = useState<boolean>(false);
  // 列宽数组
  const [columnResize, setColumnResize] = useState<number[]>([0]);
  // 表格的宽度
  // const [tableBoxWidth, setTableBoxWidth] = useState<number>(0);

  const handleExportSQLResult = async (exportType: ExportTypeEnum, exportSize: ExportSizeEnum) => {
    const params: IExportParams = {
      ...(props.executeSqlParams || {}),
      sql: queryResultData.sql,
      originalSql: queryResultData.originalSql,
      exportType,
      exportSize,
    };
    downloadFile(window._BaseURL + '/api/rdb/dml/export', params);
  };

  useEffect(() => {
    let total: any = queryResultData.fuzzyTotal;

    // 如果total是数字，且不为0，则还是使用原先的total
    if (lodash.isNumber(paginationConfig.total) && paginationConfig.total !== 0) {
      total = paginationConfig.total;
    }

    if (!lodash.isNumber(paginationConfig.total)) {
      const oldTotal = Number(paginationConfig.total.split('+')[0]);
      const newTotal = Number(queryResultData.fuzzyTotal.split('+')[0]);
      if (oldTotal > newTotal) {
        total = paginationConfig.total;
      }
    }

    setPaginationConfig({
      ...paginationConfig,
      total,
      hasNextPage: queryResultData.hasNextPage,
    });
  }, [queryResultData]);

  useEffect(() => {
    // 每次dataList变化，都需要重新计算tableData
    if (!columns?.length) {
      setTableData([]);
    } else {
      const newTableData = dataListTransformTableData(queryResultData.dataList);
      setTableData(newTableData);
      setOldTableData(newTableData);
      setAllDataReady(true);
    }
    // 每次data变化，都需要重新计算oldDataList
    if (queryResultData.dataList?.length) {
      setOldDataList(queryResultData.dataList);
    }
  }, [queryResultData.dataList]);

  // 导出sql的菜单项
  const exportDropdownItems: MenuProps['items'] = useMemo(
    () => [
      {
        label: i18n('workspace.table.export.all.csv'),
        key: '1',
        // icon: <UserOutlined />,
        onClick: () => {
          handleExportSQLResult(ExportTypeEnum.CSV, ExportSizeEnum.ALL);
        },
      },
      {
        label: i18n('workspace.table.export.all.insert'),
        key: '2',
        // icon: <UserOutlined />,
        onClick: () => {
          handleExportSQLResult(ExportTypeEnum.INSERT, ExportSizeEnum.ALL);
        },
      },
      {
        label: i18n('workspace.table.export.cur.csv'),
        key: '3',
        // icon: <UserOutlined />,
        onClick: () => {
          handleExportSQLResult(ExportTypeEnum.CSV, ExportSizeEnum.CURRENT_PAGE);
        },
      },
      {
        label: i18n('workspace.table.export.cur.insert'),
        key: '4',
        // icon: <UserOutlined />,
        onClick: () => {
          handleExportSQLResult(ExportTypeEnum.INSERT, ExportSizeEnum.CURRENT_PAGE);
        },
      },
    ],
    [queryResultData],
  );

  const defaultSorts: SortItem[] = useMemo(
    () =>
      (queryResultData.headerList || []).map((item) => ({
        code: item.name,
        order: 'none',
      })),
    [queryResultData.headerList],
  );

  function monacoEditorEditData() {
    const editorData = monacoEditorRef?.current?.getAllContent();
    const { rowId, colId } = viewTableCellData!;
    oldTableData.forEach((item) => {
      if (item[colNoCode] === rowId) {
        if (item[colId] !== editorData) {
          const newTableData = lodash.cloneDeep(tableData);
          let newRowDataList: any = [];
          newTableData.forEach((i) => {
            if (i[colNoCode] === rowId) {
              i[colId] = editorData;
              newRowDataList = Object.keys(i).map((_i) => i[_i]);
            }
          });
          setTableData(newTableData);
          // 添加更新记录
          setUpdateData([
            ...updateData,
            {
              type: CRUD.UPDATE,
              oldDataList: Object.keys(item).map((_i) => item[_i]),
              dataList: newRowDataList,
              rowId,
            },
          ]);
        }
        return;
      }
    });
    setViewTableCellData(null);
  }

  function handleCancel() {
    setViewTableCellData(null);
  }

  const handleClickTableItem = (colId, rowId, value, isEditing) => {
    // 1. 如果当前单元格正在编辑，则不需要再次编辑
    // 2. 如果当前单元格正在编辑，则不需要聚焦
    if (editingCell?.[0] === colId && editingCell?.[1] === rowId && editingCell?.[2]) {
      return;
    }
    setFocusedContent(value);
    // 聚焦当前单元格，取消对于行的聚焦
    setCurOperationRowNo(null);
    // 当前聚焦或者编辑的单元格的数据
    setEditingData(value);
    // 如果数据不支持修改，则该单元格不支持编辑
    if (!queryResultData.canEdit) {
      setEditingCell([colId, rowId, false]);
    } else {
      setEditingCell([colId, rowId, isEditing]);
    }
    // 当前聚焦或者编辑的单元格的坐标
    // 如果是编辑状态，则需要聚焦到input
    if (isEditing) {
      setTimeout(() => {
        editDataInputRef?.current?.focus();
      }, 0);
    }
  };

  // 渲染单元格的值
  const renderTableCellValue = (value) => {
    if (value === null) {
      return <span className={styles.cellValueNull}>{'<null>'}</span>;
    } else if (value === USER_FILLED_VALUE.DEFAULT) {
      return <span className={styles.cellValueNull}>{'<default>'}</span>;
    } else if (!value) {
      // 如果为空需要展位
      return <span />;
    } else {
      return value;
    }
  };

  // 每个单元格的样式
  const tableCellStyle = (value, rowId, colId) => {
    // 单元格的基础样式
    const styleList = [styles.tableItem];
    // 如果当前行中的单元格正在聚焦或编辑
    if (editingCell?.[1] === rowId) {
      // 设置正在编辑或聚焦的单元格所在行的样式为高亮
      styleList.push(styles.tableItemHighlight);
      // 精确找到列，设置正在编辑或聚焦的单元格的样式为Focus
      if (editingCell?.[0] === colId && !editingCell?.[2]) {
        styleList.push(styles.tableItemFocus);
      }
      return classnames(...styleList);
    }
    // 当前单元格所在的行被选中了(行聚焦)
    if (curOperationRowNo?.includes(rowId)) {
      // No列的高亮只需要用tableItemHighlight不需要用tableItemFocus
      if (colId === colNoCode) {
        styleList.push(styles.tableItemHighlight);
      } else {
        styleList.push(styles.tableItemFocus);
      }
      return classnames(...styleList);
    }
    // 新添加的行
    const index2 = updateData.findIndex((item) => {
      return item.rowId === rowId && item.type === CRUD.CREATE;
    });
    if (index2 !== -1) {
      styleList.push(styles.tableItemSuccess);
      return classnames(...styleList);
    }
    // 如果是删除过的行
    const index = updateData.findIndex((item) => item.rowId === rowId && item.type === CRUD.DELETE);
    if (index !== -1) {
      styleList.push(styles.tableItemError);
      return classnames(...styleList);
    }
    // 编辑过的单元格的样式
    let oldValue = '';
    oldTableData.forEach((item) => {
      if (item[colNoCode] === rowId) {
        oldValue = item[colId];
      }
    });

    if (value !== oldValue && colId !== colNoCode) {
      // console.log('colId', colId, 'rowId', rowId)
      // console.log('oldValue', oldValue, 'value', value)

      styleList.push(styles.tableItemEdit);
    }
    return classnames(...styleList);
  };

  // 纯数据的dataList 转换为 tableData
  const dataListTransformTableData = (myDataList: string[][]) => {
    const newTableData = (myDataList || []).map((item) => {
      const rowData: any = {};
      item.map((i: string | null, colIndex: number) => {
        const colId = `${preCode}${colIndex}${columns[colIndex].name}`;
        rowData[colId] = i;
      });
      return rowData;
    });
    return newTableData;
  };

  const onPageNoChange = (pageNo: number) => {
    const config = { ...paginationConfig, pageNo };
    setPaginationConfig(config);
    getTableData({ pageNo });
  };

  const onPageSizeChange = (pageSize: number) => {
    const config = { ...paginationConfig, pageSize, pageNo: 1 };
    setPaginationConfig(config);
    getTableData({ pageSize, pageNo: 1 });
  };

  const onClickTotalBtn = async () => {
    const res = await sqlService.getDMLCount({
      sql: queryResultData.originalSql,
      ...(props.executeSqlParams || {}),
    });
    setPaginationConfig({ ...paginationConfig, total: res });
    return res;
  };

  // 撤销按钮是否可用
  const revokeDisableBarState = useMemo(() => {
    // 如果有聚焦的行，但是没有操作过的数据，则不可用
    const operationType = [CRUD.CREATE, CRUD.UPDATE, CRUD.DELETE];
    if (curOperationRowNo) {
      // 当前选中的行里面有没有操作过的数据
      const hasOperationData = updateData.some((item) => {
        return operationType.includes(item.type) && curOperationRowNo.includes(item.rowId);
      });
      if (hasOperationData) {
        return false;
      }
    }
    // 如果有聚焦的单元格
    if (editingCell && editingCell[2] === false) {
      const oldRowDataList = oldDataList.find((item) => item[0] === editingCell[1]);
      const oldData = oldRowDataList?.[editingCell[0]];
      // 如果当前单元格的数据和老数据一样，则可用
      if (oldData !== editingData) {
        return false;
      }
    }
    // 如果都没，那撤销按钮不可用
    return true;
  }, [curOperationRowNo, updateData, editingCell]);

  // 处理撤销
  const handleRevoke = () => {
    if (revokeDisableBarState) {
      return;
    }
    // 多行撤销处理
    if (curOperationRowNo?.length) {
      const _updateData = updateData.filter((item) => !curOperationRowNo?.includes(item.rowId));
      let _tableData = tableData.map((item) => {
        const oldData = oldTableData.find((i) => i[colNoCode] === item[colNoCode])!;
        return curOperationRowNo.includes(item[colNoCode]!) ? oldData : item;
      });
      _tableData = _tableData.filter((item) => item);

      setUpdateData(_updateData);
      setTableData(_tableData);
      setCurOperationRowNo(null);
      return;
    }

    // 聚焦单元格撤销
    if (editingCell && editingCell[2] === false) {
      const oldRowTableData = oldTableData.find((item) => item[colNoCode] === editingCell[1])!;
      const oldData = oldRowTableData[editingCell[0]];
      const _tableData = tableData.map((item) => {
        if (item[colNoCode] === editingCell[1]) {
          item[editingCell[0]] = oldData || '';
        }
        return item;
      });

      // 如果撤销后这一行的数据和原始数据一样，则删除这条更新记录
      const newRowTableData = _tableData.find((item) => item[colNoCode] === editingCell[1])!;
      if (lodash.isEqual(newRowTableData, oldRowTableData)) {
        setUpdateData(updateData.filter((item) => item.rowId !== editingCell[1]));
      }

      setTableData(_tableData);
    }
  };

  // 查看更新数据的sql
  const handleViewSql = () => {
    if (!updateData.length) {
      return;
    }
    getExecuteUpdateSql().then((res) => {
      setUpdateDataSql(res);
      setViewUpdateDataSqlModal(true);
    });
  };

  // 更新数据的sql
  const handleUpdateSubmit = () => {
    if (!updateData.length || tableLoading) {
      return;
    }
    setTableLoading(true);
    getExecuteUpdateSql()
      .then((res) => {
        executeUpdateDataSql(res);
      })
      .catch(() => {
        setTableLoading(false);
      });
  };

  // 获取更新数据的sql
  const getExecuteUpdateSql = (_updateData?: any) => {
    return new Promise<string>((resolve) => {
      const params = {
        databaseName: props.executeSqlParams?.databaseName,
        dataSourceId: props.executeSqlParams?.dataSourceId,
        schemaName: props.executeSqlParams?.schemaName,
        type: props.executeSqlParams?.databaseType,
        tableName: queryResultData.tableName,
        headerList: queryResultData.headerList,
        operations: _updateData || updateData,
      };
      sqlService.getExecuteUpdateSql(params).then((res) => {
        resolve(res || '');
      });
    });
  };

  // 执行sql
  const executeUpdateDataSql = (sql: string) => {
    const executeSQLParams: IExecuteSqlParams = {
      sql,
      dataSourceId: props.executeSqlParams?.dataSourceId,
      databaseName: props.executeSqlParams?.databaseName,
      schemaName: props.executeSqlParams?.schemaName,
      tableName: queryResultData.tableName,
    };
    sqlService
      .executeUpdateDataSql(executeSQLParams)
      .then((res) => {
        if (res?.success) {
          // 更新成功后，需要重新获取表格数据
          getTableData().then(() => {
            message.success(i18n('common.text.successfulExecution'));
            setUpdateData([]);
          });
        } else {
          setUpdateDataSql(res?.sql);
          setViewUpdateDataSqlModal(true);
          setInitError(res.message);
        }
      })
      .finally(() => {
        setTableLoading(false);
      });
  };

  // 获取表格数据 接受一个参数params 包含IExecuteSqlParams中的一个或多个
  const getTableData = (params?: Partial<IExecuteSqlParams>) => {
    setTableLoading(true);
    setCurOperationRowNo(null);
    setEditingCell(null);
    const executeSQLParams: IExecuteSqlParams = {
      sql: queryResultData.originalSql,
      dataSourceId: props.executeSqlParams?.dataSourceId,
      databaseName: props.executeSqlParams?.databaseName,
      schemaName: props.executeSqlParams?.schemaName,
      pageNo: paginationConfig.pageNo,
      pageSize: paginationConfig.pageSize,
      ...(params || {}),
    };

    return sqlService.executeSql(executeSQLParams).then((res) => {
      setTableLoading(false);
      setQueryResultData(res?.[0]);
      setUpdateData([]);
    });
  };

  // sql执行成功后的回调
  const executeSuccessCallBack = () => {
    getTableData().then(() => {
      setViewUpdateDataSqlModal(false);
      message.success(i18n('common.text.successfulExecution'));
    });
  };

  const { multipleSelect } = useMultipleSelect({
    setCurOperationRowNo,
    tableData,
    colNoCode,
    curOperationRowNo,
    setFocusedContent,
  });

  const handelRowNoClick = (rowId: string) => {
    multipleSelect(rowId);
    setEditingCell(null);
    // const newRowData = tableData.find((item) => item[colNoCode] === rowId)!;
    // const newRowDataList = Object.keys(newRowData).map((item) => newRowData[item]);
    // newRowDataList.splice(0, 1);
  };

  // 表格 列配置
  const columns: ArtColumn[] = useMemo(() => {
    return (queryResultData.headerList || []).map((item, colIndex) => {
      const { dataType, name } = item;
      const isNumber = dataType === TableDataType.NUMERIC;
      const isNumericalOrder = dataType === TableDataType.CHAT2DB_ROW_NUMBER;
      const colId = `${preCode}${colIndex}${name}`;
      const isDateTime = dataType === TableDataType.DATETIME;

      if (isNumericalOrder) {
        return {
          code: colNoCode,
          name: 'No.',
          title: (
            <div
              className={styles.allSelectBox}
              onClick={() => {
                setEditingCell(null);
                if (curOperationRowNo) {
                  setCurOperationRowNo(null);
                  return;
                }
                // 全选列
                const rowIds = tableData.map((i) => i[colNoCode]!);
                setCurOperationRowNo(rowIds);
              }}
            />
          ),
          key: name,
          lock: true,
          // features: { sortable: compareStrings },
          render: (value: any, rowData, rowIndex) => {
            const rowId = rowData[colNoCode];
            return (
              <div
                data-chat2db-general-can-copy-element
                data-chat2db-edit-table-data-can-paste
                data-chat2db-edit-table-data-can-right-click
                onClick={() => {
                  handelRowNoClick(rowId);
                }}
                onContextMenu={() => {
                  if (!curOperationRowNo?.includes(rowId)) {
                    handelRowNoClick(rowId);
                  }
                }}
                className={tableCellStyle(value, rowId, colNoCode)}
              >
                <div className={styles.tableItemNo}>{rowIndex + 1}</div>
              </div>
            );
          },
        };
      }

      if (isDateTime) {
        return {
          code: colId,
          name: name,
          key: name,
          render: (value: any, rowData) => {
            const rowId = rowData[colNoCode];
            const content = renderTableCellValue(value);
            return (
              <div
                data-chat2db-general-can-copy-element
                data-chat2db-edit-table-data-can-paste
                data-chat2db-edit-table-data-can-right-click
                className={tableCellStyle(value, rowId, colId)}
                onClick={handleClickTableItem.bind(null, colId, rowId, value, false)}
                onDoubleClick={handleClickTableItem.bind(null, colId, rowId, value, true)}
                onContextMenu={handleClickTableItem.bind(null, colId, rowId, value, false)}
              >
                {editingCell?.[0] === colId && editingCell?.[1] === rowId && editingCell?.[2] ?

                  (
                    <DatePicker
                      showTime
                      format={DATE_TIME.DATEFORMAT}
                      value={transformDateTimeValue(editingData) as any}
                      onChange={(_, dateString) => {
                        setEditingData(String(dateString));
                      }}
                      onBlur={() => {
                        setEditingCell([editingCell![0], editingCell![1], false]);
                        updateTableData('setCell', editingData);
                      }}
                    />
                  ) : (
                    <>
                      <div className={styles.tableItemContent}>{content}</div>
                      <div className={styles.previewTableItemContent}>{content}</div>
                    </>
                  )}
              </div>
            );
          }
        };
      }
      return {
        code: colId,
        name: name,
        key: name,
        // title: <div>{name}</div>,
        render: (value: any, rowData) => {
          const rowId = rowData[colNoCode];
          const content = renderTableCellValue(value);
          return (
            <div
              data-chat2db-general-can-copy-element
              data-chat2db-edit-table-data-can-paste
              data-chat2db-edit-table-data-can-right-click
              className={tableCellStyle(value, rowId, colId)}
              onClick={handleClickTableItem.bind(null, colId, rowId, value, false)}
              onDoubleClick={handleClickTableItem.bind(null, colId, rowId, value, true)}
              onContextMenu={handleClickTableItem.bind(null, colId, rowId, value, false)}
            >
              {editingCell?.[0] === colId && editingCell?.[1] === rowId && editingCell?.[2] ? (
                <Input
                  ref={editDataInputRef}
                  value={transformInputValue(editingData) as any}
                  onChange={(e) => {
                    setEditingData(e.target.value);
                  }}
                  onBlur={() => {
                    setEditingCell([editingCell![0], editingCell![1], false]);
                    updateTableData('setCell', editingData);
                  }}
                />
              ) : (
                <>
                  <div className={styles.tableItemContent}>{content}</div>
                  <div className={styles.previewTableItemContent}>{content}</div>
                </>
              )}
            </div>
          );
        },
        // 如果是数字类型，因为后端返回的都是字符串，所以需要调用字符串对比函数来判断
        features: { sortable: isNumber ? compareStrings : true },
      };
    });
  }, [queryResultData.headerList, editingCell, editingData, curOperationRowNo, oldDataList]);

  const { updateTableData, handleCreateData, handleDeleteData } = useCurdTableData({
    tableData,
    setTableData,
    preCode,
    editingCell,
    columns,
    curOperationRowNo,
    oldDataList,
    updateData,
    setUpdateData,
    queryResultData,
    setCurOperationRowNo,
    setEditingCell,
    tableBoxRef,
    oldTableData,
    colNoCode,
  });

  // 处理粘贴的数据 hooks
  usePasteData({ updateTableData, curOperationRowNo, editingCell });

  // 表格渲染的配置
  const pipeline = useTablePipeline()
    .input({ dataSource: tableData, columns })
    .use(
      features.sort({
        mode: 'single',
        defaultSorts,
        highlightColumnWhenActive: true,
        // sorts,
        // onChangeSorts,
      }),
    )
    .use(
      features.columnResize({
        fallbackSize: 150,
        // handleBackground: '#ddd',
        handleHoverBackground: `var(--color-primary-bg-hover)`,
        handleActiveBackground: `var(--color-primary-bg-hover)`,
        minSize: 60,
        maxSize: 1080,
        sizes: columnResize,
        onChangeSizes: (sizes) => {
          sizes[0] = 0;
          setColumnResize(sizes);
        },
      }),
    );

  const getSelectTableRowData = () => {
    if (!curOperationRowNo && !editingCell) {
      return [[]];
    }
    const rowIds = curOperationRowNo || [editingCell?.[1]];
    const newRowDatas = tableData.filter((item) => rowIds.includes(item[colNoCode]!));
    const newRowDatasList = newRowDatas.map((item) => {
      const _item = lodash.cloneDeep(item);
      delete _item[colNoCode];
      return Object.keys(_item).map((i) => _item[i]);
    });
    return newRowDatasList;
  };

  // 右键菜单配置项
  const copyRow = {
    key: AllSupportedMenusType.CopyRow,
    children: [
      {
        callback: () => {
          const rowIds = curOperationRowNo || [editingCell![1]];
          const newRowDatas = tableData.filter((item) => rowIds.includes(item[colNoCode]!));
          const newRowDatasList = newRowDatas.map((item) => {
            const _item = lodash.cloneDeep(item);
            return Object.keys(_item).map((i) => _item[i]);
          });
          const _updateDatas = newRowDatasList.map((item, index) => {
            return {
              type: CRUD.CREATE,
              dataList: item,
              rowId: (tableData.length + index + 1).toString(),
            };
          });

          getExecuteUpdateSql(_updateDatas).then((res) => {
            copy(res);
          });
        },
        hide: !queryResultData.canEdit,
      },
      {
        callback: () => {
          const rowIds = curOperationRowNo || [editingCell![1]];
          const newRowDatas = tableData.filter((item) => rowIds.includes(item[colNoCode]!));
          const newRowDatasList = newRowDatas.map((item) => {
            const _item = lodash.cloneDeep(item);
            return Object.keys(_item).map((i) => _item[i]);
          });
          const _updateDatas = newRowDatasList.map((item, index) => {
            return {
              type: CRUD.UPDATE_COPY,
              dataList: item,
              rowId: (tableData.length + index + 1).toString(),
            };
          });

          getExecuteUpdateSql(_updateDatas).then((res) => {
            copy(res);
          });
        },
        hide: !queryResultData.canEdit,
      },
      // 复制当前行的数据
      {
        callback: () => {
          const selectTableRowData = getSelectTableRowData();
          tableCopy(selectTableRowData);
        },
      },
      // 复制表头
      {
        callback: () => {
          const headerList = queryResultData.headerList.map((item) => item.name);
          // 去掉No列
          headerList.splice(0, 1);
          tableCopy([headerList]);
        },
      },
      // 复制表头和当前行的数据
      {
        callback: () => {
          const rowIds = curOperationRowNo || [editingCell![1]];
          const newRowDatas = tableData.filter((item) => rowIds.includes(item[colNoCode]!));
          const newRowDatasList = newRowDatas.map((item) => {
            const _item = lodash.cloneDeep(item);
            delete _item[colNoCode];
            return Object.keys(_item).map((i) => _item[i]);
          });
          const headerList = queryResultData.headerList.map((item) => item.name);
          // 去掉No列
          headerList.splice(0, 1);
          tableCopy([headerList, ...newRowDatasList]);
        },
      },
    ],
  };

  const cloneRow = {
    key: AllSupportedMenusType.CloneRow,
    callback: () => {
      const newTableData = lodash.cloneDeep(tableData);
      const rowIds = curOperationRowNo || [editingCell![1]];
      // 在newTableData中找出 rowIds中所有的行
      const newRowDatas = newTableData.filter((item) => rowIds.includes(item[colNoCode]!));
      newRowDatas.map((t, i) => {
        t[colNoCode] = (newTableData.length + i + 1).toString();
      });
      handleCreateData(newRowDatas);
    },
  };

  const deleteRow = {
    key: AllSupportedMenusType.DeleteRow,
    callback: handleDeleteData,
  };

  const copyCell = {
    key: AllSupportedMenusType.CopyCell,
    callback: () => {
      copy(editingData);
    },
  };

  const setDefault = {
    key: AllSupportedMenusType.SetDefault,
    callback: () => {
      updateTableData('setCell', USER_FILLED_VALUE.DEFAULT);
    },
  };

  const setNull = {
    key: AllSupportedMenusType.SetNull,
    callback: () => {
      updateTableData('setCell', null);
    },
  };

  const viewData = {
    key: AllSupportedMenusType.ViewData,
    callback: () => {
      setViewTableCellData({
        name: columns.find((i) => i.code === editingCell![0])!.name,
        value: editingData,
        colId: editingCell![0],
        rowId: editingCell![1],
      });
    },
  };

  const rowRightClickMenu = useMemo(() => {
    let rightClickMenu: any = [];
    if (curOperationRowNo) {
      rightClickMenu = [copyRow, cloneRow, deleteRow];
      // 如果当前数据不可编辑，则不显示cloneRow和deleteRow
      if (!queryResultData.canEdit) {
        rightClickMenu = rightClickMenu.filter(
          (i) => i.key !== AllSupportedMenusType.CloneRow && i.key !== AllSupportedMenusType.DeleteRow,
        );
      }
    }

    if (editingCell) {
      rightClickMenu = [viewData, copyCell, copyRow, cloneRow, setNull, setDefault, deleteRow];
      // 判断是否有默认值,如果没有默认值，则不显示设置默认值的菜单
      const colId = editingCell[0];
      const hasDefaultValue =
        queryResultData.headerList.find((item) => {
          return item.name === columns.find((i) => i.code === colId)?.name;
        })?.defaultValue !== null;

      if (!hasDefaultValue) {
        rightClickMenu = rightClickMenu.filter((i) => i.key !== AllSupportedMenusType.SetDefault);
      }
      // 如果当前数据不可编辑，则不显示cloneRow和deleteRow
      if (!queryResultData.canEdit) {
        rightClickMenu = rightClickMenu.filter(
          (i) =>
            i.key !== AllSupportedMenusType.CloneRow &&
            i.key !== AllSupportedMenusType.DeleteRow &&
            i.key !== AllSupportedMenusType.SetNull,
        );
      }
    }

    if (!curOperationRowNo && !editingCell) {
      return null;
    }
    return rightClickMenu;
  }, [curOperationRowNo, editingCell, queryResultData]);

  const renderContent = () => {
    const bottomStatus = (
      <div className={styles.statusBar}>
        <span>{`【${i18n('common.text.result')}】${queryResultData.description}.`}</span>
        <span>{`【${i18n('common.text.timeConsuming')}】${queryResultData.duration}ms.`}</span>
        <span>{`【${i18n('common.text.searchRow')}】${tableData.length} ${i18n('common.text.row')}.`}</span>
      </div>
    );

    if (!columns.length) {
      return (
        <>
          <StateIndicator state="success" text={i18n('common.text.successfulExecution')} />
          <div style={{ position: 'absolute', bottom: 0, left: 0, right: 0 }}>{bottomStatus}</div>
        </>
      );
    } else {
      return (
        <>
          <div className={styles.toolBar}>
            <div className={styles.toolBarItem}>
              <MyPagination
                paginationConfig={paginationConfig}
                onPageNoChange={onPageNoChange}
                onPageSizeChange={onPageSizeChange}
                onClickTotalBtn={onClickTotalBtn}
              />
            </div>
            <div className={classnames(styles.toolBarItem, styles.refreshBar)}>
              {/* 刷新 */}
              <Popover mouseEnterDelay={0.8} content={i18n('common.button.refresh')} trigger="hover">
                <div
                  onClick={() => {
                    getTableData();
                  }}
                  className={classnames(styles.refreshIconBox)}
                >
                  <Iconfont code="&#xe62d;" />
                </div>
              </Popover>
            </div>
            {queryResultData.canEdit && (
              <div className={classnames(styles.toolBarItem, styles.editTableDataBar)}>
                {/* 新增行 */}
                <Popover mouseEnterDelay={0.8} content={i18n('editTableData.tips.addRow')} trigger="hover">
                  <div
                    onClick={() => {
                      handleCreateData();
                    }}
                    className={classnames(styles.createDataBar, styles.editTableDataBarItem)}
                  >
                    <Iconfont code="&#xe61b;" />
                  </div>
                </Popover>
                {/* 删除行 */}
                <Popover mouseEnterDelay={0.8} content={i18n('editTableData.tips.deleteRow')} trigger="hover">
                  <div
                    onClick={() => {
                      handleDeleteData();
                    }}
                    className={classnames(styles.deleteDataBar, styles.editTableDataBarItem, {
                      [styles.disableBar]: curOperationRowNo === null,
                    })}
                  >
                    <Iconfont code="&#xe644;" />
                  </div>
                </Popover>
                {/* 撤销 */}
                <Popover mouseEnterDelay={0.8} content={i18n('editTableData.tips.revert')} trigger="hover">
                  <div
                    onClick={handleRevoke}
                    className={classnames(styles.revokeBar, styles.editTableDataBarItem, {
                      [styles.disableBar]: revokeDisableBarState,
                    })}
                  >
                    <Iconfont code="&#xe6e2;" />
                  </div>
                </Popover>
                {/* 查看更改sql */}
                <Popover
                  mouseEnterDelay={0.8}
                  content={i18n('editTableData.tips.previewPendingChanges')}
                  trigger="hover"
                >
                  <div
                    onClick={handleViewSql}
                    className={classnames(styles.viewSqlBar, styles.editTableDataBarItem, {
                      [styles.disableBar]: !updateData.length,
                    })}
                  >
                    <Iconfont code="&#xe654;" />
                  </div>
                </Popover>
                {/* 提交 */}
                <Popover mouseEnterDelay={0.8} content={i18n('editTableData.tips.submit')} trigger="hover">
                  <div
                    onClick={handleUpdateSubmit}
                    className={classnames(styles.updateSubmitBar, styles.editTableDataBarItem, {
                      [styles.disableBar]: !updateData.length,
                    })}
                  >
                    <Iconfont code="&#xe687;" />
                  </div>
                </Popover>
              </div>
            )}
            <div className={styles.toolBarRight}>
              <Dropdown menu={{ items: exportDropdownItems }} trigger={['click']}>
                <Space className={styles.exportBar}>
                  {i18n('common.text.export')}
                  <DownOutlined />
                </Space>
              </Dropdown>
            </div>
          </div>
          {concealTabHeader && <ScreeningResult getTableData={getTableData} promptWord={queryResultData.headerList} />}
          {isActive ? (
            <RightClickMenu menuList={rowRightClickMenu}>
              <div
                ref={tableBoxRef}
                className={classnames(styles.supportBaseTableBox, { [styles.supportBaseTableBoxHidden]: tableLoading })}
              >
                {allDataReady && (
                  <>
                    {tableLoading && <Spin className={styles.supportBaseTableSpin} />}
                    <SupportBaseTable
                      className={classnames('supportBaseTable', props.className, styles.table)}
                      components={{ EmptyContent: () => <h2>{i18n('common.text.noData')}</h2> }}
                      isStickyHead
                      stickyTop={31}
                      {...pipeline.getProps()}
                    />
                  </>
                )}
              </div>
            </RightClickMenu>
          ) : (
            <div className={styles.supportBaseTableBox} />
          )}
          <StatusBar
            description={queryResultData.description}
            duration={queryResultData.duration}
            dataLength={tableData.length}
          />
        </>
      );
    }
  };

  const renderMonacoEditor = useMemo(() => {
    return (
      <div className={styles.monacoEditor}>
        <MonacoEditor
          ref={monacoEditorRef}
          id={`view_table-Cell_data-${uuid()}`}
          appendValue={{
            text: transformInputValue(viewTableCellData?.value),
            range: 'reset',
          }}
          language="plaintext"
          options={{
            lineNumbers: 'off',
            readOnly: !queryResultData.canEdit,
          }}
        />
      </div>
    );
  }, [queryResultData, viewTableCellData]);

  return (
    <div className={classnames(className, styles.tableBox, { [styles.noDataTableBox]: !tableData.length })}>
      {renderContent()}
      <Modal
        title={viewTableCellData?.name}
        open={!!viewTableCellData?.name}
        onCancel={handleCancel}
        width="60vw"
        maskClosable={false}
        destroyOnClose={true}
        footer={
          queryResultData.canEdit && [
            <Button key="1" type="primary" onClick={monacoEditorEditData}>
              {i18n('common.button.modify')}
            </Button>,
          ]
        }
      >
        {renderMonacoEditor}
      </Modal>
      <Modal
        width="60vw"
        maskClosable={false}
        title={initError ? i18n('common.button.executionError') : i18n('editTable.title.sqlPreview')}
        open={viewUpdateDataSqlModal}
        footer={false}
        destroyOnClose={true}
        onCancel={() => {
          setViewUpdateDataSqlModal(false);
          setUpdateDataSql('');
          setInitError(null);
        }}
      >
        <ExecuteSQL
          initError={initError}
          initSql={updateDataSql}
          databaseName={props.executeSqlParams?.databaseName}
          dataSourceId={props.executeSqlParams?.dataSourceId}
          tableName={queryResultData.tableName}
          schemaName={props.executeSqlParams?.schemaName}
          databaseType={props.executeSqlParams?.databaseType}
          executeSuccessCallBack={executeSuccessCallBack}
          executeSqlApi="executeUpdateDataSql"
        />
      </Modal>
      {contextHolder}
    </div>
  );
}
