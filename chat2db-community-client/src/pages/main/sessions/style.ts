import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  container: css`
    display: flex;
    flex-direction: column;
    height: 100%;
    min-width: 0;
    background: ${token.colorBgLayout};
  `,
  header: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    min-height: 48px;
    padding: 8px 16px;
    border-bottom: 1px solid ${token.colorBorderLayout};
    background: ${token.colorBgBase};
  `,
  titleGroup: css`
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 2px;
  `,
  title: css`
    font-size: 16px;
    font-weight: 600;
    color: ${token.colorText};
  `,
  subtitle: css`
    font-size: 12px;
    color: ${token.colorTextTertiary};
  `,
  toolbar: css`
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: flex-end;
    gap: 8px;
  `,
  selector: css`
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 10px 16px;
    border-bottom: 1px solid ${token.colorBorderLayout};
    background: ${token.colorBgBase};
  `,
  body: css`
    flex: 1;
    min-height: 0;
    padding: 12px 16px 16px;
    overflow: auto;
  `,
  tableWrap: css`
    height: 100%;
    min-height: 360px;
    background: ${token.colorBgBase};
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: 6px;
    overflow: hidden;
  `,
  codeCell: css`
    display: block;
    max-width: 520px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-family: ${token.fontFamilyCode};
  `,
  result: css`
    margin: 0 16px;
  `,
}));
