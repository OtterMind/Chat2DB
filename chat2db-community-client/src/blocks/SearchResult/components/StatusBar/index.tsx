import { ForwardedRef, forwardRef, memo, useImperativeHandle, useMemo, useRef } from 'react';
import classnames from 'classnames';
import i18n from '@/i18n';
import { useStyles } from './style';
import { IManageResultData } from '@/typings';
import { Dropdown } from 'antd';
import { ChevronDown, Copy, List } from 'lucide-react';
import { useGlobalStore } from '@/store/global';
import { DATA_TABLE_SETTINGS } from '@/constants/settings';
import type { SelectionMetricId } from '@/typings/settings';
import { formatSelectionMetric, summarizeSelection } from './selectionAggregation';
import { SELECTION_METRIC_OPTIONS } from './selectionMetrics';
import { copyToClipboard } from '@/utils';
import { staticMessage } from '@chat2db/ui';
import { formatResultSummary } from './resultSummary';

interface IProps {
  className?: string;
  resultData: IManageResultData;
  selectedValues?: unknown[];
  selectedRowCount?: number;
  onShowAllAggregates?: () => void;
}

export interface StatusBarRef {
  copyActiveMetric: () => boolean;
}

const StatusBar = forwardRef((props: IProps, ref: ForwardedRef<StatusBarRef>) => {
  const { className, resultData, selectedValues = [], selectedRowCount = 0, onShowAllAggregates } = props;
  const { styles } = useStyles();
  const { dataTableSettings, updateDataTableSettings } = useGlobalStore((state) => ({
    dataTableSettings: state.dataTableSettings,
    updateDataTableSettings: state.updateDataTableSettings,
  }));
  const selectionMetrics = dataTableSettings.selectionMetrics || DATA_TABLE_SETTINGS.selectionMetrics!;
  const selectionSummary = useMemo(
    () => summarizeSelection(selectedValues, selectedRowCount),
    [selectedValues, selectedRowCount],
  );
  const activeMetricIndexRef = useRef(2);

  const resultSummary = formatResultSummary(resultData, (key, durationMs) => i18n(key, durationMs));

  const updateMetric = (index: number, metric: SelectionMetricId) => {
    const nextMetrics = [...selectionMetrics] as [SelectionMetricId, SelectionMetricId, SelectionMetricId];
    nextMetrics[index] = metric;
    updateDataTableSettings({
      ...dataTableSettings,
      selectionMetrics: nextMetrics,
    });
  };

  const copyMetric = (index: number) => {
    if (!selectedValues.length) {
      return false;
    }
    const metric = selectionMetrics[index];
    const value = metric ? formatSelectionMetric(metric, selectionSummary) : '';
    if (!value || !copyToClipboard(value)) {
      return false;
    }
    staticMessage.success(i18n('common.button.copySuccessfully'));
    return true;
  };

  useImperativeHandle(
    ref,
    () => ({
      copyActiveMetric: () => copyMetric(activeMetricIndexRef.current),
    }),
    [selectionMetrics, selectedValues.length, selectionSummary],
  );

  return (
    <div className={classnames(styles.statusBar, className)}>
      <div className={styles.resultSummary}>
        <span>{resultSummary}</span>
      </div>
      {selectedValues.length > 0 && (
        <div className={styles.selectionSummary}>
          {selectionMetrics.map((metric, index) => {
            const option =
              SELECTION_METRIC_OPTIONS.find((item) => item.id === metric) || SELECTION_METRIC_OPTIONS[0];
            const value = formatSelectionMetric(metric, selectionSummary);
            const menuItems = [
              {
                key: '__copy',
                disabled: !value,
                icon: <Copy size={14} strokeWidth={1.75} />,
                label: i18n('common.selectionAggregate.copyResult', i18n(option.label)),
              },
              { type: 'divider' as const },
              ...SELECTION_METRIC_OPTIONS.map((item) => ({ key: item.id, label: i18n(item.label) })),
              { type: 'divider' as const },
              {
                key: '__showAll',
                icon: <List size={14} strokeWidth={1.75} />,
                label: i18n('common.selectionAggregate.showAll'),
              },
            ];
            return (
              <Dropdown
                key={index}
                trigger={['click']}
                menu={{
                  items: menuItems,
                  selectedKeys: [metric],
                  selectable: true,
                  onClick: ({ key }) => {
                    activeMetricIndexRef.current = index;
                    if (key === '__copy') {
                      copyMetric(index);
                      return;
                    }
                    if (key === '__showAll') {
                      onShowAllAggregates?.();
                      return;
                    }
                    updateMetric(index, key as SelectionMetricId);
                  },
                }}
              >
                <button
                  type="button"
                  className={styles.metricButton}
                  onFocus={() => {
                    activeMetricIndexRef.current = index;
                  }}
                  onClick={() => {
                    activeMetricIndexRef.current = index;
                  }}
                >
                  <span className={styles.metricLabel}>{i18n(option.label)}</span>
                  {value && <span className={styles.metricValue}>{value}</span>}
                  <ChevronDown size={12} strokeWidth={1.75} />
                </button>
              </Dropdown>
            );
          })}
        </div>
      )}
    </div>
  );
});

export default memo(StatusBar);
