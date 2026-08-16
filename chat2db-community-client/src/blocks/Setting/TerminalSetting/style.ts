import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  settingsList: css`
    width: 100%;
    container-type: inline-size;
  `,
  settingRow: css`
    padding: 24px 0;
    border-bottom: 1px solid ${token.colorSplit};
    display: grid;
    grid-template-columns: minmax(180px, 240px) minmax(0, 1fr);
    align-items: start;
    gap: 32px;

    @container (max-width: 720px) {
      grid-template-columns: minmax(0, 1fr);
      gap: 16px;
    }
  `,
  settingMeta: css`
    min-width: 0;
    display: grid;
    grid-template-columns: 20px minmax(0, 1fr);
    align-items: start;
    gap: 10px;
  `,
  settingMetaContent: css`
    min-width: 0;
  `,
  settingGroupIcon: css`
    margin-top: 2px;
    color: ${token.colorPrimary};
  `,
  settingTitle: css`
    font-size: 14px;
    font-weight: 500;
    line-height: 22px;
  `,
  settingDescription: css`
    max-width: 280px;
    margin-top: 4px;
    color: ${token.colorTextSecondary};
    font-size: 13px;
    line-height: 20px;
  `,
  settingControl: css`
    min-width: 0;
  `,
  switchControl: css`
    min-width: 0;
    min-height: 32px;
    display: flex;
    align-items: center;
  `,
  controlState: css`
    width: min(280px, 100%);
    min-height: 32px;
    display: flex;
    align-items: center;
  `,
  capabilitiesAlert: css`
    max-width: 440px;
  `,
  shellSelect: css`
    width: min(280px, 100%);
  `,
  positionSegmented: css`
    width: min(520px, 100%);

    :global(.ant-segmented-group) {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
    }

    :global(.ant-segmented-item-label) {
      min-width: 0;
      padding-inline: 8px;
    }
  `,
  positionOption: css`
    min-width: 0;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 6px;
    white-space: nowrap;
  `,
  hint: css`
    max-width: 640px;
    margin-top: 8px;
    color: ${token.colorTextTertiary};
    font-size: 12px;
    line-height: 18px;
  `,
  themeGrid: css`
    width: 100%;
    max-width: 640px;
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(112px, 1fr));
    gap: 14px;
  `,
  themeOption: css`
    min-width: 0;
    padding: 0;
    border: 0;
    display: flex;
    flex-direction: column;
    gap: 8px;
    background: transparent;
    color: ${token.colorTextSecondary};
    font: inherit;
    cursor: pointer;

    &:hover {
      color: ${token.colorText};

      > span:first-child {
        border-color: ${token.colorTextTertiary};
      }
    }

    &:focus-visible {
      outline: none;

      > span:first-child {
        outline: 2px solid ${token.colorPrimary};
        outline-offset: 3px;
      }
    }
  `,
  activeThemeOption: css`
    color: ${token.colorText};
    font-weight: 500;
  `,
  themePreview: css`
    position: relative;
    box-sizing: border-box;
    width: 100%;
    aspect-ratio: 8 / 5;
    overflow: hidden;
    padding: 12px;
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: 6px;
    display: flex;
    flex-direction: column;
    justify-content: center;
    gap: 10px;
    text-align: left;
    transition: border-color 0.15s ease;
  `,
  activeThemePreview: css`
    border-color: ${token.colorPrimary};
    box-shadow: 0 0 0 1px ${token.colorPrimary};
  `,
  themeCheck: css`
    position: absolute;
    top: 7px;
    right: 7px;
    width: 20px;
    height: 20px;
    border-radius: 50%;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    background-color: ${token.colorPrimary};
    color: ${token.colorTextLightSolid};
  `,
  colorRow: css`
    display: flex;
    gap: 5px;

    > span {
      width: 8px;
      height: 8px;
      border: 1px solid rgb(127 127 127 / 25%);
      border-radius: 50%;
    }
  `,
  commandPreview: css`
    overflow: hidden;
    font-family: SFMono-Regular, Consolas, 'Liberation Mono', monospace;
    font-size: 11px;
    line-height: 16px;
    text-overflow: ellipsis;
    white-space: nowrap;
  `,
  themeName: css`
    min-height: 20px;
    font-size: 12px;
    line-height: 20px;
    text-align: center;
  `,
}));
