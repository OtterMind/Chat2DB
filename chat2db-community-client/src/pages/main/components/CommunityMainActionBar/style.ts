import { createStyles } from 'antd-style';
import { COMMUNITY_MAIN_ACTION_BUTTON_SIZE } from '@/constants/mainLayout';

export const useStyles = createStyles(({ css, token }) => ({
  actionBar: css`
    display: flex;
    flex-direction: column;
    align-items: center;
    width: 44px;
    flex-shrink: 0;
    box-sizing: border-box;
    padding: 6px 0;
    border-right: 1px solid ${token.colorBorderLayout};
    background-color: ${token.colorBgBase};
    user-select: none;
  `,
  navigationActions: css`
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 2px;
  `,
  settingsAction: css`
    display: flex;
    align-items: center;
    justify-content: center;
    width: ${COMMUNITY_MAIN_ACTION_BUTTON_SIZE.boxSize}px;
    height: ${COMMUNITY_MAIN_ACTION_BUTTON_SIZE.boxSize}px;
    margin-top: auto;
    padding: 0;
    border: 0;
    border-radius: 6px;
    background: transparent;
    cursor: pointer;

    &:hover {
      background-color: ${token.colorFillTertiary};
    }
  `,
  settingsActionActive: css`
    background-color: ${token.colorPrimaryBg};

    &:hover {
      background-color: ${token.colorPrimaryBg};
    }
  `,
}));
