import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  baseSettingBox: css`
    width: 100%;
    padding-top: 24px;
    display: flex;
    flex-direction: column;
    gap: 30px;
  `,
  customFontBox: css`
    display: flex;
    flex-wrap: wrap;
    gap: 20px;
  `,
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
  settingStack: css`
    width: 100%;
    max-width: 640px;
    display: flex;
    flex-direction: column;
    gap: 24px;
  `,
  controlBlock: css`
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 10px;
  `,
  backgroundList: css`
    width: 100%;
    max-width: 640px;
    display: grid;
    grid-template-columns: repeat(4, minmax(96px, 1fr));
    gap: 14px;

    @container (max-width: 480px) {
      grid-template-columns: repeat(2, minmax(96px, 1fr));
    }
  `,
  themeItemBox: css`
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
    }

    &:focus-visible {
      border-radius: 6px;
      outline: 2px solid ${token.colorPrimary};
      outline-offset: 3px;
    }
  `,
  activeThemeItemBox: css`
    color: ${token.colorText};
    font-weight: 500;
  `,
  themeBox: css`
    position: relative;
    box-sizing: border-box;
    width: 100%;
    aspect-ratio: 8 / 5;
    overflow: hidden;
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: 6px;
    display: block;
    background-position: center;
    background-repeat: no-repeat;
    background-size: cover;
  `,
  activeThemeBox: css`
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
  themeName: css`
    min-height: 20px;
    font-size: 12px;
    line-height: 20px;
    text-align: center;
  `,
  swatchesBox: css`
    min-height: 32px;
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 12px;
  `,
  colorSwatch: css`
    box-sizing: border-box;
    width: 28px;
    height: 28px;
    padding: 0;
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: 50%;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    color: ${token.colorTextLightSolid};
    cursor: pointer;

    &:focus-visible {
      outline: 2px solid ${token.colorPrimary};
      outline-offset: 3px;
    }
  `,
  activeColorSwatch: css`
    box-shadow: 0 0 0 2px ${token.colorBgBase}, 0 0 0 4px ${token.colorPrimary};
  `,
  languageSelect: css`
    width: min(220px, 100%);
  `,
  fontControls: css`
    width: 100%;
    max-width: 640px;
    display: grid;
    grid-template-columns: minmax(180px, 1fr) 160px;
    gap: 16px;

    @container (max-width: 480px) {
      grid-template-columns: minmax(0, 1fr);
    }
  `,
  fieldControl: css`
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 7px;

    .ant-select {
      width: 100%;
    }
  `,
  fieldLabel: css`
    color: ${token.colorTextSecondary};
    font-size: 12px;
    line-height: 18px;
  `,
}));
