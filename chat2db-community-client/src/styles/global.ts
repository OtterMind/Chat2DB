import { memo, useEffect } from 'react';
import { createGlobalStyle, css, createStyles } from 'antd-style';
import iconEdit from '../../node_modules/@chat2db/ui/es/ThemeProvider/fonts/icon-editor.woff2';
import { useStylesStore } from '@/store/styles';

export const useStyles = createStyles(() => {
  return {};
});

const GlobalStyle = createGlobalStyle(({ theme: token }) => {
  const { theme } = useStyles();

  const setTheme = useStylesStore((s) => s.setTheme);

  useEffect(() => {
    setTheme(theme);
  }, [theme]);

  const scrollbarStyle = css`
    * {
      scrollbar-color: ${token.colorFill} transparent;
      ::-webkit-scrollbar {
        width: 6px;
        height: 6px;
      }

      ::-webkit-scrollbar-thumb {
        background-color: transparent;
        border-radius: 999px;
      }

      ::-webkit-scrollbar-corner {
        display: none;
        width: 0;
        height: 0;
      }

      &:hover {
        ::-webkit-scrollbar-thumb {
          background-color: ${token.colorFill};
        }
      }
    }
    .bashful-scroller {
      &::-webkit-scrollbar {
        width: 6px;
        height: 6px;
      }

      &::-webkit-scrollbar-thumb {
        border-radius: 10px;
        background-color: transparent;
        background-clip: padding-box;
      }

      &:hover::-webkit-scrollbar-thumb {
        background-color: ${token.colorFillSecondary};
      }

      &::-webkit-scrollbar-thumb:hover {
        background-color: ${token.colorFill};
      }

      &::-webkit-scrollbar-corner {
        display: none;
      }
    }
  `;

  const resizerStyle = css`
    /* The style of the dragged handle */
    .Resizer {
      position: relative;
      z-index: 100;
      flex-shrink: 0;
      background: ${token.colorBorderLayout};
      -moz-box-sizing: border-box;
      -webkit-box-sizing: border-box;
      box-sizing: border-box;
      -moz-background-clip: padding;
      -webkit-background-clip: padding;
      background-clip: padding-box;
    }

    .ResizerHidden .Resizer {
      display: none;
    }

    /* When the dragged line is on the right, the style when it is collapsed */
    .ResizerSizeIsZeroRight .Resizer.vertical {
      margin: 0 -5px 0px 0px;
      border-left: 0px solid transparent;
      border-right: 5px solid transparent;
    }

    /* When the dragged line is on the upper side, the style when it is collapsed  */
    .ResizerSizeIsZeroTop .Resizer.horizontal {
      margin: -5px 0px 0px 0px;
      border-top: 5px solid transparent;
      border-bottom: 0px solid transparent;
    }

    /* Horizontal drag bar */
    .Resizer.horizontal {
      height: 5px;
      margin: -2px 0;
      border-top: 2px solid transparent;
      border-bottom: 2px solid transparent;
      cursor: ns-resize !important;
      width: 100%;
    }

    /* When the horizontal drag bar is dragged */
    .Resizer.horizontal:hover,
    .Resizer.horizontal:active {
      border-top: 2px solid ${token.colorBorder};
      border-bottom: 2px solid ${token.colorBorder};
      background: ${token.colorBorder};
      position: relative;
      z-index: 30;
    }

    /* Vertical drag bar */
    .Resizer.vertical {
      width: 5px;
      margin: 0 -2px;
      border-left: 2px solid transparent;
      border-right: 2px solid transparent;
      cursor: ew-resize !important;
    }

    /* When dragging the vertical drag bar */
    .Resizer.vertical:hover,
    .Resizer.vertical:active {
      border-left: 2px solid ${token.colorBorder};
      border-right: 2px solid ${token.colorBorder};
      background: ${token.colorBorder};
      position: relative;
      z-index: 30;
    }

    .Resizer.disabled {
      cursor: default !important;
    }

    .Resizer.disabled:hover {
      border-color: transparent;
    }

    html.WorkspaceTabHorizontalResizing,
    html.WorkspaceTabHorizontalResizing * {
      cursor: ns-resize !important;
    }

    html.WorkspaceTabVerticalResizing,
    html.WorkspaceTabVerticalResizing * {
      cursor: ew-resize !important;
    }
  `;

  const canvasTable = css`
    .vtable__menu-element {
      padding: 4px !important;
      background-color: ${token.colorBgContainer} !important;
      border: 1px solid ${token.colorBorderSecondary} !important;
      box-shadow: none !important;
      color: ${token.colorTextBase} !important;
      border-radius: 6px;
      min-width: 150px !important;
    }
    .vtable__menu-element__item {
      height: 22px !important;
      padding: 5px 12px !important;
      color: ${token.colorText} !important;
      border-radius: 4px !important;
      &:hover {
        background-color: ${token.colorFillTertiary} !important;
      }
    }
    .vtable__menu-element__arrow {
      display: flex !important;
      svg {
        path {
          fill: ${token.colorTextBase} !important;
        }
      }
    }
  `;

  return css`
    @font-face {
      font-family: 'icon-editor';
      src: url(${iconEdit}) format('woff2');
    }
    ${scrollbarStyle},
    ${resizerStyle},
    ${canvasTable},
    a {
      color: ${token.colorPrimary};
      &:hover {
        color: ${token.colorPrimaryHover};
      }
    }
    .ant-select-dropdown {
      border: 1px solid ${token.colorBorderSecondary};
    }
    .ant-modal-confirm-paragraph {
      width: 100%;
    }
    .ant-modal-root .ant-modal-wrap {
      top: ${window._appTitleBarHeight || 0}px;
    }
    .ant-dropdown {
      z-index: 11000 !important;
    }
    .ant-dropdown-menu-submenu {
      z-index: 11001 !important;
    }
    .ant-dropdown .ant-dropdown-menu .ant-dropdown-menu-item-icon {
      font-size: 20px !important;
    }
    .ant-dropdown-menu-submenu .ant-dropdown-menu {
      height: auto;
      max-height: 50vh;
      overflow: auto;
    }
    .ant-dropdown-menu-submenu .ant-dropdown-menu .ant-dropdown-menu-item-icon {
      font-size: 20px !important;
    }
    .ant-tooltip {
      z-index: 11100 !important;
      max-width: 500px;
    }
    .ant-input-outlined:focus-within {
      box-shadow: none;
    }
    .ant-input-outlined:focus {
      box-shadow: none;
    }

    .ant-form-item {
      margin-bottom: 16px;
    }

    .ant-upload-wrapper .ant-upload-drag {
      background-color: transparent;
    }

    .ant-dropdown-menu-submenu-title {
      display: flex;
      align-items: center;
    }

    /* The popup layer border color needs to be unified to UI */
    .ant-modal-content {
      border: 1px solid ${token.colorBorderSecondary} !important;
    }

    /* Offset Ant Design overlays below the desktop title bar. */
    .ant-drawer {
      top: ${window._appTitleBarHeight || 0}px;
    }
    .ant-message {
      top: ${window._appTitleBarHeight ? window._appTitleBarHeight + 4 : 0}px !important;
    }
  `;
});

export default memo(GlobalStyle);
