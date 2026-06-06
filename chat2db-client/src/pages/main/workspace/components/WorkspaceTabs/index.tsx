import React, { memo, useEffect, useMemo, Fragment, useState, useRef } from 'react';
import styles from './index.less';
import i18n from '@/i18n';
import { Button, message, Spin } from 'antd';
import { LoadingOutlined } from '@ant-design/icons';

// ----- constants -----
import { WorkspaceTabType, workspaceTabConfig } from '@/constants';
import { IWorkspaceTab } from '@/typings';

// ----- components -----
import Tabs, { ITabItem } from '@/components/Tabs';
import SearchResult from '@/components/SearchResult';
import DatabaseTableEditor from '@/blocks/DatabaseTableEditor';
import SQLExecute from '../SQLExecute';
import ViewAllTable from '../ViewAllTable';
import RedisDataView from '../RedisDataView';
import ERDiagram from '../ERDiagram';
import SchemaDiffPanel from '@/blocks/SchemaDiff';
import Iconfont from '@/components/Iconfont';
import ShortcutKey from '@/components/ShortcutKey';

// ---- store -----
import {
  getOpenConsoleList,
  setActiveConsoleId,
  setWorkspaceTabList,
  createConsole,
} from '@/pages/main/workspace/store/console';
import { useWorkspaceStore } from '@/pages/main/workspace/store';
import { useTreeStore } from '@/blocks/Tree/treeStore';
import { useConnectionStore } from '@/pages/main/store/connection';

// ----- services -----
import historyService from '@/service/history';
import sqlService from '@/service/sql';

import indexedDB from '@/indexedDB';
import connectToEventSource from '@/utils/eventSource';
import { formatParams } from '@/utils/url';
import { getCookie } from '@/utils';
import { v4 as uuidv4 } from 'uuid';
import { getConsoleEditor } from '../SQLExecute/consoleEditorRegistry';

