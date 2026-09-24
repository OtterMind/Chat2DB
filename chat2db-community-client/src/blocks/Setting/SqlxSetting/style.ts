import { createStyles } from 'antd-style';

export const useSqlxStyles = createStyles(({ css, token }) => ({
  stateLine: css`
    min-height: 24px;
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 10px;
  `,
  stateLabel: css`
    color: ${token.colorTextSecondary};
    font-size: 13px;
    line-height: 20px;
  `,
  badge: css`
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 2px 8px;
    border-radius: 10px;
    background: ${token.colorFillTertiary};
    color: ${token.colorTextSecondary};
    font-size: 12px;
    line-height: 18px;
  `,
  badgeWarning: css`
    background: ${token.colorWarningBg};
    color: ${token.colorWarningText};
  `,
  badgeError: css`
    background: ${token.colorErrorBg};
    color: ${token.colorErrorText};
  `,
  describe: css`
    max-width: 640px;
    color: ${token.colorTextSecondary};
    font-size: 13px;
    line-height: 20px;
  `,
  detailLine: css`
    display: flex;
    flex-wrap: wrap;
    gap: 6px 12px;
    color: ${token.colorTextSecondary};
    font-size: 12px;
    line-height: 18px;
  `,
  monospace: css`
    font-family: ${token.fontFamilyCode};
    word-break: break-all;
  `,
  actions: css`
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 12px;
  `,
  commandBox: css`
    display: flex;
    align-items: flex-start;
    gap: 12px;
    padding: 10px 12px;
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: 6px;
    background: ${token.colorFillQuaternary};

    &:hover [data-sqlx-copy],
    &:focus-within [data-sqlx-copy] {
      opacity: 1;
    }
  `,
  copySlot: css`
    flex: 0 0 auto;
    align-self: flex-start;
    opacity: 0;
    transition: opacity 150ms ease;
  `,
  commandColumn: css`
    flex: 1 1 auto;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 4px;
  `,
  commandText: css`
    flex: 1 1 auto;
    min-width: 0;
    margin: 0;
    color: ${token.colorText};
    font-family: ${token.fontFamilyCode};
    font-size: 12px;
    line-height: 18px;
    white-space: pre-wrap;
    word-break: break-all;
  `,
  disclosure: css`
    align-self: flex-start;
    padding: 0;
    border: 0;
    background: transparent;
    color: ${token.colorTextSecondary};
    font: inherit;
    font-size: 13px;
    line-height: 20px;
    cursor: pointer;

    &:hover {
      color: ${token.colorPrimary};
    }

    &:focus-visible {
      border-radius: 4px;
      outline: 2px solid ${token.colorPrimary};
      outline-offset: 2px;
    }
  `,
  hint: css`
    color: ${token.colorTextTertiary};
    font-size: 12px;
    line-height: 18px;
  `,
  dataSourceRow: css`
    width: 100%;
    display: flex;
    flex-direction: column;
    gap: 12px;
  `,
  /** The import action sits under the table, aligned with its right edge. */
  dataSourceFooter: css`
    display: flex;
    justify-content: flex-end;
  `,
  dataSourceTable: css`
    width: 100%;
    min-width: 0;
  `,
  placeholder: css`
    padding: 12px;
    border: 1px dashed ${token.colorBorderSecondary};
    border-radius: 6px;
    color: ${token.colorTextTertiary};
    font-size: 13px;
    line-height: 20px;
  `,
}));
