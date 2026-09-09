import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  root: css`
    display: flex;
    height: 100%;
    min-height: 0;
    flex-direction: column;
    background: ${token.colorBgContainer};
  `,
    header: css`
    display: flex;
    height: 48px;
    flex: 0 0 48px;
    align-items: center;
    justify-content: space-between;
    padding: 0 20px;
    border-bottom: 1px solid ${token.colorBorderSecondary};
    `,
    runtimeActions: css`
      display: inline-flex;
      align-items: center;
      gap: 6px;
    `,
    runtimeConfigButton: css`
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 24px;
      height: 24px;
      padding: 0;
      border: 0;
      border-radius: 4px;
      color: inherit;
      background: transparent;
      cursor: pointer;

      &:hover {
        color: ${token.colorPrimary};
        background: ${token.colorFillSecondary};
      }
    `,
    runtimeConfigPanel: css`
      min-width: 180px;
    `,
    runtimeConfigTitle: css`
      margin-bottom: 8px;
      color: ${token.colorText};
      font-weight: 500;
    `,
  title: css`
    min-width: 0;
    overflow: hidden;
    color: ${token.colorText};
    font-size: 14px;
    font-weight: 600;
    text-overflow: ellipsis;
    white-space: nowrap;
  `,
  transcript: css`
    flex: 1;
    min-height: 0;
    overflow: auto;
    padding: 24px max(24px, calc((100% - 820px) / 2));
  `,
  empty: css`
    display: grid;
    height: 100%;
    place-items: center;
    color: ${token.colorTextTertiary};
    font-size: 14px;
  `,
  message: css`
    margin-bottom: 24px;
    color: ${token.colorText};
    font-size: 14px;
    line-height: 1.7;
  `,
  userMessage: css`
    width: fit-content;
    max-width: 78%;
    margin-left: auto;
    padding: 9px 12px;
    border-radius: 8px;
    background: ${token.colorFillSecondary};
    white-space: pre-wrap;
  `,
  assistantMessage: css`
    overflow-wrap: anywhere;
  `,
  status: css`
    margin-top: 6px;
    color: ${token.colorError};
    font-size: 12px;
  `,
  composer: css`
    display: flex;
    width: min(820px, calc(100% - 48px));
    flex: 0 0 auto;
    align-items: flex-end;
    gap: 8px;
    margin: 0 auto;
    padding: 12px 0 18px;
  `,
  input: css`
    flex: 1;
  `,
  model: css`
    width: 180px;
  `,
  iconButton: css`
    width: 36px;
    height: 36px;
    padding: 0;
  `,
}));