const WorkspaceTabs = memo(() => {
  const { activeConsoleId, consoleList, workspaceTabList } = useWorkspaceStore((state) => {
    return {
      consoleList: state.consoleList,
      activeConsoleId: state.activeConsoleId,
      workspaceTabList: state.workspaceTabList,
    };
  });

  const { connectionList } = useConnectionStore((state) => {
    return {
      connectionList: state.connectionList,
    };
  });

  const currentConnectionDetails = useWorkspaceStore((state) => state.currentConnectionDetails);
  const [generatingTitleKey, setGeneratingTitleKey] = useState<number | string | null>(null);
  const closeEventSourceRef = useRef<(() => void) | null>(null);
  const prevEnvironmentRef = useRef<string | null>(null);

  const getConnectionEnvironment = (dataSourceId?: number) => {
    if (!dataSourceId || !connectionList) return null;
    const connection = connectionList.find((item) => item.id === dataSourceId);
    return connection?.environment || null;
  };

  const isReleaseEnvironment = (environment: { name: string; shortName: string } | null) => {
    if (!environment) return false;
    const envName = environment.name?.toLowerCase();
    const envShortName = environment.shortName?.toLowerCase();
    return envName?.includes('release') || envShortName === 'release';
  };

  // 获取console
  useEffect(() => {
    getOpenConsoleList();
  }, []);

  // consoleList 先转换为通用的 workspaceTabList，同时保留非 consoleList 管理的本地 tab
  useEffect(() => {
    const _workspaceTabItems =
      consoleList?.map((item) => {
        return {
          id: item.id,
          type: item.operationType,
          title: item.name,
          uniqueData: {
            dataSourceId: item.dataSourceId,
            dataSourceName: item.dataSourceName,
            databaseType: item.type,
            databaseName: item.databaseName,
            schemaName: item.schemaName,
            status: item.status,
            ddl: item.ddl,
            connectable: item.connectable,
            name: item.name, // 添加名称用于动态创建 loadSQL
          },
        };
      }) || [];

    const currentWorkspaceTabList = useWorkspaceStore.getState().workspaceTabList;
    const localTabs = (currentWorkspaceTabList || []).filter(
      (tab) => !_workspaceTabItems.some((ct) => ct.id === tab.id),
    );

    setWorkspaceTabList([..._workspaceTabItems, ...localTabs]);
  }, [consoleList]);

  // 关闭tab
  const closeWindowTab = (key: number) => {
    const p: any = {
      id: key,
      tabOpened: 'n',
    };

    historyService.updateSavedConsole(p).then(() => {
      indexedDB.deleteData('chat2db', 'workspaceConsoleDDL', key);
    });

    const { consoleList: currentConsoleList } = useWorkspaceStore.getState();
    if (currentConsoleList) {
      useWorkspaceStore.setState({
        consoleList: currentConsoleList.filter((item) => item.id !== key),
      });
    }
  };

  const createNewConsole = () => {
    const { databaseName, schemaName } =  useTreeStore.getState().focusTreeNode || {};
    if (currentConnectionDetails) {
      createConsole({
        dataSourceId: currentConnectionDetails.id,
        dataSourceName: currentConnectionDetails.alias,
        databaseType: currentConnectionDetails.type,
        databaseName,
        schemaName
      });
    }
  };

  // 删除 新增tab
  const handelTabsEdit = (action: 'add' | 'remove', data: ITabItem[]) => {
    if (action === 'remove') {
      setWorkspaceTabList(
        workspaceTabList?.filter((t) => {
          return data.findIndex((item) => item.key === t.id) === -1;
        }) || [],
      );
      data.forEach((item) => {
        const editData = workspaceTabList?.find((t) => t.id === item.key);
        if (
          editData?.type === WorkspaceTabType.CONSOLE ||
          editData?.type === WorkspaceTabType.FUNCTION ||
          editData?.type === WorkspaceTabType.PROCEDURE ||
          editData?.type === WorkspaceTabType.TRIGGER ||
          editData?.type === WorkspaceTabType.VIEW ||
          // table 和 !editData?.type 为了兼容老数据
          editData?.type === ('table' as any) ||
          !editData?.type
        ) {
          closeWindowTab(item.key as number);
        }
      });
    }
    if (action === 'add') {
      createNewConsole();
    }
  };

  // 切换tab
  const onTabChange = (key: string | null) => {
    const tab = workspaceTabList?.find((item) => String(item.id) === String(key));

    setActiveConsoleId(key);

    const environment = getConnectionEnvironment(tab?.uniqueData?.dataSourceId);
    
    if (isReleaseEnvironment(environment)) {
      const envKey = environment?.id?.toString() || 'release';
      if (prevEnvironmentRef.current !== envKey) {
        message.warning(i18n('workspace.tips.enterReleaseEnvironment'));
        prevEnvironmentRef.current = envKey;
      }
    } else {
      prevEnvironmentRef.current = null;
    }
  };

  // 编辑名称
  const editableNameOnBlur = (t: ITabItem, value: string) => {
    const _params: any = {
      id: t.key,
      name: value,
    };
    historyService.updateSavedConsole(_params);

    const _workspaceTabList: any =
      workspaceTabList?.map((item) => {
        if (item.id === t.key) {
          return {
            ...item,
            title: value,
          };
        }
        return item;
      }) || [];
    setWorkspaceTabList(_workspaceTabList);
  };

  // AI 生成标题
  const handleGenerateTitle = async (t: ITabItem) => {
    const consoleId = t.key as number;
    const tabData = workspaceTabList?.find((item) => item.id === consoleId);
    if (!tabData) return;

    // 获取 SQL 内容，优先从编辑器实例获取最新内容
    let sqlContent = '';
    const editorRef = getConsoleEditor(consoleId);
    if (editorRef?.current?.editorRef) {
      sqlContent = editorRef.current.editorRef.getAllContent() || '';
    }
    
    // 如果编辑器实例中没有内容，尝试从 indexedDB 获取（可能有未保存的修改）
    if (!sqlContent) {
      try {
        const userId = getCookie('CHAT2DB.USER_ID');
        const cachedData: any = await indexedDB.getDataByCursor('chat2db', 'workspaceConsoleDDL', {
          consoleId,
          userId,
        });
        if (cachedData?.[0]?.ddl) {
          sqlContent = cachedData[0].ddl;
        } else {
          sqlContent = tabData.uniqueData?.ddl || '';
        }
      } catch {
        sqlContent = tabData.uniqueData?.ddl || '';
      }
    }

    if (!sqlContent?.trim()) {
      message.warning(i18n('workspace.tips.noSqlContent'));
      return;
    }

    setGeneratingTitleKey(consoleId);
    const uid = uuidv4();
    let generatedTitle = '';

    const params = formatParams({
      message: sqlContent,
      promptType: 'TITLE_GENERATION',
      dataSourceId: tabData.uniqueData?.dataSourceId,
      databaseName: tabData.uniqueData?.databaseName,
      schemaName: tabData.uniqueData?.schemaName,
    });

    const handleMessage = (content?: string) => {
      if (content) {
        generatedTitle += content;
      }
    };

    const handleDone = () => {
      setGeneratingTitleKey(null);
      // 清理标题，去除引号和多余空白
      const cleanTitle = generatedTitle.replace(/^["'`]+|["'`]+$/g, '').trim();
      if (cleanTitle) {
        const _params: any = {
          id: consoleId,
          name: cleanTitle,
        };
        historyService.updateSavedConsole(_params);

        const _workspaceTabList: any =
          workspaceTabList?.map((item) => {
            if (item.id === consoleId) {
              return {
                ...item,
                title: cleanTitle,
              };
            }
            return item;
          }) || [];
        setWorkspaceTabList(_workspaceTabList);
        message.success(i18n('common.tips.updateSuccess'));
      } else {
        message.warning(i18n('workspace.tips.generateTitleFailed'));
      }
    };

    const handleError = (error: string) => {
      console.error('AI chat error:', error);
      message.error(i18n('workspace.tips.generateTitleFailed'));
      setGeneratingTitleKey(null);
    };

    closeEventSourceRef.current = connectToEventSource({
      url: `/api/ai/chat?${params}`,
      uid,
      onOpen: () => {},
      onMessage: handleMessage,
      onDone: handleDone,
      onError: handleError,
    });
  };

  // 修改tab详情
  const changeTabDetails = (data: IWorkspaceTab) => {
    const list =
      workspaceTabList?.map((t) => {
        if (t.id === data.id) {
          return data;
        }
        return t;
      }) || [];
    setWorkspaceTabList(list);
  };

  // 渲染sql执行器
  const renderSQLExecute = (item: IWorkspaceTab) => {
    const { uniqueData } = item;
    
    // 动态创建 loadSQL (当从 consoleList 恢复时 loadSQL 会丢失)
    const getLoadSQL = () => {
      if (uniqueData.loadSQL) {
        return uniqueData.loadSQL;
      }
      
      // 根据 tab 类型动态创建 loadSQL
      const commonParams = {
        dataSourceId: uniqueData.dataSourceId,
        databaseName: uniqueData?.databaseName,
        schemaName: uniqueData?.schemaName,
      };
      
      switch (item.type) {
        case WorkspaceTabType.FUNCTION:
          return () => {
            return sqlService.getFunctionDetail({
              ...commonParams,
              functionName: uniqueData.functionName || uniqueData.name,
            } as any).then((res) => {
              return res.functionBody || '';
            });
          };
          
        case WorkspaceTabType.PROCEDURE:
          return () => {
            return sqlService.getProcedureDetail({
              ...commonParams,
              procedureName: uniqueData.procedureName || uniqueData.name,
            } as any).then((res) => {
              return res.procedureBody || '';
            });
          };
          
        case WorkspaceTabType.TRIGGER:
          return () => {
            return sqlService.getTriggerDetail({
              ...commonParams,
              triggerName: uniqueData.triggerName || uniqueData.name,
            } as any).then((res) => {
              return res.triggerBody || '';
            });
          };
          
        case WorkspaceTabType.VIEW:
          return () => {
            return sqlService.getViewDetail({
              ...commonParams,
              tableName: uniqueData.tableName || uniqueData.name,
            } as any).then((res) => {
              return res.ddl || '';
            });
          };
          
        default:
          return undefined;
      }
    };
    
    const loadSQL = getLoadSQL();
    
    return (
      <SQLExecute
        boundInfo={{
          dataSourceId: uniqueData.dataSourceId,
          dataSourceName: uniqueData.dataSourceName,
          databaseName: uniqueData?.databaseName,
          databaseType: uniqueData.databaseType,
          schemaName: uniqueData?.schemaName,
          consoleId: item.id as number,
          status: uniqueData.status,
          connectable: uniqueData.connectable,
          supportDatabase: uniqueData.supportDatabase,
          supportSchema: uniqueData.supportSchema,
        }}
        initDDL={uniqueData.ddl}
        loadSQL={loadSQL}
      />
    );
  };

  // 渲染表格编辑器
  const renderTableEditor = (item: IWorkspaceTab) => {
    const { uniqueData } = item;
    return (
      <DatabaseTableEditor
        tabDetails={item}
        changeTabDetails={changeTabDetails}
        dataSourceId={uniqueData.dataSourceId}
        databaseName={uniqueData.databaseName!}
        databaseType={uniqueData?.databaseType}
        schemaName={uniqueData?.schemaName}
        tableName={uniqueData.tableName}
        submitCallback={uniqueData.submitCallback}
      />
    );
  };

  // 渲染搜索结果
  const renderSearchResult = (item: IWorkspaceTab) => {
    const { uniqueData } = item;
    return (
      <SearchResult
        isActive={activeConsoleId === item.id}
        sql={uniqueData.sql}
        executeSqlParams={uniqueData}
        viewTable
        concealTabHeader
      />
    );
  };

  // 渲染所有表
  const renderViewAllTable = (item: IWorkspaceTab) => {
    const { uniqueData } = item;
    return <ViewAllTable uniqueData={uniqueData} />;
  };

  // 根据不同的tab类型渲染不同的内容
  const workspaceTabConnectionMap = (item: IWorkspaceTab) => {
    switch (item.type) {
      case 'table' as any: // 为了兼容老数据
      case null as any: // 为了兼容老数据
      case WorkspaceTabType.CONSOLE:
      case WorkspaceTabType.FUNCTION:
      case WorkspaceTabType.PROCEDURE:
      case WorkspaceTabType.TRIGGER:
      case WorkspaceTabType.VIEW:
        return renderSQLExecute(item);
      case WorkspaceTabType.EditTable:
      case WorkspaceTabType.CreateTable:
        return renderTableEditor(item);
      case WorkspaceTabType.EditTableData:
        return renderSearchResult(item);
      case WorkspaceTabType.ViewAllTable:
        return renderViewAllTable(item);
      case WorkspaceTabType.RedisData:
        return <RedisDataView uniqueData={item.uniqueData} />;
      case WorkspaceTabType.ViewERDiagram:
        return renderViewERDiagram(item);
      case WorkspaceTabType.SchemaDiff:
        return <SchemaDiffPanel />;
      default:
        return <div>Unknown</div>;
    }
  };

  // 渲染 ER 图
  const renderViewERDiagram = (item: IWorkspaceTab) => {
    const { uniqueData } = item;
    return <ERDiagram uniqueData={uniqueData} />;
  };
  // 判断是否是可以生成标题的 tab 类型
  const canGenerateTitleType = (type: WorkspaceTabType) => {
    return (
      type === WorkspaceTabType.CONSOLE ||
      type === WorkspaceTabType.FUNCTION ||
      type === WorkspaceTabType.PROCEDURE ||
      type === WorkspaceTabType.TRIGGER ||
      type === WorkspaceTabType.VIEW ||
      type === ('table' as any) ||
      !type
    );
  };

  const getEnvLabelStyle = (environment: { name: string; shortName: string } | null, isActive: boolean) => {
    if (!environment || !isActive) return {};
    const envName = environment.name?.toLowerCase();
    const envShortName = environment.shortName?.toLowerCase();
    if (envName?.includes('test') || envShortName === 'test') return { color: '#1890ff' };
    if (envName?.includes('release') || envShortName === 'release') return { color: '#ff4d4f' };
    return {};
  };

  // tab 列表
  const workspaceTabItems = useMemo(() => {
    return workspaceTabList?.map((item) => {
      const environment = getConnectionEnvironment(item.uniqueData?.dataSourceId);
      const isGeneratingTitle = generatingTitleKey === item.id;
      const isActive = activeConsoleId === item.id;
      return {
        prefixIcon: isGeneratingTitle
          ? <Spin indicator={<LoadingOutlined spin />} size="small" />
          : workspaceTabConfig[item.type]?.icon,
        label: (
          <div className={styles.tabLabel}>
            {environment && (
              <span className={styles.envTag} style={{ background: environment.color?.toLocaleLowerCase() }} />
            )}
            <span className={styles.tabTitle} style={getEnvLabelStyle(environment, isActive)}>
              {isGeneratingTitle ? i18n('common.text.generatingTitle') : item.title}
            </span>
          </div>
        ),
        name: isGeneratingTitle ? i18n('common.text.generatingTitle') : item.title,
        key: item.id,
        editableName: item.type === WorkspaceTabType.CONSOLE,
        canGenerateTitle: canGenerateTitleType(item.type),
        children: <Fragment key={item.id}>{workspaceTabConnectionMap(item)}</Fragment>,
      };
    });
  }, [workspaceTabList, activeConsoleId, connectionList, generatingTitleKey]);

  function renderCreateConsoleButton() {
    return (
      <div className={styles.createButtonBox}>
        <Button
          className={styles.createButton}
          type="primary"
          onClick={() => {
            createNewConsole();
          }}
        >
          <Iconfont code="&#xe63a;" />
          {i18n('common.button.createConsole')}
        </Button>
      </div>
    );
  }

  return workspaceTabItems?.length ? (
    <Tabs
      className={styles.tabBox}
      onChange={onTabChange as any}
      onEdit={handelTabsEdit as any}
      activeKey={activeConsoleId}
      editableNameOnBlur={editableNameOnBlur}
      items={workspaceTabItems}
      onGenerateTitle={handleGenerateTitle}
      generatingTitleKey={generatingTitleKey}
    />
  ) : (
    <div className={styles.ears}>
      <ShortcutKey slot={renderCreateConsoleButton} />
    </div>
  );
});

export default WorkspaceTabs;
