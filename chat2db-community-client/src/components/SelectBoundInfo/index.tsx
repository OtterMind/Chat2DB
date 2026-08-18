import { useEffect, useMemo, useRef, useState, memo, Fragment } from 'react';
import { ChevronRight } from 'lucide-react';
import { Dropdown, Input } from 'antd';
import { useStyles } from './style';
import { TreeNodeType, databaseMap, DatabaseTypeCode } from '@/constants';
import { normalizeTreeNodeLoadResult, treeConfig, switchIcon } from '@/blocks/NewTree/treeConfig';
import isEqual from 'lodash/isEqual';
import { IconfontSvg, ToolbarBtn } from '@chat2db/ui';
import { useTreeStore } from '@/store/tree';
import { TreeNodeData, type IConnectionEnv } from '@/typings';
import { getDatabaseSupport } from '@/utils/database';
import i18n from '@/i18n';
import DataSourceIdentityMark from '@/components/DataSourceIdentityMark';
import {
  activateCascadeRequestGuard,
  beginCascadeRequest,
  createCascadeRequestGuard,
  disposeCascadeRequestGuard,
  getCascadeRequestContextKey,
  invalidateCascadeRequest,
  isCascadeRequestCurrent,
} from './cascadeRequestGuard';
import { createCachedDataSourceSelection } from './dataSourceSelection';

export interface EachOption {
  value?: string; // Currently selected value.
  label?: string; // Currently selected label.
  title?: string; // Currently selected title.
  options: any[]; // Options for the current item.
  treeNodeType: TreeNodeType; // Current item type.
  databaseType?: DatabaseTypeCode; // Selected database type.
  dataSourceId?: number;
  environmentId?: number | null;
  environment?: IConnectionEnv | null;
  identityColor?: string | null;
  watermarkEnabled?: boolean | null;
  watermarkContent?: string | null;
  searchText?: string;
  hasPermission?: boolean; // Whether the data source is accessible.
  display?: boolean; // Whether to show the item.
}

export interface BoundInfo {
  dataSourceId?: number;
  dataSourceName?: string;
  environmentId?: number | null;
  environment?: IConnectionEnv | null;
  identityColor?: string | null;
  watermarkEnabled?: boolean | null;
  watermarkContent?: string | null;
  databaseType?: DatabaseTypeCode;
  databaseName?: string;
  schemaName?: string;
  tableName?: string;
}

interface IProps {
  boundInfo: BoundInfo;
  onChangeDBInfo: (boundInfo: BoundInfo) => void;
  // Whether empty values may be selected.
  allowEmpty?: boolean;
  // Whether data sources may be selected.
  allowSelectDataSource?: boolean;
  // Whether every node must have a value.
  mustHaveValue?: boolean;
}

interface CascadeOptionsState {
  contextKey: string;
  options: any[];
}

// Generate options.
const generateOptions = (treeDataList: TreeNodeData[] | null, allowEmpty, styles) => {
  if (!treeDataList?.length) return [];
  const options: any = treeDataList.map((item) => {
    const environmentName = item.extraParams.environment?.shortName || item.extraParams.environment?.name;
    return {
      label: (
        <span>
          {item.originalTitle}
          {environmentName && <span className={styles.environmentName}>{environmentName}</span>}
          {!item.extraParams.hasPermission && item.treeNodeType === TreeNodeType.DATA_SOURCE && (
            <span className={styles.noPermission}>({i18n('common.text.noPermission')})</span>
          )}
        </span>
      ),
      title: item.originalTitle,
      // label: item.originalTitle,
      value: item.id?.toString() || item.originalTitle,
      key: item.id?.toString() || item.originalTitle,
      treeNodeType: item.treeNodeType,
      databaseType: item.extraParams.databaseType,
      dataSourceId: item.extraParams.dataSourceId,
      environmentId: item.extraParams.environmentId,
      environment: item.extraParams.environment,
      identityColor: item.extraParams.identityColor,
      watermarkEnabled: item.extraParams.watermarkEnabled,
      watermarkContent: item.extraParams.watermarkContent,
      searchText: [item.originalTitle, environmentName]
        .filter(Boolean)
        .join(' ')
        .toLowerCase(),
      hasPermission: item.extraParams.hasPermission,
    };
  });
  if (allowEmpty) {
    options.unshift({
      label: '',
      value: '',
      key: '',
      treeNodeType: treeDataList[0].treeNodeType,
    });
  }
  return options || [];
};

