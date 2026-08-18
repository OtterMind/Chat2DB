import { createStyles, keyframes } from 'antd-style';
import { createVar } from '@/styles/var';

export const useStyles = createStyles(({ css, token }) => {
  const vatStyles = createVar(token);
  const rotateGradient = keyframes`
    0% {
      background-position: 0% 50%;
    }
    50% {
      background-position: 100% 50%;
    }
    100% {
      background-position: 0% 50%;
    }
  `;

  return {
    draggableModal: css`
      position: absolute;
      .ant-modal-content {
        border: 0px !important;
      }
    `,
    container: css`
      height: 100%;
      display: flex;
      flex-direction: column;
    `,
    containerHeader: css`
      position: sticky;
      top: 0;
      box-sizing: border-box;
      height: 36px;
      flex-shrink: 0;
      display: flex;
      align-items: center;
      padding: 0px 16px;
      border-bottom: 1px solid ${token.colorBorderLayout};
      background: ${token.colorBgBase};
    `,
    headerLeft: css`
      flex: 1;
      width: 0px;
      display: flex;
      align-items: center;
      gap: 20px;
    `,
    headerTitle: css`
      display: flex;
      align-items: center;
      font-weight: 500;
      font-size: 16px;
      gap: 8px;
      max-width: 60%;
    `,
    headerTitleIcon: css`
      flex-shrink: 0;
    `,
    headerTitleText: css`
      flex: 1;
      height: 32px;
      line-height: 32px;
      width: fit-content;
      ${vatStyles.singleLine}
    `,
    dashboardMenu: css`
      width: 260px;
      height: 320px;
    `,
    dashboardMenuTrigger: css`
      display: inline-flex;
      flex-shrink: 0;
    `,
    headerRight: css`
      flex-shrink: 0;
      display: flex;
      align-items: center;
      gap: 8px;
    `,
    containerBody: css`
      flex: 1;
      height: 0px;
      position: relative;
      background-color: ${token.colorBgLayout};
    `,
    createDashboardButton: css`
      display: flex;
      align-items: center;
      gap: 4px;
      color: ${token.colorPrimary};
      cursor: pointer;
      padding: 2px 6px;
      border-radius: 4px;
      &:hover {
        background: ${token.colorPrimaryBgHover};
      }
    `,
    aiChart: css`
      display: flex;
      align-items: center;
      padding: 6px 18px;
      height: 100%;
      box-sizing: border-box;
      cursor: pointer;
      &:hover {
        filter: brightness(1.1);
      }
    `,
    aiChartBox: css`
      border: 1px solid transparent;
      border-radius: 4px;
      background-clip: padding-box, border-box;
      background-origin: padding-box, border-box;
      background-image: linear-gradient(to right, ${token.colorBgLayout}, ${token.colorBgLayout}),
        radial-gradient(101.76% 204.52% at 0% 0%, #ff4c33 0%, #fa8837 29.5%, #f218f4 72.5%, #ad00ff 100%);
      background-size: 200% 200%;
      animation: ${rotateGradient} 3s linear infinite;
    `,
    aiStar: css`
      width: 24px;
      height: 24px;
      cursor: pointer;
    `,
    aiChartText: css`
      background: linear-gradient(to right, #ff4c33 25%, #fa8837 50%, #f218f4 75%, #ad00ff 100%);
      background-clip: text;
      -webkit-text-fill-color: transparent;
    `,
    aiChatContainer: css`
      box-sizing: border-box;
      background-color: ${token.colorBgBase};
    `,
    draggableModalAcceptPlace: css``,
  };
});
