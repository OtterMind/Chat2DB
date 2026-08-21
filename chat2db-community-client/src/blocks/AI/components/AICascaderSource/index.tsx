import useTrimTreeData from '@/blocks/NewTree/hooks/useTrimTreeData';
import { ILoadDataOptions, switchIcon, treeConfig } from '@/blocks/NewTree/treeConfig';
import { TreeNodeType, databaseMap } from '@/constants';
import i18n from '@/i18n';
import { useTreeStore } from '@/store/tree';
import { TreeNodeData } from '@/typings';
import { IDBContextInfo } from '@/typings/database';
import { findNode } from '@/utils';
import { IconfontSvg } from '@chat2db/ui';
import { Cascader, Tooltip } from 'antd';
import { useMemo } from 'react';
import { ChevronDown } from 'lucide-react';
import { useStyles } from './style';

export type IAICascaderData = IDBContextInfo | null;

interface IAICascaderOption {
  originalTitle: string;
  key: string;
  iconCode?: string;
  iconExistDark?: boolean;
  treeNodeType?: TreeNodeType;
  extraParams?: any;
  children?: IAICascaderOption[];
  isLeaf?: boolean;
}

interface IProps {
  contextInfo: IAICascaderData;
  onChange?: (contextInfo: IAICascaderData) => void;
  onFileSelect?: () => void;
}

