import { createStyles } from 'antd-style';

export const useTaskScheduleStyles = createStyles(({ css, token }) => ({
  page: css`
    display: flex;
    width: 100%;
    height: 100%;
    min-width: 0;
    flex-direction: column;
    background: ${token.colorBgLayout};
  `,
  header: css`
    display: flex;
    min-height: 56px;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    padding: 0 18px;
    border-bottom: 1px solid ${token.colorBorderSecondary};
    background: ${token.colorBgContainer};
  `,
  headerIdentity: css`
    display: flex;
    min-width: 0;
    align-items: center;
    gap: 12px;
  `,
  headerTitle: css`
    min-width: 0;
    h1 {
      margin: 0;
      color: ${token.colorText};
      font-size: 16px;
      font-weight: 650;
    }
    p {
      margin: 2px 0 0;
      overflow: hidden;
      color: ${token.colorTextTertiary};
      font-size: 12px;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  `,
  workspace: css`
    display: grid;
    flex: 1;
    min-height: 0;
    grid-template-columns: 320px minmax(0, 1fr);
    background: ${token.colorBgContainer};
    @media (max-width: 920px) {
      grid-template-columns: 280px minmax(0, 1fr);
    }
    @media (max-width: 720px) {
      display: block;
      overflow: auto;
    }
  `,
  sidebar: css`
    display: flex;
    min-width: 0;
    min-height: 0;
    flex-direction: column;
    border-right: 1px solid ${token.colorBorderSecondary};
    background: ${token.colorBgLayout};
    @media (max-width: 720px) {
      max-height: 280px;
      border-right: 0;
      border-bottom: 1px solid ${token.colorBorderSecondary};
    }
  `,
  sidebarHeader: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 14px 14px 10px;
    strong {
      color: ${token.colorText};
      font-size: 13px;
    }
    span {
      margin-left: 6px;
      color: ${token.colorTextTertiary};
      font-size: 11px;
    }
  `,
  scheduleList: css`
    display: flex;
    min-height: 0;
    flex: 1;
    flex-direction: column;
    gap: 4px;
    overflow: auto;
    padding: 2px 8px 12px;
  `,
  scheduleItem: css`
    width: 100%;
    padding: 11px 10px;
    border: 1px solid transparent;
    border-radius: ${token.borderRadiusLG}px;
    background: transparent;
    color: ${token.colorText};
    cursor: pointer;
    text-align: left;
    transition: background 0.15s, border-color 0.15s, transform 0.15s;
    &:hover,
    &:focus-visible {
      border-color: ${token.colorBorderSecondary};
      background: ${token.colorBgContainer};
      outline: none;
    }
    &:active {
      transform: translateY(1px);
    }
  `,
  scheduleItemSelected: css`
    border-color: ${token.colorPrimaryBorder};
    background: ${token.colorPrimaryBg};
  `,
  scheduleItemTop: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    strong {
      overflow: hidden;
      font-size: 13px;
      font-weight: 600;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  `,
  scheduleItemMeta: css`
    display: flex;
    margin-top: 7px;
    align-items: center;
    gap: 7px;
    color: ${token.colorTextTertiary};
    font-size: 11px;
    span {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  `,
  scheduleItemNext: css`
    margin-top: 5px;
    color: ${token.colorTextQuaternary};
    font-size: 10px;
  `,
  sidebarEmpty: css`
    display: grid;
    min-height: 180px;
    place-items: center;
    padding: 18px;
  `,
  main: css`
    min-width: 0;
    min-height: 0;
    overflow: auto;
    background: ${token.colorBgContainer};
  `,
  mainInner: css`
    width: min(100%, 980px);
    margin: 0 auto;
    padding: 24px 28px 36px;
    @media (max-width: 720px) {
      padding: 20px 16px 28px;
    }
  `,
  scopeSummary: css`
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 5px;
    small {
      flex-basis: 100%;
      color: ${token.colorWarningText};
      font-size: 11px;
      line-height: 1.45;
    }
  `,
  sectionHeader: css`
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 20px;
    margin-bottom: 22px;
    h2 {
      margin: 0;
      color: ${token.colorText};
      font-size: 22px;
      font-weight: 650;
      letter-spacing: -0.015em;
    }
    p {
      max-width: 620px;
      margin: 6px 0 0;
      color: ${token.colorTextTertiary};
      font-size: 12px;
      line-height: 1.6;
    }
  `,
  sectionActions: css`
    display: flex;
    flex: none;
    flex-wrap: wrap;
    justify-content: flex-end;
    gap: 8px;
  `,
  notice: css`
    width: 100%;
    max-width: 760px;
    margin-bottom: 20px;
  `,
  form: css`
    max-width: 760px;
    .ant-form-item-label > label {
      color: ${token.colorTextSecondary};
      font-size: 12px;
      font-weight: 550;
    }
  `,
  formGrid: css`
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    gap: 0 16px;
    @media (max-width: 720px) {
      grid-template-columns: 1fr;
    }
  `,
  formActions: css`
    display: flex;
    justify-content: flex-end;
    gap: 8px;
    margin-top: 22px;
    padding-top: 16px;
    border-top: 1px solid ${token.colorBorderSecondary};
  `,
  preview: css`
    margin-top: 8px;
    padding: 10px 12px;
    border-radius: ${token.borderRadiusLG}px;
    background: ${token.colorFillQuaternary};
    color: ${token.colorTextSecondary};
    font-size: 12px;
    line-height: 1.8;
  `,
  detailSummary: css`
    margin-bottom: 26px;
    .ant-descriptions-item-label {
      color: ${token.colorTextTertiary};
      font-size: 12px;
    }
    .ant-descriptions-item-content {
      color: ${token.colorText};
      font-size: 12px;
    }
  `,
  textBlock: css`
    margin-top: 18px;
    h3 {
      margin: 0 0 7px;
      color: ${token.colorText};
      font-size: 13px;
    }
    p {
      margin: 0;
      color: ${token.colorTextSecondary};
      line-height: 1.65;
      white-space: pre-wrap;
    }
  `,
  history: css`
    margin-top: 28px;
    padding-top: 22px;
    border-top: 1px solid ${token.colorBorderSecondary};
  `,
  historyHeader: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 12px;
    h3 {
      margin: 0;
      color: ${token.colorText};
      font-size: 15px;
      font-weight: 600;
    }
  `,
  emptyMain: css`
    display: grid;
    min-height: 420px;
    place-items: center;
  `,
}));
