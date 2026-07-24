import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import * as VTable from '@visactor/vtable';
import { Theme } from 'antd-style';
import { hexToRgba } from '@/utils/color';
import { ITableInstance } from '@/blocks/CanvasTable/typings';
import { getColumnValue } from '../utils';
import NodeFiltering from '@/components/NodeFiltering';
import i18n from '@/i18n';
import { getSortedOrFilteredData } from '@/blocks/CanvasTable/utils';
import { IManageResultData } from '@/typings/database';
import { buildResultSetOrderByText } from './orderByIdentifier';

type IUseFilterAndSort = (params: {
  theme: Omit<Theme, 'prefixCls'>;
  tableInstance: ITableInstance | null;
  resultData: IManageResultData;
  sortAfter?: () => void;
  filterAfter?: () => void;
  setOrderByText?: (orderByText: string) => void;
}) => {
  activeFilterCount: number;
  clearAllFilters: () => void;
};

// Get the CHAT2DB_ROW_NUMBER list of data
const getRowNumberList = (tableInstance) => {
  const records = getSortedOrFilteredData(tableInstance) || [];
  const rowNumberList = records.map((row) => row.CHAT2DB_ROW_NUMBER);
  return rowNumberList;
};

// Upper bound on VTable render-settling poll iterations (100ms interval * 100 = 10s).
const MAX_POLL_ITERATIONS = 100;

