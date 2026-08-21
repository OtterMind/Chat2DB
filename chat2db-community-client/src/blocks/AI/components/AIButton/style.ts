import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css }) => {
  return {
    aiButton: css`
      position: relative;
      cursor: pointer;
    `,
    defaultImage: css`
      display: block;
      width: 100%;
      height: 100%;
    `,
    hoverImage: css`
      position: absolute;
      top: 0;
      left: 0;
      width: 100%;
      height: 100%;
      opacity: 0;
      transition: opacity 0.3s ease;
      &:hover {
        opacity: 1;
      }

      ${'.aiButton'}:hover & {
        opacity: 1;
      }
    `,
  };
});