const AICascaderSource = (props: IProps) => {
  const { contextInfo, onChange, onFileSelect } = props;
  const {
    styles,
    theme: { appearance },
  } = useStyles();
  const treeData = useTrimTreeData();

  const { handleLoadData } = useTreeStore((state) => ({
    handleLoadData: state.handleLoadData,
  }));

  // recursively processes treeData
  const handleTreeData = (data: TreeNodeData[]) => {
    const list = data.map((item) => {
      let isLeaf = false;
      if (
        (item?.treeNodeType === TreeNodeType.DATABASE && !item?.extraParams?.supportSchema) ||
        item?.treeNodeType === TreeNodeType.SCHEMA
      ) {
        isLeaf = true;
        item.children = undefined;
      }

      return {
        ...item,
        isLeaf,
        children: item.children?.length ? handleTreeData(item.children) : undefined,
      };
    });
    return list.filter((item) => !(item?.treeNodeType === TreeNodeType.GROUP && !item.children?.length));
  };

  const treeDataOptions = useMemo(() => {
    return handleTreeData(treeData || []);
  }, [treeData]);

  const options = useMemo(() => {
    const nextOptions: IAICascaderOption[] = [
      {
        originalTitle: i18n('ai.select.globalDatabaseScope'),
        key: 'globalDatabaseScope',
        isLeaf: true,
      },
      {
        originalTitle: i18n('common.dataSource.title'),
        key: 'dataSource',
        iconCode: 'icon-database-nav',
        children: treeDataOptions,
      },
      {
        originalTitle: i18n('stream.source.files'),
        key: 'file',
        iconCode: 'icon-sql-file-1',
        isLeaf: true,
      },
    ];
    return nextOptions;
  }, [treeData]);

  const loadData = (selectedOptions: any) => {
    const data = selectedOptions[selectedOptions.length - 1];
    const loadDataOptions: ILoadDataOptions = {
      closeExpandTreeNode: true,
    };
    if (data.treeNodeType === TreeNodeType.GROUP) {
      return;
    }
    handleLoadData(data, loadDataOptions);
  };

  const cascaderValue = useMemo(() => {
    if (!contextInfo) {
      return [];
    }

    // Update data source
    if ('dataSourceId' in contextInfo) {
      const _dataSource = ['dataSource'];

      if (contextInfo.dataSourceId) {
        _dataSource.push(treeConfig[TreeNodeType.DATA_SOURCE]?.createTreeNodeKey?.(contextInfo) || '');
      }

      if (contextInfo.databaseName) {
        _dataSource.push(treeConfig[TreeNodeType.DATABASE]?.createTreeNodeKey?.(contextInfo) || '');
      }

      if (contextInfo.schemaName) {
        _dataSource.push(treeConfig[TreeNodeType.SCHEMA]?.createTreeNodeKey?.(contextInfo) || '');
      }

      return _dataSource;
    }

    return [];
  }, [contextInfo]);

  const handleChange = (_value: string[]) => {
    if (!_value || _value.length === 0) {
      onChange?.(null);
      return;
    }
    if (_value[0] === 'file') {
      onFileSelect?.();
      return;
    }
    if (_value[0] === 'globalDatabaseScope') {
      onChange?.(null);
      return;
    }
    // data source situation
    const lastKey = _value[_value.length - 1];
    const selectNode = findNode(lastKey, treeDataOptions);
    if (selectNode?.extraParams) {
      const _contextInfo = {
        dataSourceId: selectNode.extraParams.dataSourceId!,
        databaseName: selectNode.extraParams.databaseName,
        schemaName: selectNode.extraParams.schemaName,
        databaseType: selectNode.extraParams.databaseType,
        dataSourceName: selectNode.extraParams.dataSourceName,
      };
      onChange?.(_contextInfo);
    }
  };

  const renderIcon = (option) => {
    if (option?.iconCode) {
      return <IconfontSvg code={option.iconCode} existDark={option.iconExistDark} appearance={appearance} size={14} />;
    }
    if (option?.treeNodeType === TreeNodeType.DATA_SOURCE && databaseMap[option?.extraParams?.databaseType]) {
      return (
        <IconfontSvg
          size={14}
          existDark={databaseMap[option.extraParams.databaseType!]?.iconExistDark}
          appearance={appearance}
          code={databaseMap[option?.extraParams?.databaseType]?.icon}
        />
      );
    }
    if (switchIcon?.[option.treeNodeType]) {
      return (
        <IconfontSvg
          code={switchIcon[option.treeNodeType]!.icon}
          existDark={switchIcon[option.treeNodeType]!.iconExistDark}
          appearance={appearance}
          size={14}
        />
      );
    }

    return null;
  };

  const optionRender = (option) => {
    return (
      <div className={styles.dropdownRender}>
        {renderIcon(option)}
        {option.originalTitle}
      </div>
    );
  };

  const renderDisplayValue = (value, selectedOptions) => {
    if (value.length === 0) {
      return (
        <div className={styles.displayRenderPlus}>
          <IconfontSvg code="icon-add" size="sm" />
        </div>
      );
    }
    if (value.length === 1) {
      return (
        <div className={styles.displayRenderPlaceholder}>
          {selectedOptions?.[1] && renderIcon(selectedOptions[1])}
          {i18n('ai.select.database')}
        </div>
      );
    }

    const renderValue = () => {
      const newSelectedOptions = selectedOptions?.slice(1, value.length)?.filter(Boolean);
      if (contextInfo && 'dataSourceId' in contextInfo) {
        // Because databaseName and schemaName are not checked, they need to be added manually.
        newSelectedOptions[1] = {
          originalTitle: contextInfo.databaseName,
        };
        newSelectedOptions[2] = {
          originalTitle: contextInfo.schemaName,
        };
      }
      return newSelectedOptions
        ?.map((item) => {
          return item.originalTitle;
        })
        ?.filter(Boolean)
        ?.join('/');
    };
    return (
      <Tooltip title={renderValue()} className={styles.displayRender} mouseEnterDelay={0.8}>
        <div className={styles.dropdownRenderIcon}>{selectedOptions?.[1] && renderIcon(selectedOptions[1])}</div>
        <div className={styles.dropdownRenderTitle}>{renderValue()}</div>
      </Tooltip>
    );
  };

  return (
    <Cascader
      className={styles.wrapper}
      popupClassName={styles.popupContainer}
      value={cascaderValue}
      fieldNames={{ label: 'originalTitle', value: 'key' }}
      options={options as any}
      loadData={loadData}
      onChange={handleChange}
      optionRender={optionRender}
      suffixIcon={<ChevronDown size={14} />}
      prefix={
        cascaderValue.length === 0 ? (
          <div className={styles.displayRenderPlus}>
            <IconfontSvg code="icon-add" size="sm" />
          </div>
        ) : null
      }
      placeholder=""
      size="small"
      variant="borderless"
      placement="topLeft"
      displayRender={renderDisplayValue}
    />
  );
};

export default AICascaderSource;