const useFilterAndSort: IUseFilterAndSort = ({
  theme,
  tableInstance,
  resultData,
  sortAfter,
  filterAfter,
  setOrderByText,
}) => {
  const filterRulesRef = useRef<
    {
      filterKey: string;
      filteredValues: string[];
    }[]
  >([]);
  const [activeFilterCount, setActiveFilterCount] = useState(0);

  // Track active VTable render-settling pollers so they can be cleared on unmount.
  const pollTimerIdsRef = useRef<Set<ReturnType<typeof setInterval>>>(new Set());

  // VTable has no post-filter/sort callback, so poll until the rendered
  // CHAT2DB_ROW_NUMBER list changes. Cap iterations so a poll cannot run
  // forever when the list never changes, and register each timer so unmount
  // clears it instead of firing setState on an unmounted component.
  const pollUntilRowNumbersChange = (
    beforeGetRowNumberList: (string | number)[],
    onSettled: () => void,
  ) => {
    let iterations = 0;
    const timerId = setInterval(() => {
      iterations += 1;
      const afterGetRowNumberList = getRowNumberList(tableInstance);
      if (
        beforeGetRowNumberList.join(',') !== afterGetRowNumberList.join(',') ||
        iterations >= MAX_POLL_ITERATIONS
      ) {
        clearInterval(timerId);
        pollTimerIdsRef.current.delete(timerId);
        onSettled();
      }
    }, 100);
    pollTimerIdsRef.current.add(timerId);
  };

  useEffect(() => {
    return () => {
      pollTimerIdsRef.current.forEach((timerId) => clearInterval(timerId));
      pollTimerIdsRef.current.clear();
    };
  }, []);

  // useEffect(() => {
  //   if (!tableInstance) return;
  //   filterRulesRef.current = [];
  // }, [resultData]);

  // Registration form icon
  useLayoutEffect(() => {
    const bgColor = hexToRgba(theme.colorFill, 40);

    VTable.register.icon('sort', {
      name: 'sort',
      type: 'svg',
      width: 22,
      height: 22,
      marginRight: 0,
      // hover: {
      //   bgColor,
      // },
      cursor: 'pointer',
      positionType: VTable.TYPES.IconPosition.right,
      svg: `<svg t="1732081713731" fill="${theme.colorText}" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" p-id="15494" width="200" height="200"><path d="M311.99999961 562.0000001h400.00000078l-200.3000001 199.99999951L311.99999961 562.0000001z m0-100.0000002l199.70000068-199.99999951L712.00000039 461.9999999H311.99999961z" p-id="15495"></path></svg>`,
    });

    VTable.register.icon('sort-asc', {
      name: 'sort-asc',
      type: 'svg',
      width: 22,
      height: 22,
      marginRight: 0,
      // hover: {
      //   bgColor,
      // },
      cursor: 'pointer',
      positionType: VTable.TYPES.IconPosition.right,
      svg: `<svg t="1732081362691" fill="${theme.colorText}" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" p-id="14490" width="200" height="200"><path d="M511.79620974 433.97226901l-208.46554659 208.4415712a19.65977557 19.65977557 0 0 1-27.77542692-27.81139001l220.10557232-220.09358414a22.77656945 22.77656945 0 0 1 32.25881467-1e-8L748.02519506 614.60245021a19.65977557 19.65977557 0 0 1-27.79940182 27.81139l-208.4295835-208.46554659z" p-id="14491"></path></svg>`,
    });

    VTable.register.icon('sort-desc', {
      name: 'sort-desc',
      type: 'svg',
      width: 22,
      height: 22,
      marginRight: 0,
      // hover: {
      //   bgColor,
      // },
      cursor: 'pointer',
      positionType: VTable.TYPES.IconPosition.right,
      svg: `<svg t="1732081429062" fill="${theme.colorText}" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" p-id="15061" width="200" height="200"><path d="M512.20379026 590.02773099l208.46554659-208.4415712a19.65977557 19.65977557 0 0 1 27.77542692 27.81139001l-220.10557232 220.09358414a22.77656945 22.77656945 0 0 1-32.25881467 1e-8L275.97480494 409.39754979a19.65977557 19.65977557 0 0 1 27.79940182-27.81139l208.4295835 208.46554659z" p-id="15062"></path></svg>`,
    });

    VTable.register.icon('filter', {
      name: 'filter',
      type: 'svg',
      width: 22,
      height: 22,
      marginLeft: 6,
      hover: {
        bgColor,
      },
      cursor: 'pointer',
      positionType: VTable.TYPES.IconPosition.contentRight,
      svg: `<svg t="1732068283200" fill="${theme.colorText}" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" p-id="5401" width="200" height="200"><path d="M810.73102903 431.17967796c14.1942501 0 25.70897341 11.5147233 25.70897341 25.70897342 0 12.89069653-9.48697329 23.60880375-21.87073231 25.41929483l-3.76582145 0.28967857h-178.15232277c-14.04941082-0.28967857-25.2020359-11.6595626-25.2020359-25.70897341 0-12.74585724 9.26971435-23.31912517 21.58105373-25.34687519l3.76582145-0.36209822h177.93506384z m-1e-8 106.45687581c14.12183047 0.43451786 25.12961627 11.80440188 25.12961627 25.7089734 0 12.60101795-9.12487507 23.17428589-21.36379481 25.27445555l-3.76582146 0.36209822h-178.15232275c-13.97699119-0.28967857-25.2020359-11.6595626-25.20203591-25.70897342 0-12.74585724 9.26971435-23.31912517 21.58105373-25.34687518l3.76582146-0.36209822h178.00748348z m0 106.45687579c14.1942501 0 25.70897341 11.5147233 25.70897342 25.70897342 0 12.89069653-9.48697329 23.60880375-21.87073231 25.41929483l-3.76582145 0.28967857h-178.15232277c-13.97699119-0.28967857-25.2020359-11.6595626-25.2020359-25.70897341 0-12.74585724 9.26971435-23.31912517 21.58105373-25.34687518l3.76582145-0.36209823h177.93506384zM677.55130482 218.338346c23.31912517 0 38.59966993 17.88765193 33.96481275 42.07581282l-1.23113393 4.92453576-158.01966191 211.97229624v328.35066317l-51.41794681-20.56717872v-322.26741314l1.59323215-4.77969646 140.56652785-188.43591214H259.40028381l149.18446541 187.56687642 1.73807144 4.92453574V748.81223393l-51.41794681-20.56717873V477.31099081L189.00839043 262.58674813c-5.64873218-20.92927695 5.72115184-39.17902708 25.27445555-43.37936641l4.34517859-0.65177679 4.56243754-0.21725893h454.36084271z" p-id="5402"></path></svg>`,
    });

    VTable.register.icon('filter-active', {
      name: 'filter-active',
      type: 'svg',
      width: 22,
      height: 22,
      marginLeft: 6,
      hover: {
        bgColor,
      },
      cursor: 'pointer',
      positionType: VTable.TYPES.IconPosition.contentRight,
      svg: `<svg t="1732068283200" fill="${theme.colorPrimary}" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" p-id="5401" width="200" height="200"><path d="M810.73102903 431.17967796c14.1942501 0 25.70897341 11.5147233 25.70897341 25.70897342 0 12.89069653-9.48697329 23.60880375-21.87073231 25.41929483l-3.76582145 0.28967857h-178.15232277c-14.04941082-0.28967857-25.2020359-11.6595626-25.2020359-25.70897341 0-12.74585724 9.26971435-23.31912517 21.58105373-25.34687519l3.76582145-0.36209822h177.93506384z m-1e-8 106.45687581c14.12183047 0.43451786 25.12961627 11.80440188 25.12961627 25.7089734 0 12.60101795-9.12487507 23.17428589-21.36379481 25.27445555l-3.76582146 0.36209822h-178.15232275c-13.97699119-0.28967857-25.2020359-11.6595626-25.20203591-25.70897342 0-12.74585724 9.26971435-23.31912517 21.58105373-25.34687518l3.76582146-0.36209822h178.00748348z m0 106.45687579c14.1942501 0 25.70897341 11.5147233 25.70897342 25.70897342 0 12.89069653-9.48697329 23.60880375-21.87073231 25.41929483l-3.76582145 0.28967857h-178.15232277c-13.97699119-0.28967857-25.2020359-11.6595626-25.2020359-25.70897341 0-12.74585724 9.26971435-23.31912517 21.58105373-25.34687518l3.76582145-0.36209823h177.93506384zM677.55130482 218.338346c23.31912517 0 38.59966993 17.88765193 33.96481275 42.07581282l-1.23113393 4.92453576-158.01966191 211.97229624v328.35066317l-51.41794681-20.56717872v-322.26741314l1.59323215-4.77969646 140.56652785-188.43591214H259.40028381l149.18446541 187.56687642 1.73807144 4.92453574V748.81223393l-51.41794681-20.56717873V477.31099081L189.00839043 262.58674813c-5.64873218-20.92927695 5.72115184-39.17902708 25.27445555-43.37936641l4.34517859-0.65177679 4.56243754-0.21725893h454.36084271z" p-id="5402"></path></svg>`,
    });
  }, [theme]);

  // table sorting
  const handleSort = (col, row, name: 'sort-asc' | 'sort-desc' | 'sort') => {
    if (!tableInstance) return;
    const field = tableInstance.getHeaderField(col, row);
    // const sortState: any = tableInstance.sortState;
    let order: any = 'desc';
    let headerSortIcon = 'sort-desc';

    // if (sortState && sortState.field === field) {
    if (name === 'sort-desc') {
      order = 'asc';
      headerSortIcon = 'sort-asc';
    } else if (name === 'sort-asc') {
      order = 'normal';
      headerSortIcon = 'sort';
    } else {
      order = 'desc';
      headerSortIcon = 'sort-desc';
    }
    // }

    const columns = [...tableInstance.columns];

    columns.forEach((column) => {
      if (!column.headerIcon) return;
      if (column.field === field) {
        column.headerIcon[0] = headerSortIcon;
      } else {
        column.headerIcon[0] = 'sort';
      }
    });

    const beforeGetRowNumberList = getRowNumberList(tableInstance);

    tableInstance.updateColumns(columns);

    if (resultData.canEdit) {
      tableInstance.columns.forEach((column: any) => {
        if (column.field === field) {
          if (order === 'normal') {
            setOrderByText?.('');
          } else {
            setOrderByText?.(
              buildResultSetOrderByText({
                header: column.originalData,
                order,
                databaseType: resultData.executeSqlParams?.databaseType,
              }),
            );
          }
        }
      });
    } else {
      tableInstance.updateSortState({
        field,
        order,
      });
    }

    pollUntilRowNumbersChange(beforeGetRowNumberList, () => sortAfter?.());
  };

  const handleFilter = (col, row, event) => {
    if (!tableInstance) return;
    const field = tableInstance.getHeaderField(col, row);
    const nodeFilterData = getColumnValue(resultData, field);

    const onChangeSelect = (checkedKeys) => {
      // Select all or no selection without filtering
      const columns = [...tableInstance.columns];
      const beforeGetRowNumberList = getRowNumberList(tableInstance);

      if (!checkedKeys.length || checkedKeys.length === nodeFilterData.length) {
        filterRulesRef.current = filterRulesRef.current.filter((rule) => rule.filterKey !== field);
        columns.forEach((column) => {
          if (!column.headerIcon) return;
          if (column.field === field) {
            column.headerIcon[1] = 'filter';
          }
        });
        tableInstance.updateColumns(columns);
        tableInstance.updateFilterRules(filterRulesRef.current);
        setActiveFilterCount(filterRulesRef.current.length);

        // VTable has no post-filter callback, so poll until filtering and rendering have completed.
        pollUntilRowNumbersChange(beforeGetRowNumberList, () => filterAfter?.());
        return;
      }

      // If filterRulesRef.current has the filterKey of this field, update it, otherwise push it.
      const index = filterRulesRef.current.findIndex((rule) => rule.filterKey === field);
      if (index > -1) {
        filterRulesRef.current[index].filteredValues = checkedKeys;
      } else {
        filterRulesRef.current.push({
          filterKey: field as string,
          filteredValues: checkedKeys,
        });
      }

      columns.forEach((column) => {
        if (!column.headerIcon) return;
        if (column.field === field) {
          column.headerIcon[1] = 'filter-active';
        }
      });

      tableInstance.updateColumns(columns);
      tableInstance.updateFilterRules(filterRulesRef.current);
      setActiveFilterCount(filterRulesRef.current.length);
      pollUntilRowNumbersChange(beforeGetRowNumberList, () => filterAfter?.());
    };

    const selectedKeys = filterRulesRef.current.find((rule) => rule.filterKey === field)?.filteredValues || [];

    tableInstance?.contextMenuRef?.current?.openDropdown({
      zIndex: 10,
      event,
      dropdownRender: (
        <NodeFiltering
          data={nodeFilterData}
          selectedKeys={selectedKeys}
          onChangeSelect={onChangeSelect}
          filterTitle={i18n('workspace.text.localFilterFor', field)}
        />
      ),
    });
  };

  useEffect(() => {
    if (!tableInstance) return;
    const id = tableInstance.on('icon_click', (args) => {
      const { col, row, name, event } = args;
      if (name === 'sort' || name === 'sort-asc' || name === 'sort-desc') {
        handleSort(col, row, name);
      }
      if (name === 'filter' || name === 'filter-active') {
        handleFilter(col, row, event);
      }
    });
    return () => {
      tableInstance.off(id);
    };
  }, [tableInstance, resultData, handleSort, handleFilter]);

  // Clear all column filters
  const clearAllFilters = useCallback(() => {
    if (!tableInstance) return;
    if (filterRulesRef.current.length === 0) return;

    const beforeGetRowNumberList = getRowNumberList(tableInstance);
    const columns = [...tableInstance.columns];
    columns.forEach((column) => {
      if (!column.headerIcon) return;
      if (column.headerIcon[1] === 'filter-active') {
        column.headerIcon[1] = 'filter';
      }
    });
    filterRulesRef.current = [];
    tableInstance.updateColumns(columns);
    tableInstance.updateFilterRules([]);
    setActiveFilterCount(0);

    pollUntilRowNumbersChange(beforeGetRowNumberList, () => filterAfter?.());
  }, [tableInstance, filterAfter]);

  return {
    activeFilterCount,
    clearAllFilters,
  };
};

export default useFilterAndSort;
