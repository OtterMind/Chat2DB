import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    treeBox: css`
      flex: 1;
      padding-left: 6px;
    `,
    panelPane: css`
      display: none;
      min-height: 0;
      flex: 1;
      flex-direction: column;
    `,
    panelPaneActive: css`
      display: flex;
      min-height: 0;
      flex: 1;
      flex-direction: column;
    `,
    resourceSwitcher: css`
      display: flex;
      align-items: center;
      justify-content: space-between;
      flex-shrink: 0;
      height: 42px;
      padding: 0 8px 0 12px;
      border-bottom: 1px solid ${token.colorBorderLayout};
    `,
    resourceSelector: css`
      display: inline-flex;
      align-items: center;
      gap: 6px;
      min-width: 0;
      height: 30px;
      padding: 0 6px;
      border: 0;
      border-radius: 6px;
      color: ${token.colorText};
      background: transparent;
      cursor: pointer;
      font-size: 14px;
      font-weight: 600;
      letter-spacing: 0;

      &:hover {
        background: ${token.colorFillTertiary};
      }

      &:focus-visible {
        outline: 2px solid ${token.colorPrimaryBorder};
        outline-offset: 1px;
      }
    `,
    resourceSelectorLabel: css`
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    `,
    resourceMenuItem: css`
      display: inline-flex;
      align-items: center;
      gap: 8px;
    `,
    noConnectionList: css`
      height: 100%;
      margin-top: 30vh;
      text-align: center;
      font-size: 14px;
    `,
    noConnectionListIcon: css`
      font-size: 60px;
      color: ${token.colorPrimary};
    `,
    noConnectionListTips: css`
      margin: 10px 0px;
    `,
    create: css`
      color: ${token.colorPrimary};
      text-decoration: underline;
      cursor: pointer;
      margin-right: 4px;
      &:hover {
        color: ${token.colorPrimaryHover};
      }
    `,
  };
});
