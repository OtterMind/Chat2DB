import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  root: css`
    display: flex;
    flex-direction: column;
    gap: 12px;
  `,
  hint: css`
    color: ${token.colorTextSecondary};
    font-size: 12px;
    line-height: 1.5;
    margin-bottom: 8px;
  `,
  onboardingCompact: css`
    border-radius: 8px;
    padding: 10px 12px;
    background: ${token.colorFillAlter};
  `,
  onboardingLead: css`
    font-size: 13px;
    color: ${token.colorTextSecondary};
    line-height: 1.5;
  `,
  howDetails: css`
    margin-top: 6px;
    font-size: 12px;
    color: ${token.colorTextTertiary};

    summary {
      cursor: pointer;
      user-select: none;
      color: ${token.colorTextSecondary};
    }
  `,
  steps: css`
    margin: 6px 0 0;
    padding-left: 18px;
    color: ${token.colorTextSecondary};
    font-size: 12px;
    line-height: 1.7;
  `,
  /** One card per vendor/provider; ~2–3 per row on typical modal width. */
  providerGrid: css`
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
    gap: 10px;
  `,
  card: css`
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: 10px;
    padding: 12px;
    display: flex;
    flex-direction: column;
    gap: 8px;
    background: ${token.colorBgContainer};
    min-height: 140px;
  `,
  cardMuted: css`
    border: 1px dashed ${token.colorBorderSecondary};
    border-radius: 10px;
    padding: 12px;
    opacity: 0.75;
    background: ${token.colorFillQuaternary};
    min-height: 140px;
  `,
  header: css`
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 8px;
  `,
  titleBlock: css`
    min-width: 0;
    flex: 1;
  `,
  title: css`
    font-weight: 600;
    font-size: 14px;
  `,
  account: css`
    color: ${token.colorTextTertiary};
    font-size: 12px;
    margin-top: 2px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  `,
  actions: css`
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  `,
  modelList: css`
    border-top: 1px solid ${token.colorBorderSecondary};
    padding-top: 8px;
    display: flex;
    flex-direction: column;
    gap: 6px;
    flex: 1;
    min-height: 0;
  `,
  modelSummary: css`
    font-size: 11px;
    color: ${token.colorTextTertiary};
  `,
  modelNames: css`
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 4px;
    max-height: 120px;
    overflow: auto;
  `,
  modelNameItem: css`
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 12px;
    line-height: 1.3;
    min-width: 0;
  `,
  modelNameItemMuted: css`
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 12px;
    line-height: 1.3;
    min-width: 0;
    opacity: 0.55;
  `,
  modelDot: css`
    width: 5px;
    height: 5px;
    border-radius: 50%;
    background: ${token.colorSuccess};
    flex-shrink: 0;
  `,
  modelName: css`
    font-weight: 500;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  `,
  modelEmpty: css`
    font-size: 12px;
    color: ${token.colorTextSecondary};
    padding: 2px 0;
  `,
  advanced: css`
    font-size: 12px;
    color: ${token.colorTextSecondary};

    summary {
      cursor: pointer;
      user-select: none;
    }
  `,
  advancedBody: css`
    margin-top: 8px;
    padding: 10px 12px;
    border-radius: 8px;
    background: ${token.colorFillQuaternary};
  `,
}));
