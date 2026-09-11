import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  rail: css`
    position: absolute;
    z-index: 6;
    top: 50%;
    left: max(8px, calc(50% - 448px));
    transform: translateY(-50%);
    width: 36px;
    padding: 6px 0;
    opacity: 0.72;
    transition: opacity 0.15s ease;

    &:hover,
    &:focus-within {
      opacity: 1;
    }
  `,
  list: css`
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 2px;
    width: 36px;
    max-height: min(240px, 60vh);
    overflow-y: auto;
    overflow-x: hidden;
  `,
  item: css`
    display: flex;
    align-items: center;
    width: 34px;
    height: 12px;
    padding: 0;
    border: 0;
    background: transparent;
    cursor: pointer;

    > span {
      display: block;
      width: var(--message-navigation-rail-width, 8px);
      height: 2px;
      border-radius: 1px;
      background: ${token.colorTextQuaternary};
      opacity: 0.55;
      transition: background-color 0.1s ease, opacity 0.1s ease;
    }

    &:hover > span,
    &:focus-visible > span {
      width: 22px;
      background: ${token.colorTextSecondary};
      opacity: 1;
    }

  `,
  itemActive: css`
    > span,
    &:hover > span,
    &:focus-visible > span {
      width: 30px;
      height: 3px;
      background: ${token.colorText};
      opacity: 1;
    }
  `,
  preview: css`
    position: absolute;
    left: 44px;
    transform: translateY(-50%);
    z-index: 1;
    width: min(320px, calc(100vw - 96px));
    height: 108px;
    min-width: 0;
    padding: 10px 12px;
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: 8px;
    background: ${token.colorBgElevated};
    box-shadow: ${token.boxShadowSecondary};
    pointer-events: none;
    box-sizing: border-box;
  `,
  previewQuestion: css`
    display: -webkit-box;
    overflow: hidden;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 1;
    color: ${token.colorText};
    font-size: 13px;
    font-weight: 600;
    line-height: 20px;
  `,
  previewAnswer: css`
    display: -webkit-box;
    overflow: hidden;
    margin-top: 6px;
    padding-top: 6px;
    border-top: 1px solid ${token.colorBorderSecondary};
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 3;
    color: ${token.colorTextSecondary};
    font-size: 12px;
    line-height: 18px;
  `,
}));
