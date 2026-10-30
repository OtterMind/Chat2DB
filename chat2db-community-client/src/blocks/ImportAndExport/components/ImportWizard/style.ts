import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  container: css`
    display: flex;
    height: min(640px, 82vh, calc(100vh - 116px));
    height: min(640px, 82dvh, calc(100dvh - 116px));
    min-width: 0;
    flex-direction: column;
  `,
  sqlContainer: css`
    --import-file-min-height: 320px;
    height: min(440px, 82vh, calc(100vh - 116px));
    height: min(440px, 82dvh, calc(100dvh - 116px));
  `,
  scrollContent: css`
    flex: 1;
    min-height: 0;
    overflow: auto;
    padding: 8px 4px 18px 0;
  `,
  mappingScrollContent: css`
    display: flex;
    flex-direction: column;
    overflow: hidden;
  `,
  mappingStep: css`
    display: flex;
    flex: 1;
    min-height: 0;
    flex-direction: column;
  `,
  stepPanel: css`min-width: 0;`,
  fileStep: css`
    display: flex;
    height: 100%;
    min-height: var(--import-file-min-height, 380px);
    flex-direction: column;
    &[hidden] { display: none; }
  `,
  formatStep: css`
    display: flex;
    min-height: 100%;
    flex-direction: column;
  `,
  formatToolbar: css`
    display: flex;
    flex: none;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 20px;
  `,
  templateActions: css`display: flex; align-items: center; gap: 10px;`,
  sectionTitle: css`margin: 0; font-size: 14px; font-weight: 500;`,
  errorAlert: css`flex: none; margin-bottom: 12px;`,
  targetTableSelect: css`width: 100%;`,
  executionEnvironment: css`
    min-width: 0;
    margin: 0;
    padding: 3px 8px;
    border: 1px solid ${token.colorBorder};
    border-radius: ${token.borderRadius}px;
    > div { flex-wrap: wrap; }
    &:disabled { pointer-events: none; opacity: 0.65; }
  `,
  parseStatus: css`
    display: flex;
    min-height: 22px;
    gap: 8px;
    align-items: center;
    margin-bottom: 10px;
    font-size: 12px;
  `,
  parseStatusError: css`color: ${token.colorError}; overflow-wrap: anywhere;`,
  previewState: css`display: flex; flex: 1; align-items: center; justify-content: center;`,
  previewError: css`
    display: flex;
    max-width: calc(100% - 32px);
    flex-direction: column;
    align-items: center;
    gap: 10px;
    color: ${token.colorError};
    line-height: 1.6;
    text-align: center;
    overflow-wrap: anywhere;
  `,
  policySection: css`margin-bottom: 22px;`,
  policyRow: css`
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 24px;
    margin-top: 14px;
  `,
  policyField: css`display: flex; min-width: 0; flex-direction: column; gap: 8px;`,
  policyLabel: css`
    display: flex;
    align-items: center;
    gap: 4px;
    color: ${token.colorTextSecondary};
    font-size: 12px;
  `,
  policyModeRow: css`margin-top: 14px;`,
  summary: css`padding-top: 18px; border-top: 1px solid ${token.colorBorderSecondary};`,
  summaryHeading: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 12px;
  `,
  summaryIdentity: css`
    margin: 0 0 12px;
    > div {
      display: grid;
      grid-template-columns: 110px minmax(0, 1fr) auto;
      gap: 16px;
      align-items: baseline;
      padding: 10px 0;
      border-bottom: 1px solid ${token.colorBorderSecondary};
    }
    dt, span { color: ${token.colorTextSecondary}; font-size: 12px; }
    dd { margin: 0; overflow-wrap: anywhere; }
  `,
  summaryGrid: css`
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 0 28px;
    margin: 0;
    > div {
      display: grid;
      grid-template-columns: 110px minmax(0, 1fr);
      gap: 16px;
      align-items: baseline;
      padding: 10px 0;
      border-bottom: 1px solid ${token.colorBorderSecondary};
    }
    dt { color: ${token.colorTextSecondary}; font-size: 12px; }
    dd { margin: 0; overflow-wrap: anywhere; }
    @media (max-width: 700px) { grid-template-columns: 1fr; }
  `,
  templateBar: css`display: flex; align-items: center;`,
  templateSelect: css`width: 180px;`,
  templateOption: css`display: flex; align-items: center; justify-content: space-between; gap: 8px;`,
  templateOptionName: css`overflow: hidden; text-overflow: ellipsis; white-space: nowrap;`,
  templateOptionDelete: css`flex: none; color: ${token.colorTextSecondary};`,
  footerLeft: css`display: flex; align-items: center; gap: 8px;`,
  footer: css`
    display: flex;
    flex: none;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    padding-top: 14px;
    border-top: 1px solid ${token.colorBorderSecondary};
    background: ${token.colorBgElevated};
  `,
  footerRight: css`display: flex; align-items: center; gap: 8px;`,
}));
