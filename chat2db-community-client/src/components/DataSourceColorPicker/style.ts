import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  palette: css`
    --data-source-swatch-gap: 4px;
    --data-source-swatch-size: 22px;

    width: calc(var(--data-source-swatch-size) * 10 + var(--data-source-swatch-gap) * 9);
    padding: 2px 0 3px;
    letter-spacing: 0;
  `,
  responsivePalette: css`
    @container connection-identity (max-width: 900px) {
      --data-source-swatch-gap: 2px;
      --data-source-swatch-size: 18px;
    }
  `,
  label: css`
    margin-bottom: 7px;
    color: ${token.colorTextSecondary};
    font-size: 12px;
    line-height: 16px;
  `,
  swatches: css`
    display: flex;
    align-items: center;
    gap: var(--data-source-swatch-gap);
  `,
  swatch: css`
    position: relative;
    display: inline-flex;
    flex: 0 0 var(--data-source-swatch-size);
    align-items: center;
    justify-content: center;
    width: var(--data-source-swatch-size);
    height: var(--data-source-swatch-size);
    padding: 0;
    overflow: visible;
    border: 1px solid ${token.colorBorder};
    border-radius: 4px;
    cursor: pointer;

    &:hover {
      border-color: ${token.colorPrimaryBorderHover};
      transform: translateY(-1px);
    }

    &:focus-visible {
      outline: 2px solid ${token.colorPrimary};
      outline-offset: 2px;
    }

    &:disabled {
      cursor: not-allowed;
      opacity: 0.45;
      transform: none;
    }
  `,
  utilitySwatch: css`
    background: ${token.colorBgContainer};
    color: ${token.colorTextSecondary};
  `,
  selected: css`
    border-color: ${token.colorPrimary};
    box-shadow: 0 0 0 2px ${token.colorPrimaryBg};
  `,
  selectionMark: css`
    position: absolute;
    top: -5px;
    right: -5px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 13px;
    height: 13px;
    border: 1px solid ${token.colorBgElevated};
    border-radius: 50%;
    background: ${token.colorPrimary};
    color: ${token.colorTextLightSolid};
    pointer-events: none;
  `,
}));
