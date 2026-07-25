import { createStyles, FullToken } from 'antd-style';

export interface OutspreadFullToken extends FullToken {
  colorBorderLayout: string;
}

export const useStyles = createStyles(({ css, token }, { sidebarExpanded }: { sidebarExpanded?: boolean }) => {
  return {
    container: css`
      display: flex;
      width: 100vw;
      height: 100%;
    `,
    leftContainer: css`
      width: ${sidebarExpanded ? 220 : 64}px;
      flex-shrink: 0;
      display: flex;
      flex-direction: column;
      border-right: 1px solid ${token.colorBorderLayout};
      background-color: ${token.colorBgBase};
      user-select: none;
      transition: width 0.25s cubic-bezier(0.4, 0, 0.2, 1);
      overflow: hidden;
    `,
    leftContainerHidden: css`
      display: none !important;
    `,

    /* ── Sidebar header: toggle + new chat ─────────────── */
    sidebarHeader: css`
      display: flex;
      align-items: center;
      flex-shrink: 0;
      ${sidebarExpanded
        ? `flex-direction: row; justify-content: space-between; height: 36px; padding: 0 12px;`
        : `flex-direction: column; gap: 4px; padding: 8px 0;`}
    `,
    sidebarHeaderSpacer: css`
      width: 30px;
      height: 30px;
      flex-shrink: 0;
    `,
    sidebarBtn: css`
      display: flex;
      align-items: center;
      justify-content: center;
      border-radius: 50% !important;
      color: ${token.colorTextSecondary};
      flex-shrink: 0;
      &:hover {
        background-color: ${token.colorFillTertiary};
      }
    `,

    /* ── Nav items ─────────────────────────────────────── */
    navContainer: css`
      display: flex;
      flex-direction: column;
      gap: ${sidebarExpanded ? '2px' : '6px'};
      padding: ${sidebarExpanded ? '4px 8px' : '4px 0'};
      align-items: ${sidebarExpanded ? 'stretch' : 'center'};
    `,
    navItem: css`
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 0 12px;
      height: 40px;
      border-radius: 24px;
      cursor: pointer;
      color: ${token.colorText};
      white-space: nowrap;
      transition: background-color 0.15s;
      &:hover {
        background-color: ${token.colorFillTertiary};
      }
    `,
    navItemActive: css`
      background-color: ${token.colorPrimaryBg};
      &:hover {
        background-color: ${token.colorPrimaryBg};
      }
    `,
    navItemIcon: css`
      display: flex;
      align-items: center;
      justify-content: center;
      width: 20px;
      height: 20px;
      flex-shrink: 0;
      font-size: 20px;
    `,
    navItemLabel: css`
      font-size: 14px;
      font-weight: 500;
      overflow: hidden;
    `,

    /* ── Session history section (expanded only) ───────── */
    sessionSection: css`
      flex: 1;
      min-height: 0;
      display: ${sidebarExpanded ? 'flex' : 'none'};
      flex-direction: column;
      border-top: 1px solid ${token.colorBorderLayout};
      margin-top: 8px;
      padding-top: 8px;
      overflow: hidden;
    `,
    /* ── Bottom nav ────────────────────────────────────── */
    bottomNav: css`
      display: flex;
      flex-direction: column;
      flex-shrink: 0;
      margin-top: auto;
      ${sidebarExpanded
        ? `gap: 2px; padding: 8px 8px 12px; align-items: stretch; border-top: 1px solid ${token.colorBorderLayout};`
        : `gap: 8px; padding: 12px 0; align-items: center;`}
    `,

    /* ── Right content ─────────────────────────────────── */
    rightContainer: css`
      position: relative;
      flex: 1;
      background-color: ${token.colorBgBase};
      overflow: auto;
    `,
    componentBox: css`
      width: 100%;
      height: 100%;
    `,
  };
});