const SelectBoundInfo = memo(
  (props: IProps) => {
    const { boundInfo, allowEmpty = false, allowSelectDataSource = true, mustHaveValue } = props;
    const { styles } = useStyles();

    const { dataSourceList, getTreeData } = useTreeStore((s) => ({
      dataSourceList: s.dataSourceList,
      getTreeData: s.getTreeData,
    }));
    const [selectedList, setSelectedList] = useState<EachOption[]>([]);
    const [databaseOptionsState, setDatabaseOptionsState] = useState<CascadeOptionsState>({
      contextKey: '',
      options: [],
    });
    const [schemaOptionsState, setSchemaOptionsState] = useState<CascadeOptionsState>({
      contextKey: '',
      options: [],
    });
    const cascadeRequestGuardRef = useRef(createCascadeRequestGuard());
    const boundInfoRef = useRef(boundInfo);
    boundInfoRef.current = boundInfo;

    const getOptions = (treeNodeType: TreeNodeType, _boundInfo?) => {
      return treeConfig[treeNodeType]
        ?.getChildren?.({ ...(_boundInfo || boundInfo), needAiDataCollections: false })
        .then((result) => normalizeTreeNodeLoadResult(result).children);
    };

    // Check whether the current data source is accessible.
    const getDatasourceHasPermission = (dataSourceId: number) => {
      let hasPermission = false;
      dataSourceList?.forEach((item) => {
        if (item.id === dataSourceId) {
          hasPermission = item.extraParams.hasPermission!;
          return hasPermission;
        }
      });
      return hasPermission;
    };

    useEffect(() => {
      if (dataSourceList === null) {
        getTreeData();
      }
    }, [dataSourceList, getTreeData]);

    const dataSourceOptions = useMemo(() => {
      return generateOptions(dataSourceList, false, styles);
    }, [dataSourceList]);

    const isValidDataSource = useMemo(() => {
      if (!dataSourceList?.length) return false;
      return getDatasourceHasPermission(boundInfo.dataSourceId!);
    }, [dataSourceList, boundInfo.dataSourceId]);

    const databaseOptions = useMemo(
      () =>
        databaseOptionsState.contextKey === getCascadeRequestContextKey('database', boundInfo)
          ? databaseOptionsState.options
          : [],
      [databaseOptionsState, boundInfo.dataSourceId],
    );
    const schemaOptions = useMemo(
      () =>
        schemaOptionsState.contextKey === getCascadeRequestContextKey('schema', boundInfo)
          ? schemaOptionsState.options
          : [],
      [schemaOptionsState, boundInfo.dataSourceId, boundInfo.databaseName],
    );

    useEffect(() => {
      activateCascadeRequestGuard(cascadeRequestGuardRef.current);
      return () => {
        disposeCascadeRequestGuard(cascadeRequestGuardRef.current);
      };
    }, []);

    useEffect(() => {
      const requestContext = { ...boundInfo };
      if (!dataSourceOptions.length || !requestContext.dataSourceId || !isValidDataSource) {
        invalidateCascadeRequest(cascadeRequestGuardRef.current, 'database');
        setDatabaseOptionsState({
          contextKey: getCascadeRequestContextKey('database', requestContext),
          options: [],
        });
        return;
      }

      const requestToken = beginCascadeRequest(cascadeRequestGuardRef.current, 'database', requestContext);
      getOptions(TreeNodeType.DATA_SOURCE, requestContext)
        ?.then((res) => {
          if (!isCascadeRequestCurrent(cascadeRequestGuardRef.current, requestToken, boundInfoRef.current)) {
            return;
          }
          setDatabaseOptionsState({
            contextKey: requestToken.contextKey,
            options: generateOptions(res, allowEmpty, styles),
          });
        })
        .catch(() => {
          if (!isCascadeRequestCurrent(cascadeRequestGuardRef.current, requestToken, boundInfoRef.current)) {
            return;
          }
          setDatabaseOptionsState({ contextKey: requestToken.contextKey, options: [] });
        });

      return () => {
        invalidateCascadeRequest(cascadeRequestGuardRef.current, 'database');
      };
    }, [dataSourceOptions, boundInfo.dataSourceId, boundInfo.databaseType, isValidDataSource]);

    useEffect(() => {
      const requestContext = { ...boundInfo };
      const { supportDatabase, supportSchema } = getDatabaseSupport(requestContext.databaseType);
      if (
        !requestContext.dataSourceId ||
        !isValidDataSource ||
        !supportSchema ||
        (supportDatabase && !requestContext.databaseName)
      ) {
        invalidateCascadeRequest(cascadeRequestGuardRef.current, 'schema');
        setSchemaOptionsState({
          contextKey: getCascadeRequestContextKey('schema', requestContext),
          options: [],
        });
        return;
      }

      const requestToken = beginCascadeRequest(cascadeRequestGuardRef.current, 'schema', requestContext);
      getOptions(TreeNodeType.DATABASE, requestContext)
        ?.then((res) => {
          if (!isCascadeRequestCurrent(cascadeRequestGuardRef.current, requestToken, boundInfoRef.current)) {
            return;
          }
          setSchemaOptionsState({
            contextKey: requestToken.contextKey,
            options: generateOptions(res, allowEmpty, styles),
          });
        })
        .catch(() => {
          if (!isCascadeRequestCurrent(cascadeRequestGuardRef.current, requestToken, boundInfoRef.current)) {
            return;
          }
          setSchemaOptionsState({ contextKey: requestToken.contextKey, options: [] });
        });

      return () => {
        invalidateCascadeRequest(cascadeRequestGuardRef.current, 'schema');
      };
    }, [boundInfo.dataSourceId, boundInfo.databaseName, boundInfo.databaseType, isValidDataSource]);

    useEffect(() => {
      const cachedDataSourceSelection = createCachedDataSourceSelection(boundInfo);
      // Disable all selections when no data source exists.
      if (!dataSourceOptions.length) {
        setSelectedList(
          allowSelectDataSource && boundInfo.dataSourceId
            ? [
                {
                  ...cachedDataSourceSelection,
                  options: [],
                  treeNodeType: TreeNodeType.DATA_SOURCE,
                },
              ]
            : [],
        );
        return;
      }

      // Return selectable configuration when binding information is absent.
      const { dataSourceId, databaseName, schemaName, databaseType } = boundInfo;

      // Initialize data-source options.
      const _defaultSelectedList: EachOption[] = [];
      if (allowSelectDataSource) {
        const selectedDataSourceOption = dataSourceOptions.find((item) => item.value === dataSourceId?.toString());
        _defaultSelectedList.push({
          ...cachedDataSourceSelection,
          ...selectedDataSourceOption,
          value: selectedDataSourceOption?.value || cachedDataSourceSelection.value,
          // Resolve dataSourceName from dataSourceId when the name is missing.
          label: selectedDataSourceOption?.label || cachedDataSourceSelection.label,
          title: selectedDataSourceOption?.title || cachedDataSourceSelection.title,
          dataSourceId: selectedDataSourceOption?.dataSourceId ?? cachedDataSourceSelection.dataSourceId,
          options: dataSourceOptions,
          treeNodeType: TreeNodeType.DATA_SOURCE,
          databaseType,
        });
      }
      // Return only the data-source option when no data source is selected.
      if (!databaseType) {
        setSelectedList(_defaultSelectedList);
        return;
      }

      // Generate database and schema options from the configuration.
      const { supportDatabase, supportSchema } = getDatabaseSupport(databaseType);

      if (supportDatabase) {
        if (mustHaveValue && !databaseName && databaseOptions.length) {
          const latestBoundInfo = boundInfoRef.current;
          if (latestBoundInfo.dataSourceId !== dataSourceId || latestBoundInfo.databaseName) {
            return;
          }
          props.onChangeDBInfo({
            ...latestBoundInfo,
            databaseName: databaseOptions[0].value,
          });
          return;
        }
        _defaultSelectedList.push({
          value: databaseName,
          label: databaseName,
          treeNodeType: TreeNodeType.DATABASE,
          options: databaseOptions,
        });
      }

      if (supportSchema) {
        if (mustHaveValue && !schemaName && schemaOptions.length) {
          const latestBoundInfo = boundInfoRef.current;
          if (
            latestBoundInfo.dataSourceId !== dataSourceId ||
            latestBoundInfo.databaseName !== databaseName ||
            latestBoundInfo.schemaName
          ) {
            return;
          }
          props.onChangeDBInfo({
            ...latestBoundInfo,
            schemaName: schemaOptions[0].value,
          });
          return;
        }
        _defaultSelectedList.push({
          value: schemaName,
          label: schemaName,
          treeNodeType: TreeNodeType.SCHEMA,
          options: schemaOptions,
        });
      }
      setSelectedList(_defaultSelectedList);
    }, [boundInfo, dataSourceList, databaseOptions, schemaOptions]);

    const handleOptionChange = (option: EachOption) => {
      let _boundInfo = { ...boundInfo };
      // Handle a data-source change.
      if (option.treeNodeType === TreeNodeType.DATA_SOURCE) {
        _boundInfo = {
          ..._boundInfo,
          dataSourceId: Number(option.value),
          dataSourceName: option.title,
          environmentId: option.environmentId,
          environment: option.environment,
          identityColor: option.identityColor,
          watermarkEnabled: option.watermarkEnabled,
          watermarkContent: option.watermarkContent,
          databaseType: option.databaseType,
          databaseName: undefined,
          schemaName: undefined,
        };
        props.onChangeDBInfo(_boundInfo);
      } else if (option.treeNodeType === TreeNodeType.DATABASE) {
        // Handle a database change.
        _boundInfo = {
          ..._boundInfo,
          databaseName: option.value,
          schemaName: undefined,
        };
        props.onChangeDBInfo(_boundInfo);
      } else if (option.treeNodeType === TreeNodeType.SCHEMA) {
        // Handle a schema change.
        _boundInfo = {
          ..._boundInfo,
          schemaName: option.value,
        };
        props.onChangeDBInfo(_boundInfo);
      }
    };

    return (
      <div className={styles.selectBoundInfo}>
        {selectedList.map((item) => {
          return <DropdownItem eachOption={item} key={item.treeNodeType} handleOptionChange={handleOptionChange} />;
        })}
      </div>
    );
  },
  (prevProps, nextProps) => {
    return isEqual(prevProps, nextProps);
  },
);

