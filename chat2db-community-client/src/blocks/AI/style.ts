import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token, prefixCls }) => {
  return {
    main: css`
      width: 100%;
      height: 100%;
      flex: 1;
      min-width: 0;
      display: flex;
      flex-direction: column;
      background-color: ${token.colorBgLayout};
    `,

    topLoadingBar: css`
      position: absolute;
      bottom: 0;
      left: 0;
      height: 3px;
      border-radius: 0 3px 3px 0;
      background: linear-gradient(90deg, ${token.colorPrimary}, ${token.colorPrimaryHover});
      animation: topBarSlide 2s ease-in-out infinite;
      z-index: 10;

      @keyframes topBarSlide {
        0% {
          left: -50%;
          width: 50%;
        }
        70% {
          left: 100%;
          width: 50%;
        }
        100% {
          left: 100%;
          width: 0;
        }
      }
    `,

    panelHeader: css`
      flex-shrink: 0;
      position: relative;
      display: flex;
      align-items: center;
      justify-content: space-between;
      height: 36px;
      padding: 0 8px 0 16px;
      border-bottom: 1px solid ${token.colorBorderSecondary};
    `,

    panelHeaderTitle: css`
      font-size: 13px;
      font-weight: 500;
      color: ${token.colorText};
      overflow: hidden;
      white-space: nowrap;
      text-overflow: ellipsis;
      flex: 1;
      min-width: 0;
    `,

    panelHeaderBtn: css`
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 26px;
      height: 26px;
      font-size: 13px;
      color: ${token.colorTextSecondary};
      background: none;
      border: none;
      border-radius: 6px;
      cursor: pointer;
      transition: background-color 0.15s, color 0.15s;
      flex-shrink: 0;

      &:hover {
        background-color: ${token.colorFillTertiary};
        color: ${token.colorText};
      }
    `,

    panelHistoryItem: css`
      display: flex;
      align-items: center;
      gap: 8px;
      width: 240px;
      min-width: 0;
    `,

    panelHistoryTitle: css`
      flex: 1;
      min-width: 0;
      overflow: hidden;
      white-space: nowrap;
      text-overflow: ellipsis;
    `,

    panelHistoryDeleteBtn: css`
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 22px;
      height: 22px;
      border: none;
      border-radius: 5px;
      background: transparent;
      color: ${token.colorTextTertiary};
      cursor: pointer;
      flex-shrink: 0;
      opacity: 0;
      transition: opacity 0.15s, background-color 0.15s, color 0.15s;

      ${`.${prefixCls}-dropdown-menu-item-active &,
      .${prefixCls}-dropdown-menu-item-selected &,
      .${prefixCls}-dropdown-menu-item:hover &`} {
        opacity: 1;
      }

      &:hover {
        background-color: ${token.colorErrorBg};
        color: ${token.colorError};
      }
    `,

    header: css`
      flex-shrink: 0;
      position: relative;
      height: 36px;
      line-height: 36px;
      padding: 0 16px;
      font-size: 14px;
      font-weight: 500;
      width: 100%;
      box-sizing: border-box;
      text-align: center;
      overflow: hidden;
      white-space: nowrap;
      text-overflow: ellipsis;
    `,

    chatPanel: css`
      flex: 1;
      min-height: 0;
      display: flex;
      flex-direction: column;
      padding: 0 0 10px;
    `,

    chatPanelCenter: css`
      flex: 1;
      min-height: 0;
      display: flex;
      flex-direction: column;
      justify-content: center;
      padding: 0 0 14px;
    `,

    messageList: css`
      flex: 1;
      min-height: 0;
      overflow: auto;
      padding: 14px 0 10px;
    `,

    contentWidth: css`
      width: min(820px, 100%);
      max-width: 100%;
      min-width: 0;
      margin: 0 auto;
      padding: 0 12px;
      box-sizing: border-box;
    `,

    roundBlock: css`
      display: flex;
      flex-direction: column;
      justify-content: flex-start;
      width: 100%;
      min-width: 0;
    `,

    userRow: css`
      display: flex;
      width: 100%;
      min-width: 0;
      margin-bottom: 16px;
      justify-content: flex-end;
      animation: streamUserMessageIn 0.22s ease-out;

      @keyframes streamUserMessageIn {
        from {
          opacity: 0;
          transform: translateY(10px);
        }
        to {
          opacity: 1;
          transform: translateY(0);
        }
      }
    `,

    assistantRow: css`
      display: flex;
      width: 100%;
      min-width: 0;
      margin-bottom: 16px;
      justify-content: flex-start;
      align-items: flex-start;
      gap: 10px;
    `,

    assistantBadge: css`
      position: relative;
      width: 32px;
      height: 32px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
      margin-top: 1px;
    `,

    assistantBadgeSpacer: css`
      width: 32px;
      height: 32px;
      flex-shrink: 0;
      margin-top: 1px;
      visibility: hidden;
      pointer-events: none;
    `,

    assistantBadgeActive: css`
      > span {
        opacity: 1;
        animation: assistantBadgeSpin 0.95s linear infinite;
      }
    `,

    assistantBadgeLoading: css`
      width: 26px;
      height: 26px;

      > div {
        width: 22px;
        height: 22px;
      }

      > div > span {
        font-size: 12px;
      }

      > span {
        inset: -1px;
        border-width: 1.5px;
      }
    `,

    assistantBadgeRing: css`
      position: absolute;
      inset: -2px;
      border-radius: 999px;
      border: 2px solid ${token.colorPrimaryBorder};
      border-top-color: ${token.colorPrimary};
      border-right-color: ${token.colorPrimaryHover};
      opacity: 0;
      transition: opacity 0.2s ease;
      pointer-events: none;

      @keyframes assistantBadgeSpin {
        from {
          transform: rotate(0deg);
        }
        to {
          transform: rotate(360deg);
        }
      }
    `,

    // User bubble.
    userBubbleWrap: css`
      max-width: 80%;
      display: flex;
      flex-direction: column;
      align-items: flex-end;
      gap: 8px;
      min-width: 0;
    `,

    userAttachmentList: css`
      display: flex;
      flex-wrap: wrap;
      justify-content: flex-end;
      gap: 8px;
      max-width: 100%;
    `,

    userAttachmentItem: css`
      max-width: 100%;
      padding: 4px 10px;
      border-radius: 999px;
      background: ${token.colorPrimaryBg};
      color: ${token.colorPrimary};
      border: 1px solid ${token.colorPrimaryBorder};
      font-size: 12px;
      line-height: 18px;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    `,

    userBubble: css`
      max-width: 100%;
      white-space: pre-wrap;
      word-break: break-word;
      border-radius: 18px 18px 4px 18px;
      line-height: 22px;
      padding: 10px 16px;
      font-size: 14px;
      color: ${token.colorWhite};
      background: ${token.colorPrimary};
      box-shadow: 0 2px 8px ${token.colorPrimaryBg};
    `,

    // AI icon.
    aiIconWrap: css`
      width: 28px;
      height: 28px;
      border-radius: 999px;
      background: linear-gradient(135deg, ${token.colorPrimaryBg}, ${token.colorPrimaryBgHover});
      border: 1px solid ${token.colorPrimaryBorder};
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
      box-shadow: 0 6px 16px ${token.colorPrimaryBg};
    `,

    aiSpark: css`
      color: ${token.colorPrimary};
      font-size: 15px;
      font-weight: 700;
      line-height: 1;
    `,

    // AI response content.
    assistantContent: css`
      flex: 1;
      min-width: 0;
      max-width: 100%;
      font-size: 14px;
      line-height: 1.7;
      color: ${token.colorText};
      word-break: break-word;

      p {
        margin: 0 0 8px;
        &:last-child {
          margin-bottom: 0;
        }
      }

      h1,
      h2,
      h3,
      h4,
      h5,
      h6 {
        margin: 14px 0 6px;
        font-weight: 600;
        line-height: 1.4;
        &:first-child {
          margin-top: 0;
        }
      }

      h1 {
        font-size: 1.4em;
      }
      h2 {
        font-size: 1.2em;
      }
      h3 {
        font-size: 1.05em;
      }

      ul,
      ol {
        margin: 4px 0 8px;
        padding-left: 22px;
        &:last-child {
          margin-bottom: 0;
        }
      }

      li {
        margin-bottom: 3px;
      }

      blockquote {
        margin: 8px 0;
        padding: 6px 14px;
        border-left: 3px solid ${token.colorPrimary};
        background-color: ${token.colorFillQuaternary};
        border-radius: 0 6px 6px 0;
        color: ${token.colorTextSecondary};
      }

      table {
        border-collapse: collapse;
        width: 100%;
        margin: 10px 0;
        font-size: 13px;
      }

      th,
      td {
        border: 1px solid ${token.colorBorder};
        padding: 6px 12px;
        text-align: left;
      }

      th {
        background-color: ${token.colorFillTertiary};
        font-weight: 600;
      }

      tr:nth-child(even) td {
        background-color: ${token.colorFillQuaternary};
      }

      hr {
        border: none;
        border-top: 1px solid ${token.colorBorder};
        margin: 12px 0;
      }

      a {
        color: ${token.colorPrimary};
        text-decoration: none;
        &:hover {
          text-decoration: underline;
        }
      }

      strong {
        font-weight: 600;
      }
      em {
        font-style: italic;
      }
    `,

    // Code block.
    codeBlockWrap: css`
      margin: 10px 0;
      border-radius: 10px;
      overflow: hidden;
      border: 1px solid ${token.colorBorderSecondary};
      background-color: ${token.colorBgContainer};

      &:last-child {
        margin-bottom: 0;
      }
    `,

    leftAlignedSqlPreview: css`
      > div > div:first-child > div:nth-child(2) {
        text-align: left;
      }
    `,

    codeBlockHeader: css`
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 6px 14px;
      background-color: ${token.colorBgElevated};
      border-bottom: 1px solid ${token.colorBorderSecondary};
    `,

    codeBlockLang: css`
      font-size: 12px;
      font-family: 'JetBrains Mono', 'Fira Code', Consolas, monospace;
      color: ${token.colorTextTertiary};
      text-transform: lowercase;
      letter-spacing: 0.03em;
    `,

    codeBlockActions: css`
      display: flex;
      align-items: center;
      gap: 4px;
    `,

    codeBlockCopyBtn: css`
      display: inline-flex;
      align-items: center;
      gap: 5px;
      font-size: 12px;
      color: ${token.colorTextSecondary};
      background: none;
      border: none;
      cursor: pointer;
      padding: 3px 8px;
      border-radius: 5px;
      transition: background-color 0.15s, color 0.15s;

      &:hover {
        background-color: ${token.colorFillTertiary};
        color: ${token.colorText};
      }
    `,

    codeBlockPre: css`
      margin: 0;
      padding: 14px 16px;
      overflow-x: auto;
      background-color: ${token.colorBgContainer};

      code {
        font-family: 'JetBrains Mono', 'Fira Code', 'Cascadia Code', Consolas, 'Courier New', monospace;
        font-size: 13px;
        line-height: 1.7;
        color: ${token.colorText};
      }
    `,

    // Chart card.
    chartCard: css`
      margin: 10px 0;
      width: 100%;
      max-width: 720px;

      &:last-child {
        margin-bottom: 0;
      }
    `,

    chartCardInner: css`
      height: 340px;
      border-radius: 12px;
      overflow: hidden;
      border: 1px solid ${token.colorBorder};
      background-color: ${token.colorBgBase};
    `,

    // Clickable table name.
    clickableTable: css`
      display: inline-flex;
      align-items: center;
      gap: 4px;
      padding: 0px 4px;
      border-radius: 4px;
      background: ${token.colorPrimaryBg};
      border: 1px solid ${token.colorPrimaryBorder};
      color: ${token.colorPrimary};
      cursor: pointer;
      transition: all 0.2s;
      line-height: 16px;
      box-sizing: border-box;
      transform: translateY(2px);
      &:hover {
        background: ${token.colorPrimaryBgHover};
      }
    `,

    // Inline code.
    inlineCode: css`
      font-family: 'JetBrains Mono', 'Fira Code', Consolas, 'Courier New', monospace;
      font-size: 0.875em;
      padding: 2px 6px;
      border-radius: 5px;
      background-color: ${token.colorPrimaryBg};
      border: 1px solid ${token.colorPrimaryBorder};
      color: ${token.colorText};
    `,

    // Chart generation loading state.
    chartLoadingWrap: css`
      display: flex;
      align-items: center;
      gap: 10px;
      margin: 10px 0;
      padding: 20px 24px;
      border-radius: 12px;
      border: 1px dashed ${token.colorBorder};
      background-color: ${token.colorFillQuaternary};
      max-width: 720px;
    `,

    chartLoadingIcon: css`
      display: flex;
      align-items: flex-end;
      gap: 3px;
      height: 18px;

      span {
        width: 4px;
        border-radius: 2px;
        background: ${token.colorPrimary};
        animation: chartBarPulse 1s ease-in-out infinite;
      }
      span:nth-child(1) {
        height: 8px;
        animation-delay: 0s;
      }
      span:nth-child(2) {
        height: 14px;
        animation-delay: 0.15s;
      }
      span:nth-child(3) {
        height: 10px;
        animation-delay: 0.3s;
      }

      @keyframes chartBarPulse {
        0%, 100% { opacity: 0.4; transform: scaleY(1); }
        50% { opacity: 1; transform: scaleY(1.3); }
      }
    `,

    chartLoadingBar: css``,

    chartLoadingText: css`
      font-size: 13px;
      color: ${token.colorTextSecondary};
    `,

    loadingBubble: css`
      display: inline-flex;
      align-items: center;
      gap: 8px;
      padding-top: 4px;
    `,

    loadingText: css`
      color: ${token.colorTextSecondary};
    `,

    loadingDots: css`
      display: inline-flex;
      gap: 5px;

      i {
        width: 6px;
        height: 6px;
        border-radius: 999px;
        background: ${token.colorTextSecondary};
        opacity: 0.4;
        animation: streamDotPulse 1.2s infinite ease-in-out;
      }

      i:nth-child(2) {
        animation-delay: 0.15s;
      }

      i:nth-child(3) {
        animation-delay: 0.3s;
      }

      @keyframes streamDotPulse {
        0%,
        80%,
        100% {
          transform: translateY(0);
          opacity: 0.35;
        }
        40% {
          transform: translateY(-2px);
          opacity: 0.95;
        }
      }
    `,

    thoughtWrap: css`
      margin: 0 0 12px;
      display: flex;
      align-items: flex-start;
      gap: 10px;
      width: 100%;
      min-width: 0;
      color: ${token.colorTextSecondary};
    `,

    thoughtWrapActive: css`
      margin-bottom: 14px;
    `,

    thoughtMain: css`
      min-width: 0;
      flex: 1;
      width: 100%;
      overflow: hidden;
    `,

    thoughtToggle: css`
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 6px;
      padding: 0;
      width: 100%;
      min-width: 0;
      background: none;
      border: none;
      color: ${token.colorTextSecondary};
      cursor: pointer;
      font-size: 13px;
      font-weight: 600;
      line-height: 20px;
      text-align: left;
      overflow: hidden;

      &:hover {
        color: ${token.colorText};
      }
    `,

    thoughtLabel: css`
      display: block;
      color: inherit;
      min-width: 0;
      flex: 1;
      text-align: left;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      max-width: 100%;
    `,

    thoughtPulse: css`
      background-image: linear-gradient(
        90deg,
        color-mix(in srgb, ${token.colorText} 55%, transparent) 0%,
        color-mix(in srgb, ${token.colorText} 55%, transparent) 25%,
        ${token.colorText} 100%,
        color-mix(in srgb, ${token.colorText} 55%, transparent) 75%,
        color-mix(in srgb, ${token.colorText} 55%, transparent) 100%
      );
      background-size: 200% 100%;
      background-clip: text;
      -webkit-background-clip: text;
      -webkit-text-fill-color: transparent;
      animation: thoughtPulseShine 2.4s ease-out 1;

      @keyframes thoughtPulseShine {
        0% {
          background-position: 200% 0;
        }
        100% {
          background-position: -200% 0;
        }
      }
    `,

    thoughtArrow: css`
      font-size: 11px;
      color: ${token.colorTextTertiary};
      transition: transform 0.18s ease;
      transform: rotate(180deg);
      transform-origin: center;
    `,

    thoughtArrowExpanded: css`
      transform: rotate(0deg);
    `,

    thoughtPreview: css`
      margin: 8px 0 0;
      font-size: 13px;
      line-height: 1.7;
      color: ${token.colorTextSecondary};
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      max-width: 100%;
    `,

    thoughtExpanded: css`
      margin: 10px 0 0;
      padding: 0 0 0 18px;
      border-left: 1px solid ${token.colorBorderSecondary};
      display: flex;
      flex-direction: column;
      gap: 16px;
    `,

    thoughtPreviewStatic: css`
      font-size: 13px;
      line-height: 1.7;
      color: ${token.colorTextSecondary};
      white-space: pre-wrap;
      word-break: break-word;
      margin-top: -2px;
    `,

    traceEntry: css`
      display: flex;
      flex-direction: column;
      gap: 6px;
      min-width: 0;
    `,

    traceEntryTag: css`
      font-size: 11px;
      font-weight: 600;
      color: ${token.colorTextTertiary};
      letter-spacing: 0.02em;
    `,

    traceEntryTitle: css`
      font-size: 15px;
      line-height: 1.55;
      color: ${token.colorText};
      font-style: italic;
      font-weight: 600;
      white-space: pre-wrap;
      word-break: break-word;
    `,

    traceEntryText: css`
      font-size: 14px;
      line-height: 1.8;
      color: ${token.colorTextSecondary};
      white-space: pre-wrap;
      word-break: break-word;
    `,

    traceCodeBlock: css`
      margin: 2px 0 0;
      padding: 10px 12px;
      border-radius: 10px;
      background: ${token.colorFillTertiary};
      border: 1px solid ${token.colorBorderSecondary};
      color: ${token.colorTextSecondary};
      font-size: 12px;
      line-height: 1.65;
      white-space: pre-wrap;
      word-break: break-word;
      overflow-x: auto;
      font-family: 'JetBrains Mono', 'Fira Code', 'Cascadia Code', Consolas, 'Courier New', monospace;
    `,

    toolRow: css`
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin-bottom: 12px;
    `,

    toolItem: css`
      font-size: 12px;
      color: ${token.colorTextSecondary};
      background-color: ${token.colorFillTertiary};
      border: 1px solid ${token.colorBorder};
      border-radius: 12px;
      padding: 4px 10px;
    `,

    inputWrap: css`
      flex-shrink: 0;
      display: flex;
      flex-direction: column;
      align-items: center;
      padding-top: 12px;
    `,

    inputWrapBottom: css`
      flex-shrink: 0;
      display: flex;
      flex-direction: column;
      align-items: center;
      padding-top: 12px;
      margin-top: auto;
    `,

    panelWelcome: css`
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      flex: 1;
      gap: 12px;
    `,

    panelWelcomeSpark: css`
      color: ${token.colorPrimary};
      font-size: 28px;
    `,

    panelWelcomeText: css`
      font-size: 16px;
      font-weight: 500;
      color: ${token.colorTextTertiary};
    `,

    inputWrapCenter: css`
      flex-shrink: 0;
      display: flex;
      flex-direction: column;
      align-items: center;
      margin: auto 0;
    `,

    welcome: css`
      text-align: left;
      margin-bottom: 14px;
    `,

    welcomeGreeting: css`
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 22px;
      font-weight: 500;
      color: ${token.colorText};
      margin-bottom: 8px;
    `,

    welcomeSpark: css`
      color: ${token.colorPrimary};
      font-size: 24px;
    `,

    welcomeTitle: css`
      font-size: 48px;
      font-weight: 600;
      color: ${token.colorText};
      line-height: 1.08;
      letter-spacing: -0.02em;
      margin-bottom: 10px;
    `,

    welcomeDesc: css`
      font-size: 14px;
      color: ${token.colorTextSecondary};
    `,

    chatInput: css`
      width: 100%;
    `,

    chatInputAreaRounded: css`
      border-radius: 24px !important;
      background: ${token.colorBgElevated} !important;
      border: 1px solid ${token.colorBorder} !important;
      padding: 12px 16px 8px !important;
      box-shadow: 0 2px 8px 0 rgba(0, 0, 0, 0.06), 0 1px 2px 0 rgba(0, 0, 0, 0.04) !important;
    `,

    chatInputAreaLoading: css`
      /* animation: streamInputPulse 1.8s ease-in-out infinite;

      @keyframes streamInputPulse {
        0%,
        100% {
          box-shadow: 0 18px 40px ${token.colorBgMask}, 0 0 0 0 ${token.colorPrimaryBg};
        }
        50% {
          box-shadow: 0 18px 40px ${token.colorBgMask}, 0 0 0 5px ${token.colorPrimaryBg};
        }
      } */
    `,

    historyTag: css`
      cursor: default;
      font-size: 12px;
    `,

    settingsDesc: css`
      font-size: 13px;
      line-height: 22px;
      color: ${token.colorTextSecondary};
      margin-bottom: 12px;
    `,

    settingsStats: css`
      font-size: 13px;
      color: ${token.colorText};
      margin-bottom: 16px;
    `,

    collapsibleShimmer: css`
      background-image: linear-gradient(
        90deg,
        color-mix(in srgb, ${token.colorText} 60%, transparent) 0%,
        color-mix(in srgb, ${token.colorText} 60%, transparent) 25%,
        ${token.colorText} 60%,
        color-mix(in srgb, ${token.colorText} 60%, transparent) 75%,
        color-mix(in srgb, ${token.colorText} 60%, transparent) 100%
      );
      background-size: 200% 100%;
      background-clip: text;
      -webkit-background-clip: text;
      -webkit-text-fill-color: transparent;
      animation: collapsibleShine 2s linear infinite;

      @keyframes collapsibleShine {
        0% {
          background-position: 200% 0;
        }
        100% {
          background-position: -200% 0;
        }
      }
    `,
  };
});
