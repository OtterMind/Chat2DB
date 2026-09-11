import { memo, useEffect, useMemo, useState, useRef } from 'react';
import { useStyles } from './style';
import { useTableStyles } from '@/styles/table';
import i18n from '@/i18n';
import { BaseTable, ArtColumn, useTablePipeline, features } from 'ali-react-table';
import { Spin } from 'antd';
import sqlServer from '@/service/sql';
import { TreeNodeType, WorkspaceTabType } from '@/constants';
import Pagination from '@/components/Pagination';
import { Empty, IconButton, ToolbarBtn } from '@chat2db/ui';
import { IPageParams } from '@/typings';
import isEqual from 'lodash/isEqual';
import TreeDropdown, { TreeDropdownRef } from '@/blocks/NewTree/components/TreeDropdown';
import SelectBoundInfo from '@/components/SelectBoundInfo';
import { getDatabaseSupport } from '@/utils/database';
import { useWorkspaceStore } from '@/store/workspace';
import { v4 as uuid } from 'uuid';

interface IProps {
  className?: string;
  uniqueData: {
    objectType?: 'TABLE' | 'VIEW';
    dataSourceId: string;
    dataSourceName: string;
    databaseType: string;
    databaseName?: string;
    schemaName?: string;
    submitCallback?: () => void;
  };
}

