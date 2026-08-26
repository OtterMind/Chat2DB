import { createStyles } from 'antd-style';
import { hexToRgba } from '@/utils/color';

export const useStyles = createStyles(({ css, token }) => {
  const loadingBackground = hexToRgba(token.colorFill, 20);

  return {
    loading: css`
      position: absolute;
      z-index: 1;
      top: 0;
      right: 0;
      bottom: 0;
      left: 0;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      overflow: hidden;
      background-color: ${loadingBackground};
    `,
    cancel: css`
      margin-top: 30px;
      cursor: pointer;

      &:hover {
        color: ${token.colorPrimary};
      }
    `,
  };
});
