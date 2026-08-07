import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  trigger: css`
    height: 100%;
    min-width: 29px;
    display: inline-flex;
    align-items: center;
    flex-shrink: 0;
    margin: 0;
    padding: 0;
    border: 0;
    background: transparent;
    color: ${token.colorTextSecondary};
    font: inherit;
    font-size: 13px;
    line-height: 1;
    white-space: nowrap;
    cursor: pointer;

    &:hover {
      color: ${token.colorText};
    }

    &:focus-visible {
      outline: 2px solid ${token.colorPrimaryBorder};
      outline-offset: -2px;
    }
  `,
  label: css`
    display: inline-flex;
    align-items: center;
    height: 100%;
    padding-left: 8px;
  `,
  chevronSlot: css`
    width: 29px;
    height: 100%;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    flex: 0 0 29px;

    svg {
      display: block;
    }
  `,
}));
