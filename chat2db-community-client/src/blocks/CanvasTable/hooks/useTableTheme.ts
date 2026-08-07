import { useMemo } from 'react';
import { Theme as AntdTheme } from 'antd-style';
import { hexToRgba } from '@/utils/color';
import {ICustomOptions} from '../index';
import { useGlobalStore } from '@/store/global';

const useTableTheme = (params: { antdTheme: Omit<AntdTheme, 'prefixCls'>, options: any, customOptions?: ICustomOptions }) => {
  const { antdTheme, options, customOptions } = params;
  const  customFontSize  = useGlobalStore.getState().baseSetting.customFontSize;
  const theme: any = useMemo(() => {
    const {
      colorText,
      colorFillQuaternary,
      colorTextSecondary,
      colorFill,
      fontFamily,
      colorPrimary,
      colorBorderSecondary,
    } = antdTheme;

    const colorPrimary4 = hexToRgba(colorPrimary, 4);
    const colorPrimary40 = hexToRgba(colorPrimary, 40);
    const colorFill80 = hexToRgba(colorFill, 80);
    const colorFillQuaternary60 = hexToRgba(colorFillQuaternary, 60);
    const getBackgroundColor = (args) => {
      const { row, table } = args;
      const index = row - table.frozenRowCount;
      if (!(index & 1)) {
        return 'transparent';
      }
      return colorFillQuaternary60;
    };
    
    return {
      underlayBackgroundColor: 'transparent',
      dragHeaderSplitLine: {
        lineColor: colorPrimary,
      },
      defaultStyle: {
        color: colorText,
        bgColor: colorFillQuaternary,
        borderColor: colorBorderSecondary,
        fontSize: customFontSize ? (customFontSize - 1) : 12,
        fontFamily: fontFamily,
        fontWeight: 400,
        padding: [2, 6, 0, 6],
        borderLineWidth(args) {
          if (args.col === 0 && !customOptions?.showLeftBorder) {
            return [1, 1, 1, 0];
          }
        },
      },
      headerStyle: {
        color: colorText,
        bgColor: colorFillQuaternary,
        borderColor: colorBorderSecondary,
        fontSize: customFontSize ? (customFontSize + 1) : 14,
        fontFamily: fontFamily,
        fontWeight: 400,
        padding: [2, 6, 0, 6],
      },
      frameStyle: {
        borderColor: 'transparent',
        borderLineWidth: 0,
        borderLineDash: [],
        cornerRadius: 0,
        shadowBlur: 0,
        shadowOffsetX: 0,
        shadowOffsetY: 0,
        shadowColor: 'none',
      },
      cornerHeaderStyle: {
        bgColor: colorFillQuaternary,
      },
      scrollStyle: {
        visible: 'always',
        scrollSliderColor: colorFill80,
        width: 10,
        barToSide: true,
        hoverOn: false,
      },
      bodyStyle: {
        color: (args) => {
          if (args.col === 0 && !!options?.rowSeriesNumber) {
            return colorTextSecondary;
          }
          return colorText
        },
        bgColor: getBackgroundColor,
        textBaseline: 'top',
        fontSize: customFontSize || 13,
        fontFamily: fontFamily,
        fontWeight: 400,
        padding: [6, 6, 0, 6],
        lineHeight: 18,
        borderLineWidth(args) {
          if (args.col === 0  && !customOptions?.showLeftBorder) {
            return [1, 1, 1, 0];
          }
        },
      },
      columnResize: {
        lineWidth: 1,
        lineColor: colorPrimary,
        bgColor: colorPrimary,
        width: 2,
        labelVisible: false,
        resizeHotSpotSize: 6
      },
      frozenColumnLine: {
        shadow: {
          width: customOptions?.showFrozenColumnDivider ? 1 : 4,
          startColor: customOptions?.showFrozenColumnDivider
            ? colorPrimary
            : 'transparent',
          endColor: customOptions?.showFrozenColumnDivider
            ? colorPrimary
            : 'transparent',
        },
      },
      selectionStyle: {
        cellBgColor: colorPrimary4,
        cellBorderColor: colorPrimary,
        cellBorderLineWidth: 2,
        inlineRowBgColor: colorPrimary40,
      },
      functionalIconsStyle: {
        sort_color: colorTextSecondary,
        sort_color_opacity: '1',
        sort_color_2: colorPrimary,
        sort_color_opacity_2: '1',
      },
    };
  }, [antdTheme.appearance, customOptions?.showFrozenColumnDivider]);
  return theme;
};

export default useTableTheme;
