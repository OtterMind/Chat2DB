import { useEffect, useMemo, useState, memo, Fragment } from 'react';
import { ChevronRight } from 'lucide-react';
import { Dropdown, Input } from 'antd';
import { useStyles } from './style';
import { TreeNodeType, databaseMap, DatabaseTypeCode } from '@/constants';
import { normalizeTreeNodeLoadResult, treeConfig, switchIcon } from '@/blocks/NewTree/treeConfig';
import isEqual from 'lodash/isEqual';
import { IconfontSvg, ToolbarBtn } from '@chat2db/ui';
import { useTreeStore } from '@/store/tree';
import { TreeNodeData } from '@/typings';
import { getDatabaseSupport } from '@/utils/database';
import i18n from '@/i18n';

export interface EachOption {
  value?: string; // Currently selected value.
  label?: string; // Currently selected label.
  title?: string; // Currently selected title.
  options: any[]; // Options for the current item.
  treeNodeType: TreeNodeType; // Current item type.
  databaseType?: DatabaseTypeCode; // Selected database type.
  hasPermission?: boolean; // Whether the data source is accessible.
  display?: boolean; // Whether to show the item.
}

export interface BoundInfo {
  dataSourceId?: number;
  dataSourceName?: string;
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

// Generate options.
const generateOptions = (treeDataList: TreeNodeData[] | null, allowEmpty, styles) => {
  if (!treeDataList?.length) return [];
  const options: any = treeDataList.map((item) => {
    return {
      label: (
        <span>
          {item.originalTitle}
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
    const [databaseOptions, setDatabaseOptions] = useState<any>([]);
    const [schemaOptions, setSchemaOptions] = useState<any>([]);

    const getOptions = (treeNodeType: TreeNodeType, _boundInfo?) => {
      return treeConfig[treeNodeType]?.getChildren
        ?.({ ...(_boundInfo || boundInfo), needAiDataCollections: false })
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
    }, [dataSourceList]);

    const dataSourceOptions = useMemo(() => {
      return generateOptions(dataSourceList, false, styles);
    }, [dataSourceList]);

    const isValidDataSource = useMemo(() => {
      if (!dataSourceList?.length) return false;
      return getDatasourceHasPermission(boundInfo.dataSourceId!);
    }, [dataSourceList, boundInfo.dataSourceId]);

    useEffect(() => {
      if (!dataSourceOptions.length || !boundInfo.dataSourceId || !isValidDataSource) return;
      getOptions(TreeNodeType.DATA_SOURCE)?.then((res) => {
        setDatabaseOptions(generateOptions(res, allowEmpty, styles));
      });
    }, [dataSourceOptions, boundInfo.dataSourceId]);

    useEffect(() => {
      if (!databaseOptions.length) return;
      getOptions(TreeNodeType.DATABASE)?.then((res) => {
        setSchemaOptions(generateOptions(res, allowEmpty, styles));
      });
    }, [databaseOptions]);

    useEffect(() => {
  // Disable all selections when no data source exists.
      if (!dataSourceOptions.length) {
        setSelectedList([]);
        return;
      }

  // Return selectable configuration when binding information is absent.
      const { dataSourceId, databaseName, schemaName, databaseType } = boundInfo;

  // Initialize data-source options.
      const _defaultSelectedList: EachOption[] = [];
      if (allowSelectDataSource) {
        _defaultSelectedList.push({
          value: dataSourceId?.toString() || '',
  // Resolve dataSourceName from dataSourceId when the name is missing.
          label: dataSourceOptions.find((item) => item.value === dataSourceId?.toString())?.label || '',
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
          props.onChangeDBInfo({
            ...boundInfo,
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
          props.onChangeDBInfo({
            ...boundInfo,
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
      let requestNodeType: any = undefined;
      let setOptionsFN: any = null;
      let _boundInfo = { ...boundInfo };
    // Handle a data-source change.
      if (option.treeNodeType === TreeNodeType.DATA_SOURCE) {
        _boundInfo = {
          ..._boundInfo,
          dataSourceId: Number(option.value),
          dataSourceName: option.title,
          databaseType: option.databaseType,
          databaseName: undefined,
          schemaName: undefined,
        };
        const supportDatabase = databaseMap[option.databaseType!].supportDatabase;
        requestNodeType = TreeNodeType.DATA_SOURCE;
        setOptionsFN = supportDatabase ? setDatabaseOptions : setSchemaOptions;
        setDatabaseOptions([]);
        setSchemaOptions([]);
        props.onChangeDBInfo(_boundInfo);
        if (!option.value) {
          return;
        }
      } else if (option.treeNodeType === TreeNodeType.DATABASE) {
    // Handle a database change.
        _boundInfo = {
          ..._boundInfo,
          databaseName: option.value,
          schemaName: undefined,
        };
        requestNodeType = TreeNodeType.DATABASE;
        setOptionsFN = setSchemaOptions;
        setSchemaOptions([]);
        props.onChangeDBInfo(_boundInfo);
        if (!option.value) {
          return;
        }
      } else if (option.treeNodeType === TreeNodeType.SCHEMA) {
    // Handle a schema change.
        _boundInfo = {
          ..._boundInfo,
          schemaName: option.value,
        };
        props.onChangeDBInfo(_boundInfo);
        if (!option.value) {
          return;
        }
      }

      const datasourceHasPermission = getDatasourceHasPermission(_boundInfo.dataSourceId!);
      if (!requestNodeType || !datasourceHasPermission) return;
      getOptions(requestNodeType, _boundInfo)?.then((res) => {
        setOptionsFN(generateOptions(res, allowEmpty, styles));
      });
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
    return options.filter((item) => {
      const labelText = item.label?.props?.children?.[1]?.toString().toLowerCase();
      const titleText = item.title?.toLowerCase();
      return (
        (labelText && labelText.includes(searchText.toLowerCase())) ||
        (titleText && titleText.includes(searchText.toLowerCase()))
      );
    });
  }, [options, searchText]);

  // Render the current node's icon.
  const currentIcon = useMemo(() => {
    if (eachOption.treeNodeType === TreeNodeType.DATA_SOURCE) {
      return (
        <IconfontSvg
          size="md"
          existDark={databaseMap[eachOption.databaseType!]?.iconExistDark}
          appearance={appearance}
          code={databaseMap[eachOption.databaseType!]?.icon}
        />
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
                      <IconfontSvg
                        size="md"
                        existDark={databaseMap[item.databaseType!]?.iconExistDark}
                        appearance={appearance}
                        code={databaseMap[item.databaseType!]?.icon}
                      />
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
