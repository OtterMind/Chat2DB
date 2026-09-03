import { normalizeTreeNodeLoadResult, treeConfig } from '@/blocks/NewTree/treeConfig';
import { DatabaseTypeCode, TreeNodeType } from '@/constants';
import { databaseMap } from '@/constants/database';
import { getDatabaseSupport } from '@/utils/database';
import { useEffect, useMemo, useRef, useState } from 'react';
import {
  activateSelectDatabaseRequests,
  createSelectDatabaseRequestLifecycle,
  disposeSelectDatabaseRequests,
  hasApplicableDatabaseNameChange,
  invalidateDatabaseOptionRequests,
  invalidateDataSourceOptionRequests,
  normalizeDatabaseOptions,
  normalizeSchemaOptions,
  runDatabaseOptionRequest,
  runSchemaOptionRequest,
  SelectDatabaseOption,
} from './selectDatabaseRequestLifecycle';

export type ISelectDatabase = {
  dataSourceId?: number;
  databaseType?: DatabaseTypeCode;
  databaseName?: string;
  schemaName?: string;
  supportSchema?: boolean;
  supportDatabase?: boolean;
  selectDone?: boolean;
} | null;

type IChangedValues = {
  dataSourceId?: number;
  databaseName?: string;
  schemaName?: string;
};

interface IUseSelectDatabaseProps {
  astrictDatabaseType?: DatabaseTypeCode;
}

interface DataSourceOption {
  value: number;
  label: string;
  databaseType: DatabaseTypeCode;
}