interface DropdownProps {
  eachOption: EachOption;
  handleOptionChange: (option: EachOption) => void;
}

const DropdownItem = memo((props: DropdownProps) => {
  const { eachOption, handleOptionChange } = props;
  const options = eachOption.options || [];
  const [searchText, setSearchText] = useState('');

  const {
    styles,
    theme: { appearance },
  } = useStyles();

  // Filter options.
  const filteredOptions = useMemo(() => {
    if (!searchText) return options;
    const normalizedSearchText = searchText.toLowerCase();
    return options.filter((item) => {
      return (
        item.searchText?.includes(normalizedSearchText) || item.title?.toLowerCase().includes(normalizedSearchText)
      );
    });
  }, [options, searchText]);

  // Render the current node's icon.
  const currentIcon = useMemo(() => {
    if (eachOption.treeNodeType === TreeNodeType.DATA_SOURCE) {
      return (
        <span className={styles.dataSourceIdentityIcon}>
          <DataSourceIdentityMark dataSourceId={eachOption.dataSourceId} size={7} />
          <IconfontSvg
            size="md"
            existDark={databaseMap[eachOption.databaseType!]?.iconExistDark}
            appearance={appearance}
            code={databaseMap[eachOption.databaseType!]?.icon}
          />
        </span>
      );
    }

    return (
      <IconfontSvg
        size="md"
        code={switchIcon[eachOption.treeNodeType]!.icon}
        existDark={switchIcon[eachOption.treeNodeType]!.iconExistDark}
        appearance={appearance}
      />
    );
  }, [eachOption]);

  const changeOption = (e) => {
    eachOption.options?.forEach((element) => {
      if (element.key === e.key) {
        handleOptionChange(element);
        setSearchText(''); // Clear the search after selection.
      }
    });
  };

  if (eachOption.display) return null;

  const dropdownRender = (menu) => (
    <div className={styles.dropdownContent}>
      <Input
        placeholder={i18n('common.text.search')}
        value={searchText}
        onChange={(e) => setSearchText(e.target.value)}
        prefix={<IconfontSvg code="icon-search" size={16} />}
        allowClear
      />
      {menu}
      {/* Show the empty state when no data is available. */}
      {filteredOptions.length === 0 && <div className={styles.noData}>{i18n('common.text.noSearchResult')}</div>}
    </div>
  );

  return (
    <Fragment>
      {options.length > 0 ? (
        <Dropdown
          destroyPopupOnHide
          dropdownRender={dropdownRender}
          menu={{
            items: filteredOptions.map((item) => {
              return {
                key: item.key,
                value: item.value,
                label: (
                  <div className={styles.dropdownItemLabel}>
                    {eachOption.treeNodeType === TreeNodeType.DATA_SOURCE && (
                      <span className={styles.dataSourceIdentityIcon}>
                        <DataSourceIdentityMark dataSourceId={item.dataSourceId} size={7} />
                        <IconfontSvg
                          size="md"
                          existDark={databaseMap[item.databaseType!]?.iconExistDark}
                          appearance={appearance}
                          code={databaseMap[item.databaseType!]?.icon}
                        />
                      </span>
                    )}
                    {item?.label}
                  </div>
                ),
              };
            }),
            onClick: changeOption,
          }}
          trigger={['click']}
        >
          <ToolbarBtn
            className={styles.toolbarBtn}
            prefixIcon={currentIcon}
            text={eachOption?.label || `<${eachOption.treeNodeType}>`}
            suffixIcon={<ChevronRight size={14} className={styles.suffixIcon} />}
          />
        </Dropdown>
      ) : (
        <ToolbarBtn
          className={styles.toolbarBtn}
          prefixIcon={currentIcon}
          text={eachOption?.label || `<${eachOption.treeNodeType}>`}
          suffixIcon={<ChevronRight size={14} className={styles.suffixIcon} />}
        />
      )}
    </Fragment>
  );
});

export default SelectBoundInfo;
