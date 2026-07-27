import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  settingBox: css`
    position: absolute;
    inset: 0;
    overflow: hidden;
    background-color: ${token.colorBgBase};
    color: ${token.colorText};
  `,
  header: css`
    box-sizing: border-box;
    height: 56px;
    padding: 0 18px 0 22px;
    border-bottom: 1px solid ${token.colorSplit};
    display: flex;
    align-items: center;
    justify-content: space-between;
    background-color: ${token.colorBgContainer};
  `,
  headerTitle: css`
    font-size: 18px;
    font-weight: 600;
    line-height: 26px;
  `,
  headerClose: css`
    width: 32px;
    height: 32px;
    padding: 0;
    border: 0;
    border-radius: 6px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    background: transparent;
    color: ${token.colorTextSecondary};
    cursor: pointer;

    &:hover {
      background-color: ${token.colorFillSecondary};
      color: ${token.colorText};
    }

    &:focus-visible {
      outline: 2px solid ${token.colorPrimary};
      outline-offset: 2px;
    }
  `,
  content: css`
    height: calc(100% - 56px);
    min-height: 0;
    display: grid;
    grid-template-columns: 236px minmax(0, 1fr);

    @media (max-width: 760px) {
      grid-template-columns: minmax(0, 1fr);
      grid-template-rows: auto minmax(0, 1fr);
    }
  `,
  left: css`
    min-height: 0;
    border-right: 1px solid ${token.colorSplit};
    display: flex;
    flex-direction: column;
    overflow: hidden;
    background-color: ${token.colorBgContainer};

    @media (max-width: 760px) {
      border-right: 0;
      border-bottom: 1px solid ${token.colorSplit};
    }
  `,
  searchWrap: css`
    box-sizing: border-box;
    height: 42px;
    padding: 0 10px;
    border-bottom: 1px solid ${token.colorSplit};
    display: flex;
    align-items: center;
    flex-shrink: 0;
  `,
  searchBar: css`
    width: 100%;
    max-width: 100%;
    height: 25px;
    background-color: ${token.colorFillTertiary};
  `,
  navContent: css`
    min-height: 0;
    padding: 18px 12px;
    display: flex;
    flex-direction: column;
    gap: 20px;
    overflow-y: auto;

    @media (max-width: 760px) {
      padding: 8px 12px;
      flex-direction: row;
      gap: 4px;
      overflow-x: auto;
      overflow-y: hidden;
    }
  `,
  navContentSearch: css`
    gap: 3px;

    @media (max-width: 760px) {
      max-height: min(240px, 35vh);
      flex-direction: column;
      overflow-x: hidden;
      overflow-y: auto;
    }
  `,
  navGroup: css`
    display: flex;
    flex-direction: column;
    gap: 4px;

    @media (max-width: 760px) {
      display: contents;
    }
  `,
  navGroupLabel: css`
    padding: 0 10px;
    color: ${token.colorTextSecondary};
    font-size: 12px;
    font-weight: 500;
    line-height: 22px;

    @media (max-width: 760px) {
      display: none;
    }
  `,
  navGroupItems: css`
    display: flex;
    flex-direction: column;
    gap: 3px;

    @media (max-width: 760px) {
      display: contents;
    }
  `,
  navItem: css`
    box-sizing: border-box;
    width: 100%;
    min-height: 38px;
    padding: 8px 10px;
    border: 0;
    border-radius: 6px;
    display: flex;
    align-items: center;
    gap: 10px;
    background: transparent;
    color: ${token.colorTextSecondary};
    font: inherit;
    line-height: 22px;
    text-align: left;
    cursor: pointer;

    &:hover {
      background-color: ${token.colorFillSecondary};
      color: ${token.colorText};
    }

    &:focus-visible {
      outline: 2px solid ${token.colorPrimary};
      outline-offset: 1px;
    }

    @media (max-width: 760px) {
      width: auto;
      min-width: max-content;
      flex: 0 0 auto;
    }
  `,
  navItemActive: css`
    background-color: ${token.colorPrimaryBg};
    color: ${token.colorPrimary};
    font-weight: 500;

    &:hover {
      background-color: ${token.colorPrimaryBg};
      color: ${token.colorPrimary};
    }
  `,
  navItemIcon: css`
    flex: 0 0 auto;
  `,
  searchResult: css`
    box-sizing: border-box;
    width: 100%;
    min-height: 48px;
    padding: 7px 10px;
    border: 0;
    border-radius: 6px;
    display: flex;
    align-items: center;
    gap: 10px;
    background: transparent;
    color: ${token.colorTextSecondary};
    font: inherit;
    text-align: left;
    cursor: pointer;

    &:hover {
      background-color: ${token.colorFillSecondary};
      color: ${token.colorText};
    }

    &:focus-visible {
      outline: 2px solid ${token.colorPrimary};
      outline-offset: 1px;
    }
  `,
  searchResultActive: css`
    background-color: ${token.colorPrimaryBg};
    color: ${token.colorPrimary};

    [data-setting-search-result-title='true'] {
      color: ${token.colorPrimary};
      font-weight: 500;
    }

    &:hover {
      background-color: ${token.colorPrimaryBg};
      color: ${token.colorPrimary};
    }
  `,
  searchResultText: css`
    min-width: 0;
    display: flex;
    flex: 1;
    flex-direction: column;
    gap: 1px;
  `,
  searchResultTitle: css`
    overflow: hidden;
    color: ${token.colorText};
    font-size: 13px;
    line-height: 20px;
    text-overflow: ellipsis;
    white-space: nowrap;
  `,
  searchResultPage: css`
    overflow: hidden;
    color: ${token.colorTextTertiary};
    font-size: 12px;
    line-height: 18px;
    text-overflow: ellipsis;
    white-space: nowrap;
  `,
  searchEmpty: css`
    padding: 28px 10px;
    color: ${token.colorTextTertiary};
    font-size: 13px;
    line-height: 20px;
    text-align: center;
  `,
  menuContent: css`
    min-width: 0;
    min-height: 0;
    overflow-y: auto;
    background-color: ${token.colorBgBase};
  `,
  menuContentInner: css`
    box-sizing: border-box;
    width: 100%;
    min-height: 100%;
    margin: 0;
    padding: 30px 40px 48px;

    @media (max-width: 760px) {
      padding: 22px 18px 36px;
    }
  `,
  pageHeader: css`
    padding-bottom: 22px;
    border-bottom: 1px solid ${token.colorSplit};

    @media (max-width: 760px) {
      padding-bottom: 18px;
    }
  `,
  pageTitle: css`
    margin: 0;
    font-size: 20px;
    font-weight: 600;
    line-height: 28px;
  `,
  pageDescription: css`
    max-width: 720px;
    margin: 6px 0 0;
    color: ${token.colorTextSecondary};
    font-size: 13px;
    line-height: 20px;
  `,
  pageBody: css`
    min-width: 0;
    container-type: inline-size;

    [data-setting-search-highlighted='true'] {
      color: ${token.colorPrimary} !important;

      [data-setting-search-title='true'] {
        color: ${token.colorPrimary} !important;
      }
    }
  `,
}));