const useSelectDatabase = (props: IUseSelectDatabaseProps) => {
  const { astrictDatabaseType } = props;
  const [dataSourceList, setDataSourceList] = useState<DataSourceOption[] | null>([]);
  const [databaseList, setDatabaseList] = useState<SelectDatabaseOption[] | null>([]);
  const [schemaList, setSchemaList] = useState<SelectDatabaseOption[] | null>([]);
  const [selectDatabase, setSelectDatabase] = useState<ISelectDatabase>();
  const mountedRef = useRef(true);
  const requestLifecycleRef = useRef(createSelectDatabaseRequestLifecycle());
  const requestLifecycle = requestLifecycleRef.current;

  useEffect(() => {
    mountedRef.current = true;
    activateSelectDatabaseRequests(requestLifecycle);
    return () => {
      mountedRef.current = false;
      disposeSelectDatabaseRequests(requestLifecycle);
    };
  }, [requestLifecycle]);

  useEffect(() => {
    invalidateDataSourceOptionRequests(requestLifecycle);
    setDatabaseList([]);
    setSchemaList([]);

    if (astrictDatabaseType) {
      const { supportSchema, supportDatabase } = databaseMap[astrictDatabaseType];
      setSelectDatabase({
        dataSourceId: undefined,
        databaseName: undefined,
        schemaName: undefined,
        databaseType: undefined,
        supportSchema,
        supportDatabase,
      });
      return;
    }
    setSelectDatabase(null);
  }, [astrictDatabaseType, requestLifecycle]);

  const astrictDataSourceList = useMemo(() => {
    if (astrictDatabaseType) {
      return dataSourceList?.filter((item) => item.databaseType === astrictDatabaseType);
    }
    return dataSourceList;
  }, [dataSourceList, astrictDatabaseType]);

  const getDataSourceList = () => {
    setDataSourceList(null);
    invalidateDataSourceOptionRequests(requestLifecycle);
    setDatabaseList([]);
    setSchemaList([]);
    treeConfig[TreeNodeType.DATA_SOURCES]
      .getChildren?.({
        refresh: true,
      })
      .then((res) => {
        if (!mountedRef.current) {
          return;
        }
        const options = normalizeTreeNodeLoadResult(res).children.map((item) => ({
          value: item.extraParams.dataSourceId!,
          label: item.originalTitle,
          databaseType: item.extraParams.databaseType!,
        }));
        setDataSourceList(options);
      })
      .catch(() => {
        if (mountedRef.current) {
          setDataSourceList([]);
        }
      });
  };

  useEffect(() => {
    getDataSourceList();
  }, []);

  const getDatabaseList = (params: { dataSourceId: number; databaseType: DatabaseTypeCode }) => {
    invalidateDatabaseOptionRequests(requestLifecycle);
    setDatabaseList(null);
    setSchemaList([]);

    const getChildren = treeConfig[TreeNodeType.DATA_SOURCE].getChildren;
    if (!getChildren) {
      setDatabaseList([]);
      return;
    }

    void runDatabaseOptionRequest(
      requestLifecycle,
      () =>
        getChildren({
          ...params,
          refresh: true,
        }),
      (res) => setDatabaseList(normalizeDatabaseOptions(normalizeTreeNodeLoadResult(res).children)),
      () => setDatabaseList([]),
    );
  };

  const getSchemaList = (params: NonNullable<ISelectDatabase>) => {
    setSchemaList(null);

    const getChildren = treeConfig[TreeNodeType.DATABASE].getChildren;
    if (!getChildren) {
      setSchemaList([]);
      return;
    }

    void runSchemaOptionRequest(
      requestLifecycle,
      () =>
        getChildren({
          ...params,
          refresh: true,
        }),
      (res) => setSchemaList(normalizeSchemaOptions(normalizeTreeNodeLoadResult(res).children)),
      () => setSchemaList([]),
    );
  };

  const isSelectDone = (params: ISelectDatabase) => {
    if (params?.supportDatabase && !params.databaseName) {
      return false;
    }
    if (params?.supportSchema && !params.schemaName) {
      return false;
    }
    return true;
  };

  const resetSelectDatabase = (): ISelectDatabase => {
    if (!astrictDatabaseType) {
      return null;
    }
    const { supportSchema, supportDatabase } = databaseMap[astrictDatabaseType];
    return {
      databaseType: undefined,
      supportSchema,
      supportDatabase,
      selectDone: false,
    };
  };

  const onChangeSelectDatabase = (changedValues: IChangedValues) => {
    let newSelectDatabase: ISelectDatabase = {
      ...selectDatabase,
    };

    if ('dataSourceId' in changedValues) {
      invalidateDataSourceOptionRequests(requestLifecycle);
      setDatabaseList([]);
      setSchemaList([]);
      const dataSource = astrictDataSourceList?.find((item) => item.value === changedValues.dataSourceId);

      if (!dataSource) {
        if (changedValues.dataSourceId !== undefined) {
          return;
        }
        setSelectDatabase(resetSelectDatabase());
        return;
      }

      const databaseType = dataSource.databaseType;
      const { supportSchema, supportDatabase } = getDatabaseSupport(databaseType);
      newSelectDatabase = {
        dataSourceId: dataSource.value,
        databaseName: undefined,
        schemaName: undefined,
        selectDone: !supportDatabase && !supportSchema,
        databaseType,
        supportSchema,
        supportDatabase,
      };

      if (supportDatabase) {
        getDatabaseList({
          dataSourceId: dataSource.value,
          databaseType,
        });
      } else if (supportSchema) {
        getSchemaList(newSelectDatabase);
      }
    }

    if (hasApplicableDatabaseNameChange(changedValues, newSelectDatabase?.supportDatabase)) {
      invalidateDatabaseOptionRequests(requestLifecycle);
      setSchemaList([]);
      newSelectDatabase = {
        ...newSelectDatabase,
        schemaName: undefined,
        databaseName: changedValues.databaseName,
      };
      newSelectDatabase.selectDone = isSelectDone(newSelectDatabase);
      if (changedValues.databaseName && newSelectDatabase.supportSchema) {
        getSchemaList(newSelectDatabase);
      }
    }

    if ('schemaName' in changedValues) {
      newSelectDatabase = {
        ...newSelectDatabase,
        schemaName: changedValues.schemaName,
      };
      newSelectDatabase.selectDone = isSelectDone(newSelectDatabase);
    }
    setSelectDatabase(newSelectDatabase);
  };

  return {
    dataSourceList: astrictDataSourceList,
    databaseList,
    schemaList,
    selectDatabase,
    onChangeSelectDatabase,
  };
};

export default useSelectDatabase;
