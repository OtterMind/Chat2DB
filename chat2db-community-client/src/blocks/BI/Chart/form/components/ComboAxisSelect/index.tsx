import { memo, useMemo, useState } from 'react';
import { ChevronDown, ChevronUp } from 'lucide-react';
import { Select, Dropdown } from 'antd';
import { IChartItem } from '@/typings';
import { newFormattedSqlExecuteData } from '@/utils/dashboard';
import { useStyles } from './style';
import { IconButton, IconfontSvg } from '@chat2db/ui';
import { chartTypeMap } from '@/blocks/BI/Chart/constants/chartType';
import { IComboYAxisDataItem } from '@/blocks/BI/Chart/typings';
import { ChartType } from '@/blocks/BI/Chart/constants';
import { useUpdateEffect } from 'ahooks';
import i18n from '@/i18n';

interface IProps {
  value?: IComboYAxisDataItem[];
  onChange?: (value: any) => void;
  chartDetail: IChartItem;
}

interface IItemProps {
  data: IComboYAxisDataItem;
  isFirst: boolean;
  isLast: boolean;
  index: number;
  dataKeys: { value: string; label: string }[];
  onChange: (params: Partial<IComboYAxisDataItem>) => void;
  onAction?: (actionType: 'move-up' | 'move-down' | 'delete') => void;
}

const defaultComboAxisSelect: IComboYAxisDataItem = {
  chartType: ChartType.Column,
  axisPosition: 'left',
};

const ComboAxisSelectItem = (props: IItemProps) => {
  const { styles } = useStyles();
  const { data, onChange, onAction, dataKeys, isFirst, isLast } = props;
  const { field, chartType, axisPosition } = data;

  const handleSelectChange = (value: string) => {
    onChange({
      field: value,
    });
  };

  // handles chart type selection
  const chartTypeList = useMemo(() => {
    return [
      {
        key: ChartType.Line,
        label: chartTypeMap.Line.title,
        icon: <IconfontSvg code={chartTypeMap.Line.icon} />,
        onClick: () =>
          onChange({
            chartType: ChartType.Line,
          }),
      },
      {
        key: ChartType.Column,
        label: chartTypeMap.Column.title,
        icon: <IconfontSvg code={chartTypeMap.Column.icon} />,
        onClick: () =>
          onChange({
            chartType: ChartType.Column,
          }),
      },
      {
        key: ChartType.AreaLine,
        label: chartTypeMap.AreaLine.title,
        icon: <IconfontSvg code={chartTypeMap.AreaLine.icon} />,
        onClick: () =>
          onChange({
            chartType: ChartType.AreaLine,
          }),
      },
      {
        key: ChartType.Scatter,
        label: chartTypeMap.Scatter.title,
        icon: <IconfontSvg code={chartTypeMap.Scatter.icon} />,
        onClick: () =>
          onChange({
            chartType: ChartType.Scatter,
          }),
      },
    ];
  }, []);

  const positionList = useMemo(() => {
    return [
      {
        key: 'left',
        label: 'Left Axis',
        icon: <IconfontSvg code="icon-left-axis" />,
        onClick: () =>
          onChange({
            axisPosition: 'left',
          }),
      },
      {
        key: 'right',
        label: 'Right Axis',
        icon: <IconfontSvg code="icon-right-axis" />,
        onClick: () =>
          onChange({
            axisPosition: 'right',
          }),
      },
    ];
  }, []);

  const positionMap = useMemo(() => {
    return {
      left: {
        label: i18n('dashboard.chart.leftAxis'),
        icon: 'icon-left-axis',
      },
      right: {
        label: i18n('dashboard.chart.rightAxis'),
        icon: 'icon-right-axis',
      },
    };
  }, []);

  const actionList = useMemo(() => {
    const _list = [
      {
        key: 'move-up',
        label: i18n('common.button.moveUp'),
        icon: <ChevronUp size={16} />,
        onClick: () => {
          onAction?.('move-up');
        },
      },
      {
        key: 'move-down',
        label: i18n('common.button.moveDown'),
        icon: <ChevronDown size={16} />,
        onClick: () => {
          onAction?.('move-down');
        },
      },
      {
        key: 'delete-axis',
        label: i18n('common.button.delete'),
        icon: <IconfontSvg code="icon-trash" />,
        onClick: () => {
          onAction?.('delete');
        },
      },
    ];
    if (isFirst) {
      _list.splice(0, 1); // Remove Move Up
    }
    if (isLast) {
      _list.splice(1, 1); // Remove Move Down
    }
    return _list;
  }, [isFirst, isLast]);

  return (
    <div className={styles.axisSelectItem}>
      <Select value={field} onChange={handleSelectChange} options={dataKeys} />
      <Dropdown menu={{ items: chartTypeList }} trigger={['click']}>
        <IconButton
          className={styles.axisSelectItemButton}
          size={{
            boxSize: 30,
            iconSize: 22,
            borderRadius: 6,
          }}
          code={chartTypeMap[chartType].icon}
        />
      </Dropdown>
      <Dropdown menu={{ items: positionList }} trigger={['click']}>
        <IconButton
          className={styles.axisSelectItemButton}
          size={{
            boxSize: 30,
            iconSize: 22,
            borderRadius: 6,
          }}
          code={positionMap[axisPosition].icon}
        />
      </Dropdown>
      <Dropdown menu={{ items: actionList }} trigger={['click']}>
        <IconButton
          className={styles.axisSelectItemButton}
          size={{
            boxSize: 30,
            iconSize: 22,
            borderRadius: 6,
          }}
          code="icon-more-dot"
        />
      </Dropdown>
    </div>
  );
};

