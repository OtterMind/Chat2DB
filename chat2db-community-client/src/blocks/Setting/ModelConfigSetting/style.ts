import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  layout: css`display:grid;grid-template-columns:260px minmax(0,680px);gap:28px;min-height:520px;`,
  listPane: css`min-width:0;border-right:1px solid ${token.colorSplit};padding-right:20px;`,
  listHeader: css`display:flex;align-items:center;justify-content:space-between;gap:10px;margin-bottom:12px;`,
  list: css`display:flex;flex-direction:column;gap:4px;`,
  item: css`
    display:flex;width:100%;flex-direction:column;gap:4px;padding:10px;border:0;border-radius:6px;
    background:transparent;color:${token.colorTextTertiary};font-size:12px;text-align:left;cursor:pointer;
    &:hover{background:${token.colorFillSecondary};}
  `,
  itemActive: css`background:${token.colorPrimaryBg};color:${token.colorPrimary};`,
  itemTop: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    color: ${token.colorText};
    font-size: 13px;
  `,
  editor: css`min-width:0;.ant-alert{margin-bottom:20px;}.ant-form-item{margin-bottom:16px;}`,
  row: css`display:grid;grid-template-columns:1fr 1fr;gap:16px;`,
  switches: css`display:flex;gap:40px;`,
  actions: css`
    display: grid;
    grid-template-columns: auto 1fr auto auto;
    align-items: center;
    gap: 10px;
    padding-top: 18px;
    border-top: 1px solid ${token.colorSplit};
  `,
}));