export default memo<IProps>(
  (props) => {
    const { className, uniqueData } = props;
    const { styles, cx, theme } = useStyles();
    const { styles: tableStyles } = useTableStyles();
    const [tableData, setTableData] = useState<any[] | null>(null);
    const [columnResize, setColumnResize] = useState<number[]>([200, 100, 150, 150, 150, 150, 150]);
    const tableBoxRef = useRef<HTMLDivElement>(null);
    const [searchValue, setSearchValue] = useState<string>('');
    const [boundInfo, setBoundInfo] = useState<any>(uniqueData);
    const [paginationConfig, setPaginationConfig] = useState({
      pageNo: 1,
      pageSize: 1000,
      total: 0,
      hasNextPage: false,
    });
    // Tree drop-down menu ref.
    const treeDropdownRef = useRef<TreeDropdownRef>(null);

    const { addWorkspaceTab } = useWorkspaceStore((state) => {
      return {
        addWorkspaceTab: state.addWorkspaceTab,
      };
    });

    const handleRightClick = (event, record) => {
      event.preventDefault();
      treeDropdownRef.current?.setCurrentNode({ event, node: record });
    };

    const renderTableCell = (value, rowData) => {
      return (
        <div
          className={styles.tableCell}
          onContextMenu={(e) => {
            handleRightClick(e, rowData);
          }}
          onDoubleClick={() => {
            treeDropdownRef.current?.handleDoubleClick(rowData);
          }}
        >
          {value}
        </div>
      );
    };

    const boundInfoChangeCallback = (_boundInfo) => {
      setBoundInfo(_boundInfo);
      getTable(
        {
          pageNo: 1,
          pageSize: paginationConfig.pageSize,
        },
        _boundInfo,
      );
    };

    // Table column configuration.
    const columns: ArtColumn[] = useMemo(() => {
      if (uniqueData.objectType === 'VIEW') {
        return [
          {
            title: i18n('workspace.tableTitle.viewName'),
            name: 'name',
            code: 'name',
            lock: true,
            render: (value, rowData) => {
              return renderTableCell(value, rowData);
            },
          },
        ];
      }
      return [
        {
          title: i18n('workspace.tableTitle.tableName'),
          name: 'name',
          code: 'name',
          lock: true,
          render: (value, rowData) => {
            return renderTableCell(value, rowData);
          },
        },
        {
          title: i18n('workspace.tableTitle.rows'),
          name: 'rows',
          code: 'rows',
          render: (value, rowData) => {
            return renderTableCell(value, rowData);
          },
        },
        {
          name: i18n('workspace.tableTitle.engine'),
          key: 'engine',
          code: 'engine',
          render: (value, rowData) => {
            return renderTableCell(value, rowData);
          },
        },
        {
          name: i18n('workspace.tableTitle.collate'),
          key: 'collate',
          code: 'collate',
          render: (value, rowData) => {
            return renderTableCell(value, rowData);
          },
        },
        {
          name: i18n('workspace.tableTitle.dataLength'),
          key: 'dataLength',
          code: 'dataLength',
          render: (value, rowData) => {
            return renderTableCell(value, rowData);
          },
        },
        {
          name: i18n('workspace.tableTitle.createTime'),
          key: 'createTime',
          code: 'createTime',
          render: (value, rowData) => {
            return renderTableCell(value, rowData);
          },
        },
        {
          name: i18n('workspace.tableTitle.tableComment'),
          key: 'comment',
          code: 'comment',
          render: (value, rowData) => {
            return renderTableCell(value, rowData);
          },
        },
      ];
    }, [uniqueData.objectType]);

    // Table rendering configuration.
    const pipeline = useTablePipeline()
      .input({ dataSource: tableData || [], columns })
      .use(
        features.columnResize({
          handleHoverBackground: theme.colorPrimaryBgHover,
          handleActiveBackground: theme.colorPrimaryBgHover,
          minSize: 60,
          maxSize: 500,
          sizes: columnResize,
          onChangeSizes: (sizes) => {
            setColumnResize(sizes);
          },
        }),
      );

    useEffect(() => {
      getTable();
    }, []);

    const getTable = (
      params: IPageParams = {
        pageNo: 1,
        pageSize: 1000,
      },
      _boundInfo: any = null,
    ) => {
      const newBoundInfo = _boundInfo || boundInfo;
      const requestParams = {
        // ...(newBoundInfo || {}),
        dataSourceId: newBoundInfo.dataSourceId,
        databaseName: newBoundInfo.databaseName,
        schemaName: newBoundInfo.schemaName,
        ...(params || {}),
        refresh: true,
      } as any;
      const { databaseType } = newBoundInfo;
    // Request data only after selecting both Database and Schema when the database supports them.
      const { supportDatabase, supportSchema } = getDatabaseSupport(databaseType);
      const compliantDatabase = (supportDatabase && requestParams.databaseName) || !supportDatabase;
      const compliantSchema = (supportSchema && requestParams.schemaName) || !supportSchema;
      if (!compliantDatabase || !compliantSchema) {
        return;
      }
      setTableData(null);
      const api =
        uniqueData.objectType === 'VIEW'
          ? sqlServer.getViewList
          : sqlServer.getTableList;
      api(requestParams).then((res) => {
        const pinnedList: string[] = [];
        const tableList: any = [];
        res.data.map((t) => {
          if (!pinnedList.includes(t.name)) {
            // const key =
            //   treeConfig?.[TreeNodeType.TABLE]?.createTreeNodeKey?.({
            //     dataSourceId: newBoundInfo.dataSourceId,
            //     databaseName: newBoundInfo.databaseName,
            //     schemaName: newBoundInfo.schemaName,
            //     tableName: t.name,
            //   }) || t.name;
            const item = {
              treeNodeType: uniqueData.objectType === 'VIEW' ? TreeNodeType.VIEW : TreeNodeType.TABLE,
              key: t.name,
              originalTitle: t.name,
              title: null,
              ...t,
              extraParams: {
                ...newBoundInfo,
                tableName: t.name,
              },
              decorativeParams: {
                pinned: t.pinned,
                comment: t.comment,
              },
            };
            pinnedList.push(t.name);
            tableList.push(item);
          }
          if (t.pinned) {
            pinnedList.push(t.name);
          }
        });

        setTableData(tableList);

        setPaginationConfig((_paginationConfig) => {
          return {
            ..._paginationConfig,
            total: res.total,
            hasNextPage: res.hasNextPage!,
          };
        });
      });
    };

    const handleChangePageNo = (pageNo: number) => {
      setPaginationConfig({
        ...paginationConfig,
        pageNo,
      });
      getTable({
        pageNo,
        pageSize: paginationConfig.pageSize,
      });
    };

    const handleChangePageSize = (pageSize: number) => {
      setPaginationConfig({
        ...paginationConfig,
        pageNo: 1,
        pageSize,
      });
      getTable({
        pageNo: 1,
        pageSize,
      });
    };

    const handleSearch = () => {
      getTable({
        pageNo: 1,
        pageSize: paginationConfig.pageSize,
        searchKey: searchValue,
      });
    };

    const handelRefresh = () => {
      setPaginationConfig({
        ...paginationConfig,
        pageNo: 1,
      });
      getTable({
        pageNo: 1,
        pageSize: paginationConfig.pageSize,
        searchKey: searchValue,
      });
    };

    const handelViewERModal = () => {
      const { dataSourceName, databaseName, schemaName } = boundInfo;
      const title = [dataSourceName, databaseName, schemaName, 'ER'].filter(Boolean).join('-');
      addWorkspaceTab({
        id: uuid(),
        type: WorkspaceTabType.ViewERModal,
        title,
        uniqueData: boundInfo,
      });
    };

    return (
      <div className={cx(styles.allTableContainer, className)}>
        <div className={styles.toolBarList}>
          <div className={styles.toolBarItem}>
            <Pagination
              onPageNoChange={handleChangePageNo}
              onPageSizeChange={handleChangePageSize}
              paginationConfig={paginationConfig}
            />
          </div>
          <div className={styles.toolBarItem}>
            <ToolbarBtn onClick={handelRefresh} prefixIcon="icon-refresh" text={i18n('common.button.refresh')} />
          </div>
          <div className={styles.toolBarItem}>
            {uniqueData.objectType === 'VIEW' ? null : (
              <ToolbarBtn
                onClick={handelViewERModal}
                prefixIcon="icon-er-modal"
                text={i18n('workspace.menu.viewERModal')}
              />
            )}
          </div>
          <div className={styles.toolBarRight}>
            <SelectBoundInfo
              mustHaveValue
              onChangeDBInfo={boundInfoChangeCallback}
              boundInfo={boundInfo}
              allowSelectDataSource={false}
            />
          </div>
        </div>
        <div className={styles.searchBar}>
          <div className={styles.iconContainer} onClick={handleSearch}>
            <IconButton
              size={{
                boxSize: 20,
                iconSize: 18,
                borderRadius: 3,
              }}
              code="icon-search"
            />
          </div>
          <input
            type="text"
            value={searchValue}
            onChange={(e) => {
              setSearchValue(e.target.value);
            }}
            placeholder={i18n(
              'workspace.tips.searchTableName',
              uniqueData.objectType === 'VIEW'
                ? i18n('common.text.views')
                : i18n('common.text.tables'),
            )}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                handleSearch();
              }
            }}
          />
        </div>
        {tableData === null ? (
          <div className={styles.spinBox}>
            <Spin />
          </div>
        ) : (
          <div
            ref={tableBoxRef}
            className={cx(styles.baseTable, tableStyles.supportBaseTableBox)}
            onContextMenu={(e) => {
              e.preventDefault();
              e.stopPropagation();
            }}
          >
            <BaseTable
              className={styles.table}
              components={{ EmptyContent: () => <Empty title={i18n('common.text.noData')} /> }}
              isStickyHeader
              estimatedRowHeight={32}
              stickyTop={32}
              useVirtual={true}
              {...pipeline.getProps()}
            />
            <TreeDropdown
              specialHandleLoadData={() => {
                getTable();
              }}
              ref={treeDropdownRef}
            />
          </div>
        )}
      </div>
    );
  },
  (prevProps, nextProps) => {
    return isEqual(prevProps, nextProps);
  },
);
