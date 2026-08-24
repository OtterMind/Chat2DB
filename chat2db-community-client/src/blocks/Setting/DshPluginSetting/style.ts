import { createStyles } from 'antd-style';

export const useDshPluginStyles = createStyles(({ css, token }) => ({
  brandPanel: css`
    display: flex;
    align-items: center;
    gap: 16px;
    padding: 18px;
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: 10px;
    background: ${token.colorFillQuaternary};
  `,
  brandLogo: css`
    width: 60px;
    height: 60px;
    flex: 0 0 auto;
    padding: 8px;
    border: 1px solid rgb(0 0 0 / 10%);
    border-radius: 14px;
    background: #fff;
    box-shadow: 0 4px 12px rgb(0 0 0 / 8%);
  `,
  brandCopy: css`
    min-width: 0;
  `,
  brandName: css`
    color: ${token.colorText};
    font-size: 17px;
    font-weight: 600;
    line-height: 24px;
  `,
  brandDescription: css`
    margin-top: 4px;
    color: ${token.colorTextSecondary};
    font-size: 13px;
    line-height: 20px;
  `,
  switch: css`
    align-self: flex-start;
  `,
}));
