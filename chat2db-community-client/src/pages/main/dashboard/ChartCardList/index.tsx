import { useState, useImperativeHandle, ForwardedRef, forwardRef, useRef, useMemo, memo } from 'react';
import { useStyles } from './style';
import { IChartItem } from '@/typings';
import ChartCardBox from '@/blocks/BI/ChartCardBox';
import i18n from '@/i18n';
import { Trash2 } from 'lucide-react';
import { Empty, EmptyImage, Icon, IconfontSvg } from '@chat2db/ui';
import useScrollToBottom from '@/hooks/useScrollToBottom';
import { Button, Flex } from 'antd';
import GridLayout from '@/components/GridLayout';
import { useDashboardStore } from '@/store/dashboard/store';
import { deconstructSchema, initAppendLayoutItems } from '@/utils/dashboard';
import { useGlobalStore } from '@/store/global';
import { v4 as uuid } from 'uuid';
import useDeviceType from '@/hooks/useDeviceType';

interface IProps {
  className?: string;
  isEditPermission?: boolean;
  aiChartDetails?: IChartItem[] | null;
  openCreateDashboardModal: () => void;
  setDraggableModalOpen?: (open: boolean) => void;
}

export interface ChartCardListRef {
  setActiveChart: (chartId: number) => void;
}

const initLayoutItem = (t, index) => {
  return {
    i: t,
    x: (index % 2) * 6,
    y: Math.floor(index / 2),
    w: 6,
    h: 4,
    minW: 3,
    minH: 3,
  };
};

const initChartIdsLayout = (chartIds: number[]) => {
  return chartIds.map((t, index) => {
    return initLayoutItem(t, index);
  });
};

const ChartCardList = forwardRef((props: IProps, ref: ForwardedRef<ChartCardListRef>) => {
  const { className, isEditPermission, openCreateDashboardModal, aiChartDetails } = props;
  const { styles, cx } = useStyles();
  const [activeChart, setActiveChart] = useState<number | null>(null);
  const endOfListRef = useRef<HTMLDivElement>(null);
  const chartCardListRef = useRef<HTMLDivElement>(null);
  const { isPhone } = useDeviceType();

  const { openUnifiedConfirmationModal } = useGlobalStore((state) => {
    return {
      openUnifiedConfirmationModal: state.openUnifiedConfirmationModal,
    };
  });

  const { updateDashboardLayout, deleteChart, currentDashboard } = useDashboardStore((s) => ({
    updateDashboardLayout: s.updateDashboardLayout,
    deleteChart: s.deleteChart,
    currentDashboard: s.currentDashboard,
  }));

  const { chartIds, schema } = currentDashboard || {};

  useImperativeHandle(ref, () => ({
    setActiveChart: (chartId) => {
      setActiveChart(chartId);
    },
  }));

  const layout: any = useMemo(() => {
    let _layout: any = null;
    if (!chartIds) {
      return _layout;
    }
    _layout = deconstructSchema(schema);

    if (!_layout) {
      _layout = initChartIdsLayout(chartIds);
    }
        // Mobile layouts allow one item per row, so set every width to 12.
    if (isPhone) {
      _layout = _layout.map((t) => {
        return {
          ...t,
          w: 12,
        };
      });
    }
    return _layout;
  }, [chartIds, schema, isPhone]);

  const { scrollToBottom } = useScrollToBottom(endOfListRef, true, 'smooth');

  const dropdownProps = (id) => {
    return {
      menu: {
        items: [
          {
            key: 'delete',
            label: i18n('dashboard.chart.delete'),
            danger: true,
            icon: <Icon size="sm" icon={Trash2} />,
            onClick: () => {
              openUnifiedConfirmationModal({
                title: i18n('common.text.deleteConfirmTitle'),
                content: i18n('dashboard.delete.chart.confirm'),
                onOk: () => {
                  return deleteChart(id);
                },
              });
            },
          },
        ],
      },
    };
  };

  const { isResizable, isDraggable } = useMemo(() => {
    return {
      isResizable: isEditPermission,
      isDraggable: isEditPermission,
    };
  }, [isEditPermission]);

  const renderSettledChartCardList = () => {
    if (!layout) {
      return null;
    }

    // A numeric i value causes GridLayout to crash.
    layout.forEach((t) => {
      t.i = t.i.toString();
    });

    return (
      <GridLayout
        layout={layout}
        isResizable={isResizable}
        isDraggable={isDraggable}
        onLayoutChange={updateDashboardLayout}
        draggableHandle=".dragHandle"
      >
        {layout?.map((t) => {
          const id = Number(t.i);
          let refreshRule: any = null;
          if (currentDashboard?.refreshType && currentDashboard?.refreshCycle) {
            refreshRule = {
              refreshType: currentDashboard?.refreshType,
              refreshCycle: currentDashboard?.refreshCycle,
            };
          }
          const isActive = activeChart === id;

          return (
            <div key={t.i} data-grid={t} className={cx(styles.gridBox, { [styles.gridBoxActive]: isActive })}>
              {isDraggable && (
                <div className={cx('dragHandle', styles.dragHandleBox)}>
                  <IconfontSvg code="icon-bashou" className="dragGripper" />
                </div>
              )}
              <ChartCardBox
                chartId={id}
                isEditPermission={isEditPermission}
                dropdownProps={dropdownProps(id)}
                refreshRule={refreshRule}
                onClick={() => {
                  setActiveChart(id);
                }}
              />
            </div>
          );
        })}
      </GridLayout>
    );
  };

  const renderAIChartCardList = () => {
    if (!aiChartDetails) {
      return null;
    }
    const _aiLayout = initAppendLayoutItems(
      aiChartDetails.map((t) => {
        const id: any = uuid();
        return {
          id,
          detail: t,
        };
      }),
      schema,
    );

    setTimeout(() => {
      scrollToBottom();
    }, 0);

    return (
      <GridLayout layout={_aiLayout}>
        {_aiLayout?.map((t: any) => {
          const id = t.i;
          const isActive = activeChart === id;
          return (
            <div key={t.i} data-grid={t} className={cx(styles.gridBox, { [styles.gridBoxActive]: isActive })}>
              <div className={cx('dragHandle', styles.dragHandleBox)}>
                <IconfontSvg code="icon-bashou" className="dragGripper" />
              </div>
              <ChartCardBox
                chartDetail={t.detail}
                isEditPermission={isEditPermission}
                onClick={() => {
                  setActiveChart(id);
                }}
              />
            </div>
          );
        })}
      </GridLayout>
    );
  };

  const showList = chartIds?.length || aiChartDetails?.length;

  const renderChartCardList = () => {
    return (
      <div className={cx(styles.chartCardList, className)} ref={chartCardListRef}>
        {renderSettledChartCardList()}
        {renderAIChartCardList()}
        <div ref={endOfListRef} />
      </div>
    );
  };

  const renderEmpty = () => {
    return (
      <div className={cx(styles.emptyPage)}>
        <Empty image={EmptyImage.ChartList} title={i18n('dashboard.createChart.tip')} />
        <Flex gap={20}>
          <Button type="primary" onClick={openCreateDashboardModal}>
            {i18n('dashboard.editor.createChart')}
          </Button>
        </Flex>
      </div>
    );
  };

  return (
    <>
      {!!showList && renderChartCardList()}
      {!showList && renderEmpty()}
    </>
  );
});

export default memo(ChartCardList);