const ComboAxisSelect = (props: IProps) => {
  const { styles } = useStyles();
  const { value, onChange, chartDetail } = props;
  const [_value, setValue] = useState<IComboYAxisDataItem[]>(value || [defaultComboAxisSelect]);

  useUpdateEffect(() => {
    if (value && value.length > 0) {
      setValue(value);
    } else {
      setValue([defaultComboAxisSelect]);
    }
  }, [value]);

  const dataKeys = useMemo(() => {
    if (!chartDetail.metaData) {
      return [];
    }
    const metaData = newFormattedSqlExecuteData(chartDetail.metaData);
    const keys = Object.keys(metaData[0] || {});
    const data =
      keys?.map((key) => {
        return { value: key, label: key };
      }) || [];
    return data;
  }, [chartDetail.metaData]);

  return (
    <div className={styles.container}>
      {_value.map((item, index) => (
        <ComboAxisSelectItem
          key={index}
          isFirst={index === 0}
          isLast={index === _value.length - 1}
          index={index}
          data={item}
          dataKeys={dataKeys}
          onChange={(params) => {
            setValue((pre) => {
              const _list = pre.map((v, i) => (i === index ? { ...v, ...params } : v));
              onChange?.(_list);
              return _list;
            });
          }}
          onAction={(actionType) => {
            setValue((pre) => {
              const _list = [...pre];
              if (actionType === 'move-up' && index > 0) {
                const temp = _list[index - 1];
                _list[index - 1] = _list[index];
                _list[index] = temp;
              } else if (actionType === 'move-down' && index < _list.length - 1) {
                const temp = _list[index + 1];
                _list[index + 1] = _list[index];
                _list[index] = temp;
              } else if (actionType === 'delete') {
                _list.splice(index, 1);
              }
              onChange?.(_list);
              return _list;
            });
          }}
        />
      ))}
      <div
        className={styles.addAxisButton}
        onClick={() => {
          const _list = [..._value, defaultComboAxisSelect];
          setValue(_list);
          onChange && onChange(_list);
        }}
      >
        <IconfontSvg code="icon-add" />
        {i18n('dashboard.chart.addAxis')}
      </div>
    </div>
  );
};

export default memo(ComboAxisSelect);
