import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  toolbar: css`
    display: grid;
    grid-template-columns: auto minmax(56px, 1fr) minmax(0, auto);
    align-items: center;
    width: 100%;
    min-width: 0;
    height: 100%;
    gap: 6px;
  `,
  navigationActions: css`
    display: flex;
    align-items: center;
    gap: 2px;
    flex-shrink: 0;
    -webkit-app-region: no-drag;
  `,
  dragRegion: css`
    align-self: stretch;
    min-width: 56px;
    -webkit-app-region: drag;
  `,
  rightActions: css`
    display: flex;
    align-items: center;
    justify-content: flex-end;
    min-width: 0;
    max-width: min(440px, 62vw);
    gap: 4px;
    -webkit-app-region: no-drag;
  `,
  workspaceActions: css`
    display: flex;
    align-items: center;
    min-width: 0;
    overflow-x: auto;
    overflow-y: hidden;
    scrollbar-width: none;

    &::-webkit-scrollbar {
      display: none;
    }
  `,
  layoutActions: css`
    flex-shrink: 0;
    margin-left: 4px;
    padding-left: 6px;
    border-left: 1px solid ${token.colorBorderSecondary};
  `,
  accountActions: css`
    display: flex;
    align-items: center;
    flex-shrink: 0;
    padding-left: 6px;
    border-left: 1px solid ${token.colorBorderSecondary};
  `,
}));
