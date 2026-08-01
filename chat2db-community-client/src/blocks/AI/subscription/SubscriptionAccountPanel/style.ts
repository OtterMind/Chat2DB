import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  root: css`
    display: flex;
    flex-direction: column;
    gap: 16px;
  `,
  hint: css`
    color: ${token.colorTextSecondary};
    font-size: 13px;
    line-height: 1.5;
  `,
  onboarding: css`
    border-radius: 10px;
    padding: 14px 16px;
    background: ${token.colorFillAlter};
  `,
  onboardingTitle: css`
    font-size: 15px;
    font-weight: 600;
    margin-bottom: 8px;
  `,
  steps: css`
    margin: 0;
    padding-left: 20px;
    color: ${token.colorTextSecondary};
    font-size: 13px;
    line-height: 1.8;
  `,
  card: css`
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: 10px;
    padding: 14px 16px;
    display: flex;
    flex-direction: column;
    gap: 10px;
  `,
  header: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  `,
  title: css`
    font-weight: 600;
    font-size: 14px;
  `,
  status: css`
    color: ${token.colorTextSecondary};
    font-size: 13px;
  `,
  account: css`
    color: ${token.colorTextTertiary};
    font-size: 12px;
  `,
  actions: css`
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  `,
}));
