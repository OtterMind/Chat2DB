import { useEffect, useRef, useState } from 'react';
import { InputNumber, Tooltip, Dropdown } from 'antd';
import { IResultConfig } from '@/typings';
import i18n from '@/i18n';
import _ from 'lodash';
import { IconButton, ToolbarBtn } from '@chat2db/ui';
import LoadingGracile from '@/components/Loading/LoadingGracile';
import { useStyles } from './style';
import { CheckOutlined } from '@ant-design/icons';
import { ChevronDown, ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight } from 'lucide-react';
import { RESULT_PAGE_SIZE_OPTIONS } from '@/constants/pagination';
import { useGlobalStore } from '@/store/global';
import { settingSelectors } from '@/store/global/selectors';

interface IProps {
  onPageSizeChange?: (pageSize: number) => void;
  onPageNoChange?: (pageNo: number) => void;
  onClickTotalBtn?: () => Promise<number | undefined>;
  paginationConfig: IResultConfig;
}

type IIconType = 'pre' | 'next' | 'first' | 'last';

export default function Pagination(props: IProps) {
  const { onPageNoChange, onPageSizeChange, paginationConfig } = props;
  const [inputNumberWidth, setInputNumberWidth] = useState<number>(25);
  const { styles } = useStyles({ inputNumberWidth });
  const [inputValue, setInputValue] = useState<number | null>(1);
  const [totalLoading, setTotalLoading] = useState(false);
  const [pageSizeMenuOpen, setPageSizeMenuOpen] = useState(false);
  const [customPageSize, setCustomPageSize] = useState<number | null>(null);
  const keepPageSizeMenuOpenRef = useRef(false);
  const { defaultPageSize, setBaseSetting } = useGlobalStore((state) => ({
    defaultPageSize: settingSelectors.currentBaseSetting(state).defaultPageSize,
    setBaseSetting: state.setBaseSetting,
  }));

  useEffect(() => {
    setInputValue(paginationConfig?.pageNo ?? 1);
  }, [paginationConfig?.pageNo]);

  const onInputNumberChange = (value: number | null) => {
    setInputValue(value);
  };

  useEffect(() => {
    const width = (inputValue?.toString().length ?? 1) * 8 + 17;
    // if (width > 105) {
    //   width = 105;
    // }
    setInputNumberWidth(width);
  }, [inputValue]);

  const onInputNumberBlur = () => {
    if (_.isNumber(inputValue)) {
      if (inputValue !== paginationConfig?.pageNo) {
        onPageNoChange && onPageNoChange(inputValue);
      }
    } else {
      setInputValue(1);
      onPageNoChange && onPageNoChange(1);
    }
  };

  const handleClickTotalBtn = async () => {
    if (!props.onClickTotalBtn) return;
    setTotalLoading(true);

    try {
      const res = await props.onClickTotalBtn();
      return res;
    } catch (error) {
      console.error(error);
    } finally {
      setTotalLoading(false);
    }
  };

  const handleClickIcon = async (type: IIconType) => {
    if (!onPageNoChange || !paginationConfig) return;
    if (handleIsDisabled(type)) return;
    switch (type) {
      case 'first':
        onPageNoChange(1);
        break;
      case 'last':
        {
          const total = await handleClickTotalBtn();
          const { pageSize } = paginationConfig || {};
          if (_.isNumber(total) && _.isNumber(pageSize)) {
            props.onPageNoChange && props.onPageNoChange(Math.ceil(total / pageSize));
          }
        }
        break;
      case 'pre':
        onPageNoChange(paginationConfig?.pageNo - 1);
        break;
      case 'next':
        onPageNoChange(paginationConfig?.pageNo + 1);
        break;
      default:
        break;
    }
  };

  const handleIsDisabled = (type: IIconType) => {
    if (!paginationConfig) {
      return false;
    }
    if (type === 'first') {
      return paginationConfig?.pageNo === 1;
    }
    if (type === 'pre') {
      return paginationConfig?.pageNo === 1;
    }

    const isNumber = _.isNumber(paginationConfig.total);
    const totalShow = paginationConfig.pageNo * paginationConfig.pageSize;
    if (type === 'next' || type === 'last') {
      if (isNumber) {
        return totalShow > (paginationConfig.total as number);
      }
      return !paginationConfig?.hasNextPage;
    }

    return true;
  };

  const isPresetDefaultPageSize = RESULT_PAGE_SIZE_OPTIONS.some((pageSize) => pageSize === defaultPageSize);

  const items = [
    ...RESULT_PAGE_SIZE_OPTIONS.map((pageSize) => ({
      key: String(pageSize),
      label: (
        <div className={styles.pageSizeOption}>
          <span>{pageSize}</span>
          {pageSize === defaultPageSize && (
            <span className={styles.defaultPageSize}>
              <CheckOutlined />
              {i18n('workspace.table.defaultPageSize')}
            </span>
          )}
        </div>
      ),
    })),
    { type: 'divider' as const },
    {
      key: 'custom',
      label: (
        <div
          className={styles.customPageSize}
          onClick={(event) => event.stopPropagation()}
          onKeyDown={(event) => event.stopPropagation()}
        >
          <InputNumber
            className={styles.customPageSizeInput}
            size="small"
            min={1}
            max={100000}
            precision={0}
            controls={false}
            autoFocus
            value={customPageSize}
            placeholder={i18n('workspace.table.customPageSize')}
            onChange={setCustomPageSize}
            onPressEnter={() => handleApplyCustomPageSize()}
          />
          {!isPresetDefaultPageSize && customPageSize === defaultPageSize && (
            <span className={styles.defaultPageSize}>
              <CheckOutlined />
              {i18n('workspace.table.defaultPageSize')}
            </span>
          )}
        </div>
      ),
    },
    { type: 'divider' as const },
    {
      key: 'set-default',
      label: i18n('workspace.table.setDefaultPageSize'),
      disabled: paginationConfig.pageSize === defaultPageSize,
    },
  ];

  function handleApplyCustomPageSize() {
    if (!customPageSize) {
      return;
    }
    keepPageSizeMenuOpenRef.current = true;
    setPageSizeMenuOpen(true);
    setBaseSetting({ defaultPageSize: customPageSize });
    onPageSizeChange?.(customPageSize);
  }

  const handlePageSizeMenuClick = ({ key }: { key: string }) => {
    if (key === 'set-default') {
      setBaseSetting({ defaultPageSize: paginationConfig.pageSize });
      setPageSizeMenuOpen(false);
      return;
    }
    keepPageSizeMenuOpenRef.current = true;
    setPageSizeMenuOpen(true);
    onPageSizeChange?.(Number(key));
  };

  const handlePageSizeMenuOpenChange = (open: boolean, info: { source: 'trigger' | 'menu' }) => {
    if (open) {
      const isPresetCurrentPageSize = RESULT_PAGE_SIZE_OPTIONS.some(
        (pageSize) => pageSize === paginationConfig.pageSize,
      );
      setCustomPageSize(
        !isPresetDefaultPageSize ? defaultPageSize : !isPresetCurrentPageSize ? paginationConfig.pageSize : null,
      );
    }
    if (!open && info.source === 'menu' && keepPageSizeMenuOpenRef.current) {
      keepPageSizeMenuOpenRef.current = false;
      return;
    }
    if (!open) {
      keepPageSizeMenuOpenRef.current = false;
    }
    setPageSizeMenuOpen(open);
  };

  return (
    <div className={styles.paginationWrapper}>
      <IconButton
        icon={ChevronsLeft}
        disabled={handleIsDisabled('first')}
        size="sm"
        onClick={() => handleClickIcon('first')}
      />
      <IconButton
        icon={ChevronLeft}
        disabled={handleIsDisabled('pre')}
        size="sm"
        onClick={() => handleClickIcon('pre')}
      />
      <InputNumber
        className={styles.inputNumber}
        size="small"
        min={1}
        value={inputValue}
        controls={false}
        onPressEnter={onInputNumberBlur}
        onBlur={onInputNumberBlur}
        onChange={onInputNumberChange}
      />
      <IconButton
        icon={ChevronRight}
        disabled={handleIsDisabled('next')}
        size="sm"
        onClick={() => handleClickIcon('next')}
      />
      <IconButton
        icon={ChevronsRight}
        disabled={handleIsDisabled('last')}
        size="sm"
        onClick={() => handleClickIcon('last')}
      />

      <Dropdown
        destroyPopupOnHide
        open={pageSizeMenuOpen}
        onOpenChange={handlePageSizeMenuOpenChange}
        menu={{ items, selectedKeys: [String(paginationConfig.pageSize)], onClick: handlePageSizeMenuClick }}
      >
        <div className={styles.selectSize}>
          {paginationConfig?.pageSize ?? 200}
          <ChevronDown size={14} />
        </div>
      </Dropdown>
      {props.onClickTotalBtn ? (
        <Tooltip mouseEnterDelay={0.6} title={i18n('workspace.table.total.tip')}>
          <span>
            <ToolbarBtn
              text={`${i18n('workspace.table.total')}：${paginationConfig?.total}`}
              className={styles.totalButton}
              suffixIcon={totalLoading ? <LoadingGracile /> : ''}
              onClick={handleClickTotalBtn}
            />
          </span>
        </Tooltip>
      ) : (
        <div className={styles.totalContainer}>
          {i18n('workspace.table.total')} ：{paginationConfig?.total}
        </div>
      )}
    </div>
  );
}
