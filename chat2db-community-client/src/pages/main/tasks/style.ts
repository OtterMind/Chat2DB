import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  container: css`
    display: flex;
    flex-direction: column;
    width: 100%;
    height: 100%;
    min-width: 0;
    background: ${token.colorBgLayout};
  `,
  header: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    min-height: 56px;
    padding: 0 20px;
    border-bottom: 1px solid ${token.colorBorderSecondary};
    background: ${token.colorBgContainer};
  `,
  titleGroup: css`
    display: flex;
    align-items: center;
    gap: 12px;
    min-width: 0;
  `,
  titleIcon: css`
    display: grid;
    width: 32px;
    height: 32px;
    place-items: center;
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: ${token.borderRadiusLG}px;
    background: ${token.colorFillQuaternary};
    color: ${token.colorPrimary};
  `,
  titleCopy: css`
    min-width: 0;
    p {
      margin: 2px 0 0;
      color: ${token.colorTextTertiary};
      font-size: 12px;
    }
  `,
  title: css`
    margin: 0;
    color: ${token.colorText};
    font-size: 17px;
    font-weight: 600;
  `,
  count: css`
    color: ${token.colorTextTertiary};
    font-size: 12px;
  `,
  toolbar: css`
    display: flex;
    align-items: center;
    gap: 8px;
  `,
  content: css`
    flex: 1;
    min-height: 0;
    overflow: auto;
    padding: 14px 16px 18px;
  `,
  board: css`
    display: flex;
    gap: 10px;
    min-width: max-content;
    min-height: 100%;
  `,
  column: css`
    width: 280px;
    min-width: 280px;
    padding: 8px;
    border: 1px solid transparent;
    border-radius: ${token.borderRadiusLG}px;
    background: ${token.colorFillQuaternary};
  `,
  columnHeader: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    height: 34px;
    padding: 0 4px 8px;
    color: ${token.colorTextSecondary};
    font-size: 12px;
    font-weight: 600;
  `,
  columnHeading: css`
    display: flex;
    align-items: center;
    gap: 7px;
  `,
  columnStatusIcon: css`
    color: ${token.colorTextTertiary};
  `,
  columnCount: css`
    min-width: 20px;
    padding: 1px 6px;
    border-radius: 999px;
    background: ${token.colorFillSecondary};
    color: ${token.colorTextTertiary};
    font-size: 11px;
    text-align: center;
  `,
  taskList: css`
    display: flex;
    flex-direction: column;
    gap: 8px;
  `,
  taskCard: css`
    width: 100%;
    padding: 11px 10px 10px;
    border: 0.5px solid ${token.colorBorderSecondary};
    border-radius: ${token.borderRadiusLG}px;
    background: ${token.colorBgContainer};
    box-shadow: 0 1px 2px color-mix(in srgb, ${token.colorText} 7%, transparent);
    cursor: pointer;
    text-align: left;
    transition: border-color 0.15s, box-shadow 0.15s;
    &:hover,
    &:focus-visible {
      border-color: ${token.colorPrimaryBorder};
      background: ${token.colorBgElevated};
      box-shadow: 0 4px 12px color-mix(in srgb, ${token.colorPrimary} 10%, transparent);
      outline: none;
    }
  `,
  taskCardTopline: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    min-height: 22px;
    margin-bottom: 5px;
  `,
  taskIdentifier: css`
    color: ${token.colorTextQuaternary};
    font-family: ${token.fontFamilyCode};
    font-size: 10px;
  `,
  taskCardTitle: css`
    display: -webkit-box;
    margin-bottom: 4px;
    overflow: hidden;
    color: ${token.colorText};
    font-size: 13px;
    font-weight: 600;
    line-height: 1.45;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  `,
  taskCardDescription: css`
    display: -webkit-box;
    margin-bottom: 9px;
    overflow: hidden;
    color: ${token.colorTextTertiary};
    font-size: 11px;
    line-height: 1.45;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 1;
  `,
  taskMeta: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    color: ${token.colorTextTertiary};
    font-size: 11px;
  `,
  taskAgent: css`
    display: flex;
    min-width: 0;
    align-items: center;
    gap: 6px;
    span:last-child {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  `,
  taskCardFooter: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
  `,
  priorityMark: css`
    display: flex;
    align-items: center;
    gap: 4px;
    color: ${token.colorTextQuaternary};
    font-size: 10px;
  `,
  agentAvatar: css`
    position: relative;
    display: inline-grid;
    flex: none;
    place-items: center;
    overflow: hidden;
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: 50%;
    background: ${token.colorPrimaryBg};
    color: ${token.colorPrimary};
    font-weight: 650;
    img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }
    svg {
      width: 64%;
      height: 64%;
    }
  `,
  runtimeBadge: css`
    display: inline-flex;
    align-items: center;
    gap: 5px;
    width: fit-content;
    color: ${token.colorTextTertiary};
    font-size: 11px;
    white-space: nowrap;
  `,
  runtimeBadgeActive: css`
    color: ${token.colorPrimary};
  `,
  runtimeBadgeError: css`
    color: ${token.colorError};
  `,
  runtimePulse: css`
    width: 5px;
    height: 5px;
    border-radius: 50%;
    background: currentColor;
    animation: task-runtime-pulse 1.6s ease-in-out infinite;
    @keyframes task-runtime-pulse {
      50% {
        opacity: 0.25;
        transform: scale(0.75);
      }
    }
    @media (prefers-reduced-motion: reduce) {
      animation: none;
    }
  `,
  runStatus: css`
    display: inline-flex;
    align-items: center;
    gap: 5px;
    color: ${token.colorTextSecondary};
    font-size: 12px;
  `,
  runStatusRUNNING: css`
    color: ${token.colorPrimary};
  `,
  runStatusQUEUED: css`
    color: ${token.colorTextTertiary};
  `,
  runStatusDISPATCHED: css`
    color: ${token.colorInfo};
  `,
  runStatusWAITING_APPROVAL: css`
    color: ${token.colorWarning};
  `,
  runStatusCOMPLETED: css`
    color: ${token.colorSuccess};
  `,
  runStatusFAILED: css`
    color: ${token.colorError};
  `,
  runStatusUNKNOWN: css`
    color: ${token.colorError};
  `,
  runStatusCANCELLED: css`
    color: ${token.colorTextQuaternary};
  `,
  emptyColumn: css`
    display: flex;
    align-items: center;
    justify-content: center;
    min-height: 88px;
    color: ${token.colorTextQuaternary};
    font-size: 12px;
  `,
  detailHeader: css`
    display: flex;
    flex-direction: column;
    gap: 10px;
    padding-right: 24px;
  `,
  detailPageHeader: css`
    display: flex;
    min-height: 52px;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    padding: 0 18px;
    border-bottom: 1px solid ${token.colorBorderSecondary};
    background: ${token.colorBgContainer};
  `,
  detailPageActions: css`
    display: flex;
    align-items: center;
    gap: 12px;
  `,
  detailPageContent: css`
    flex: 1;
    min-height: 0;
    overflow: hidden;
  `,
  detailPageWorkspace: css`
    display: grid;
    height: 100%;
    grid-template-columns: minmax(0, 1fr) 292px;
    background: ${token.colorBgContainer};
    @media (max-width: 980px) {
      grid-template-columns: minmax(0, 1fr) 250px;
    }
    @media (max-width: 760px) {
      display: block;
      overflow: auto;
    }
  `,
  detailPageHero: css`
    padding: 4px 6px 24px;
    border-bottom: 1px solid ${token.colorBorderSecondary};
  `,
  detailPageTitle: css`
    max-width: 800px;
    margin: 5px 0 28px;
    color: ${token.colorText};
    font-size: clamp(24px, 3vw, 32px);
    font-weight: 640;
    line-height: 1.25;
    letter-spacing: -0.025em;
  `,
  detailPageTabs: css`
    .ant-tabs-nav {
      margin: 0 0 22px;
    }
    .ant-tabs-tab {
      padding: 14px 0 12px;
    }
  `,
  detailIdentity: css`
    display: flex;
    min-width: 0;
    align-items: flex-start;
    gap: 10px;
  `,
  detailBreadcrumb: css`
    margin-bottom: 3px;
    color: ${token.colorTextQuaternary};
    font-size: 11px;
  `,
  detailTitleRow: css`
    display: flex;
    align-items: center;
    gap: 8px;
  `,
  detailTitle: css`
    margin: 0;
    color: ${token.colorText};
    font-size: 18px;
    font-weight: 600;
    line-height: 1.4;
  `,
  detailMeta: css`
    display: flex;
    flex-wrap: wrap;
    gap: 8px 16px;
    color: ${token.colorTextSecondary};
    font-size: 12px;
  `,
  detailShell: css`
    display: grid;
    grid-template-columns: minmax(0, 1fr) 236px;
    gap: 24px;
    @media (max-width: 840px) {
      grid-template-columns: 1fr;
    }
  `,
  detailMain: css`
    min-width: 0;
  `,
  detailAside: css`
    min-width: 0;
    border-left: 1px solid ${token.colorBorderSecondary};
    padding-left: 18px;
    @media (max-width: 840px) {
      border-left: 0;
      border-top: 1px solid ${token.colorBorderSecondary};
      padding: 16px 0 0;
    }
  `,
  propertyBlock: css`
    margin-bottom: 20px;
    h4 {
      margin: 0 0 10px;
      color: ${token.colorTextTertiary};
      font-size: 11px;
      font-weight: 600;
    }
  `,
  propertyRow: css`
    display: flex;
    min-height: 30px;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    color: ${token.colorTextSecondary};
    font-size: 12px;
    > span:first-child {
      color: ${token.colorTextTertiary};
    }
  `,
  propertyAgent: css`
    display: flex;
    min-width: 0;
    align-items: center;
    gap: 6px;
  `,
  detailDescription: css`
    margin-bottom: 24px;
    h3 {
      margin: 0 0 8px;
      color: ${token.colorText};
      font-size: 13px;
    }
    p {
      margin: 0;
      color: ${token.colorTextSecondary};
      line-height: 1.65;
      white-space: pre-wrap;
    }
  `,
  detailBody: css`
    min-height: 240px;
  `,
  section: css`
    margin-bottom: 20px;
  `,
  sectionTitle: css`
    margin: 0 0 10px;
    color: ${token.colorText};
    font-size: 13px;
    font-weight: 600;
  `,
  prose: css`
    color: ${token.colorText};
    line-height: 1.65;
    overflow-wrap: anywhere;
    h1,
    h2,
    h3 {
      margin: 18px 0 8px;
      font-size: 16px;
    }
    p {
      margin: 0 0 10px;
    }
    ul,
    ol {
      margin: 0 0 12px;
      padding-left: 22px;
    }
    li { margin: 4px 0; }
    blockquote {
      margin: 12px 0;
      padding: 4px 12px;
      border-left: 3px solid ${token.colorPrimaryBorder};
      background: ${token.colorFillQuaternary};
      color: ${token.colorTextSecondary};
    }
    code {
      padding: 1px 4px;
      border-radius: ${token.borderRadiusSM}px;
      background: ${token.colorFillTertiary};
      font-family: ${token.fontFamilyCode};
      font-size: 0.9em;
    }
    pre {
      overflow: auto;
      padding: 12px;
      border-radius: ${token.borderRadius}px;
      background: ${token.colorFillTertiary};
      code { padding: 0; background: transparent; }
    }
    table {
      width: 100%;
      margin: 12px 0;
      border-collapse: collapse;
    }
    th,
    td {
      padding: 6px 8px;
      border: 1px solid ${token.colorBorderSecondary};
    }
  `,
  artifact: css`
    margin-bottom: 16px;
    padding: 14px;
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: ${token.borderRadiusLG}px;
    background: ${token.colorBgContainer};
  `,
  artifactHeader: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 12px;
  `,
  artifactTitle: css`
    display: flex;
    align-items: center;
    gap: 8px;
    min-width: 0;
    color: ${token.colorText};
    font-weight: 600;
  `,
  chart: css`
    height: 320px;
    margin-top: 10px;
  `,
  approval: css`
    margin-bottom: 16px;
    padding: 14px;
    border: 1px solid ${token.colorWarningBorder};
    border-radius: ${token.borderRadiusLG}px;
    background: ${token.colorWarningBg};
  `,
  approvalActions: css`
    display: flex;
    justify-content: flex-end;
    gap: 8px;
    margin-top: 12px;
  `,
  sql: css`
    max-height: 220px;
    overflow: auto;
    padding: 10px;
    border-radius: ${token.borderRadius}px;
    background: ${token.colorFillTertiary};
    color: ${token.colorText};
    font-family: ${token.fontFamilyCode};
    font-size: 12px;
    white-space: pre-wrap;
  `,
  scopeList: css`
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px;
  `,
  scope: css`
    padding: 10px;
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: ${token.borderRadius}px;
    background: ${token.colorFillQuaternary};
    color: ${token.colorTextSecondary};
    font-size: 12px;
  `,
  scopeEditor: css`
    padding: 14px;
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: ${token.borderRadiusLG}px;
    background: ${token.colorBgContainer};
  `,
  scopeEditorHeader: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 12px;
  `,
  scopeEditorIdentity: css`
    display: flex;
    align-items: center;
    gap: 9px;
    div {
      display: flex;
      flex-direction: column;
    }
    strong {
      color: ${token.colorText};
      font-size: 13px;
    }
    span {
      color: ${token.colorTextTertiary};
      font-size: 11px;
    }
  `,
  scopeIcon: css`
    display: grid !important;
    width: 30px;
    height: 30px;
    place-items: center;
    border-radius: ${token.borderRadius}px;
    background: ${token.colorPrimaryBg};
    color: ${token.colorPrimary} !important;
  `,
  scopePathGrid: css`
    display: grid;
    grid-template-columns: 1.2fr 1fr 1fr;
    gap: 10px;
    @media (max-width: 760px) {
      grid-template-columns: 1fr;
    }
  `,
  scopePolicyGrid: css`
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 10px;
    @media (max-width: 760px) {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  `,
  accessChoiceGroup: css`
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px;
    .ant-radio-button-wrapper {
      display: flex;
      height: auto;
      min-height: 58px;
      flex-direction: column;
      justify-content: center;
      border: 1px solid ${token.colorBorderSecondary} !important;
      border-radius: ${token.borderRadius}px !important;
      line-height: 1.35;
      &::before {
        display: none;
      }
      strong {
        color: ${token.colorText};
        font-size: 12px;
      }
      span {
        margin-top: 3px;
        color: ${token.colorTextTertiary};
        font-size: 11px;
        white-space: normal;
      }
      &.ant-radio-button-wrapper-checked {
        border-color: ${token.colorPrimaryBorder} !important;
        background: ${token.colorPrimaryBg};
      }
    }
  `,
  scopeStudio: css`
    display: flex;
    flex-direction: column;
    gap: 10px;
  `,
  activityContent: css`
    color: ${token.colorTextSecondary};
    font-size: 12px;
    white-space: pre-wrap;
  `,
  contextComposer: css`
    margin-bottom: 16px;
    padding: 12px;
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: ${token.borderRadiusLG}px;
    background: ${token.colorFillQuaternary};
  `,
  contextAttachmentFields: css`
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 12px;
  `,
  contextActions: css`
    display: flex;
    justify-content: flex-end;
  `,
  contextList: css`
    display: flex;
    flex-direction: column;
    gap: 8px;
  `,
  contextItem: css`
    padding: 12px;
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: ${token.borderRadius}px;
    background: ${token.colorBgContainer};
  `,
  contextHeader: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 8px;
    color: ${token.colorTextTertiary};
    font-size: 12px;
  `,
  contextContent: css`
    color: ${token.colorText};
    font-size: 13px;
    line-height: 1.6;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
  `,
  taskWorkbenchMain: css`
    display: flex;
    min-width: 0;
    min-height: 0;
    flex-direction: column;
    overflow: auto;
    background: ${token.colorBgContainer};
  `,
  taskDocument: css`
    width: min(780px, calc(100% - 64px));
    margin: 0 auto;
    padding: 42px 0 20px;
    @media (max-width: 760px) {
      width: calc(100% - 32px);
      padding-top: 26px;
    }
  `,
  taskBrief: css`
    margin-bottom: 22px;
    color: ${token.colorTextSecondary};
    font-size: 14px;
    line-height: 1.7;
    white-space: pre-wrap;
    h3 {
      display: flex;
      align-items: center;
      gap: 7px;
      margin: 0 0 7px;
      color: ${token.colorTextTertiary};
      font-size: 12px;
      font-weight: 600;
    }
  `,
  outputSection: css`
    margin-top: 34px;
    padding-top: 26px;
    border-top: 1px solid ${token.colorBorderSecondary};
  `,
  conversationSection: css`
    margin-top: 34px;
    padding-top: 26px;
    border-top: 1px solid ${token.colorBorderSecondary};
  `,
  sectionHeading: css`
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 18px;
    h2 {
      margin: 0;
      color: ${token.colorText};
      font-size: 14px;
      font-weight: 650;
    }
    span {
      color: ${token.colorTextQuaternary};
      font-size: 11px;
    }
  `,
  inlineEmpty: css`
    padding: 16px 0;
    color: ${token.colorTextQuaternary};
    font-size: 13px;
  `,
  conversationTimeline: css`
    display: flex;
    flex-direction: column;
  `,
  systemActivity: css`
    display: grid;
    grid-template-columns: 28px minmax(0, 1fr) auto;
    align-items: center;
    gap: 10px;
    min-height: 40px;
    color: ${token.colorTextTertiary};
    font-size: 12px;
    time {
      color: ${token.colorTextQuaternary};
      font-size: 11px;
    }
  `,
  activityDot: css`
    width: 7px;
    height: 7px;
    margin: auto;
    border: 2px solid ${token.colorBgContainer};
    border-radius: 50%;
    background: ${token.colorTextQuaternary};
    box-shadow: 0 0 0 1px ${token.colorBorderSecondary};
  `,
  conversationItem: css`
    position: relative;
    display: grid;
    grid-template-columns: 28px minmax(0, 1fr);
    gap: 10px;
    padding: 14px 0 18px;
    &::before {
      position: absolute;
      top: -5px;
      bottom: -5px;
      left: 13px;
      width: 1px;
      background: ${token.colorBorderSecondary};
      content: '';
      z-index: 0;
    }
    > * {
      position: relative;
      z-index: 1;
    }
  `,
  conversationContent: css`
    min-width: 0;
    padding: 1px 0 0;
  `,
  conversationHeader: css`
    display: flex;
    width: 100%;
    align-items: center;
    gap: 8px;
    margin-bottom: 7px;
    padding: 2px 4px 2px 0;
    border: 0;
    border-radius: ${token.borderRadiusSM}px;
    background: transparent;
    color: ${token.colorTextQuaternary};
    cursor: pointer;
    font-size: 11px;
    text-align: left;
    &:hover { color: ${token.colorTextSecondary}; }
    strong {
      color: ${token.colorText};
      font-size: 13px;
      font-weight: 600;
    }
    time {
      margin-left: auto;
    }
  `,
  chevronOpen: css`
    transform: rotate(90deg);
  `,
  toolActivityCard: css`
    margin: 8px 0 12px;
    overflow: hidden;
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: ${token.borderRadiusLG}px;
    background: ${token.colorFillQuaternary};
    summary {
      display: flex;
      min-height: 38px;
      align-items: center;
      gap: 7px;
      padding: 7px 10px;
      color: ${token.colorTextSecondary};
      cursor: pointer;
      font-size: 12px;
      list-style: none;
      &::-webkit-details-marker { display: none; }
      strong { flex: 1; color: ${token.colorText}; font-family: ${token.fontFamilyCode}; font-size: 11px; }
      > svg:last-child { color: ${token.colorTextQuaternary}; transition: transform 0.15s; }
    }
    &[open] summary > svg:last-child { transform: rotate(90deg); }
  `,
  toolActivityBlock: css`
    padding: 0 10px 10px;
    span { color: ${token.colorTextQuaternary}; font-size: 10px; }
    pre {
      max-height: 180px;
      margin: 5px 0 0;
      overflow: auto;
      padding: 8px;
      border-radius: ${token.borderRadiusSM}px;
      background: ${token.colorBgContainer};
      color: ${token.colorTextSecondary};
      font-family: ${token.fontFamilyCode};
      font-size: 11px;
      white-space: pre-wrap;
      overflow-wrap: anywhere;
    }
  `,
  conversationError: css`
    padding: 9px 11px;
    border: 1px solid ${token.colorErrorBorder};
    border-radius: ${token.borderRadius}px;
    background: ${token.colorErrorBg};
    color: ${token.colorErrorText};
    font-size: 13px;
  `,
  agentWorking: css`
    display: flex;
    align-items: center;
    gap: 8px;
    min-height: 28px;
    color: ${token.colorTextTertiary};
    font-size: 12px;
  `,
  workingPulse: css`
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: ${token.colorPrimary};
    animation: task-working-pulse 1.4s ease-in-out infinite;
    @keyframes task-working-pulse {
      50% { opacity: 0.25; transform: scale(0.7); }
    }
    @media (prefers-reduced-motion: reduce) { animation: none; }
  `,
  messageDock: css`
    position: sticky;
    bottom: 0;
    z-index: 5;
    width: min(780px, calc(100% - 64px));
    margin: auto auto 0;
    padding: 20px 0 18px;
    background: linear-gradient(to bottom, transparent, ${token.colorBgContainer} 22%);
    @media (max-width: 760px) { width: calc(100% - 32px); }
  `,
  messageComposer: css`
    padding: 10px;
    border: 1px solid ${token.colorBorder};
    border-radius: ${token.borderRadiusLG + 2}px;
    background: ${token.colorBgContainer};
    box-shadow: 0 8px 24px color-mix(in srgb, ${token.colorText} 10%, transparent);
    .ant-mentions {
      padding: 0;
      border: 0;
      box-shadow: none !important;
    }
    textarea {
      padding: 3px 4px 9px;
      resize: none;
    }
  `,
  messageDockFooter: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    padding-top: 7px;
    color: ${token.colorTextQuaternary};
    font-size: 10px;
    > div:first-child { min-width: 0; flex: 1; }
  `,
  mentionOption: css`
    display: grid;
    grid-template-columns: auto minmax(0, 1fr) auto;
    align-items: center;
    gap: 8px;
    min-width: 280px;
    > span { display: flex; min-width: 0; flex-direction: column; }
    strong { color: ${token.colorText}; font-size: 12px; }
    small { overflow: hidden; color: ${token.colorTextTertiary}; text-overflow: ellipsis; white-space: nowrap; }
  `,
  agentTriggerChip: css`
    display: inline-flex;
    align-items: center;
    gap: 5px;
    max-width: 220px;
    padding: 3px 5px 3px 3px;
    border: 1px solid ${token.colorPrimaryBorder};
    border-radius: 999px;
    background: ${token.colorPrimaryBg};
    color: ${token.colorPrimary};
    font-size: 11px;
    font-weight: 600;
    button {
      display: grid;
      width: 17px;
      height: 17px;
      padding: 0;
      place-items: center;
      border: 0;
      border-radius: 50%;
      background: transparent;
      color: inherit;
      cursor: pointer;
      &:hover { background: ${token.colorPrimaryBgHover}; }
    }
  `,
  mentionHint: css`
    color: ${token.colorTextQuaternary};
    font-size: 10px;
  `,
  taskInspector: css`
    min-width: 0;
    overflow: auto;
    padding: 22px 18px;
    border-left: 1px solid ${token.colorBorderSecondary};
    background: ${token.colorBgLayout};
    @media (max-width: 760px) {
      border-top: 1px solid ${token.colorBorderSecondary};
      border-left: 0;
    }
  `,
  inspectorSection: css`
    margin-bottom: 24px;
    h3 {
      margin: 0 0 9px;
      color: ${token.colorTextTertiary};
      font-size: 11px;
      font-weight: 650;
      letter-spacing: 0.02em;
    }
  `,
  scopeSectionTitle: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    margin-bottom: 9px;
    h3 { margin: 0; }
    .ant-tag {
      display: inline-flex;
      align-items: center;
      gap: 3px;
      margin: 0;
      font-size: 10px;
    }
  `,
  inspectorCollapseHeader: css`
    display: flex;
    width: 100%;
    align-items: center;
    gap: 5px;
    margin: -3px 0 6px;
    padding: 3px 2px;
    border: 0;
    border-radius: ${token.borderRadiusSM}px;
    background: transparent;
    color: ${token.colorTextTertiary};
    cursor: pointer;
    text-align: left;
    &:hover { background: ${token.colorFillQuaternary}; color: ${token.colorTextSecondary}; }
    h3 { margin: 0; }
  `,
  activeRunCount: css`
    margin-left: auto;
    color: ${token.colorPrimary};
    font-family: ${token.fontFamilyCode};
    font-size: 10px;
  `,
  pastRunsToggle: css`
    display: flex;
    width: 100%;
    align-items: center;
    gap: 5px;
    margin-top: 5px;
    padding: 5px 2px;
    border: 0;
    border-radius: ${token.borderRadiusSM}px;
    background: transparent;
    color: ${token.colorTextTertiary};
    cursor: pointer;
    font-size: 10px;
    text-align: left;
    &:hover { background: ${token.colorFillQuaternary}; color: ${token.colorTextSecondary}; }
  `,
  runRow: css`
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    align-items: center;
    gap: 4px 8px;
    padding: 9px 0;
    border-bottom: 1px solid ${token.colorBorderSecondary};
    color: ${token.colorTextSecondary};
    font-size: 11px;
    > div:first-child {
      display: flex;
      align-items: center;
      gap: 6px;
    }
    time { color: ${token.colorTextQuaternary}; }
  `,
  inspectorScope: css`
    display: flex;
    align-items: flex-start;
    gap: 7px;
    padding: 7px 0;
    color: ${token.colorPrimary};
    div { display: flex; min-width: 0; flex-direction: column; }
    strong { color: ${token.colorTextSecondary}; font-size: 11px; }
    span {
      overflow: hidden;
      color: ${token.colorTextQuaternary};
      font-size: 10px;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  `,
  mutedText: css`
    color: ${token.colorTextQuaternary};
    font-size: 11px;
  `,
  emptyScopeAction: css`
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  `,
  error: css`
    display: flex;
    align-items: center;
    justify-content: center;
    height: 100%;
    padding: 24px;
  `,
  managerModal: css`
    .ant-modal-content {
      padding: 0;
      overflow: hidden;
    }
    .ant-modal-close {
      top: 25px;
      right: 18px;
    }
  `,
  managerHeader: css`
    display: flex;
    min-height: 82px;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    padding: 14px 64px 14px 18px;
    border-bottom: 1px solid ${token.colorBorderSecondary};
    background: ${token.colorBgContainer};
    p {
      margin: 3px 0 0;
      color: ${token.colorTextTertiary};
      font-size: 12px;
    }
  `,
  managerTitle: css`
    display: flex;
    align-items: center;
    gap: 8px;
    color: ${token.colorPrimary};
    h2 {
      margin: 0;
      color: ${token.colorText};
      font-size: 16px;
    }
    span {
      color: ${token.colorTextQuaternary};
      font-size: 12px;
    }
  `,
  agentManagerGrid: css`
    display: grid;
    grid-template-columns: minmax(340px, 0.95fr) minmax(420px, 1.25fr);
    height: min(680px, 76vh);
    @media (max-width: 820px) {
      grid-template-columns: 1fr;
      height: auto;
    }
  `,
  agentListPane: css`
    min-width: 0;
    padding: 14px;
    overflow: auto;
    border-right: 1px solid ${token.colorBorderSecondary};
  `,
  agentRows: css`
    margin-top: 10px;
  `,
  agentRow: css`
    display: grid;
    width: 100%;
    grid-template-columns: auto minmax(0, 1fr) auto auto;
    align-items: center;
    gap: 10px;
    min-height: 64px;
    padding: 8px 10px;
    border: 0;
    border-bottom: 1px solid ${token.colorBorderSecondary};
    background: transparent;
    color: ${token.colorTextTertiary};
    cursor: pointer;
    text-align: left;
    &:hover,
    &:focus-visible {
      background: ${token.colorFillQuaternary};
      outline: none;
    }
  `,
  agentRowSelected: css`
    background: ${token.colorPrimaryBg};
  `,
  agentRowCopy: css`
    display: flex;
    min-width: 0;
    flex-direction: column;
    strong,
    span {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    strong {
      color: ${token.colorText};
      font-size: 13px;
    }
    span {
      margin-top: 3px;
      color: ${token.colorTextTertiary};
      font-size: 11px;
    }
  `,
  agentInspector: css`
    min-width: 0;
    overflow: auto;
    padding: 20px;
    background: ${token.colorBgLayout};
  `,
  agentInspectorHero: css`
    display: grid;
    grid-template-columns: auto minmax(0, 1fr) auto;
    align-items: center;
    gap: 12px;
    padding-bottom: 18px;
    border-bottom: 1px solid ${token.colorBorderSecondary};
    h3 {
      margin: 0;
      color: ${token.colorText};
      font-size: 17px;
    }
    p {
      margin: 4px 0 0;
      color: ${token.colorTextTertiary};
      font-size: 12px;
    }
  `,
  agentInspectorActions: css`
    display: flex;
    justify-content: flex-end;
    gap: 8px;
    padding: 12px 0 0;
  `,
  inspectorSection: css`
    padding: 16px 0;
    border-bottom: 1px solid ${token.colorBorderSecondary};
    h4 {
      display: flex;
      align-items: center;
      gap: 6px;
      margin: 0 0 10px;
      color: ${token.colorTextTertiary};
      font-size: 11px;
    }
  `,
  runtimeSummary: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    color: ${token.colorTextSecondary};
    font-size: 12px;
  `,
  chipRow: css`
    display: flex;
    flex-wrap: wrap;
    gap: 5px;
  `,
  inspectorScopes: css`
    display: flex;
    flex-direction: column;
    gap: 8px;
    > div {
      display: grid;
      grid-template-columns: minmax(100px, 0.7fr) minmax(120px, 1fr) auto;
      gap: 10px;
      align-items: center;
      padding: 9px 10px;
      border-radius: ${token.borderRadius}px;
      background: ${token.colorFillQuaternary};
      font-size: 11px;
    }
    strong {
      color: ${token.colorText};
    }
    span,
    small {
      overflow: hidden;
      color: ${token.colorTextTertiary};
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  `,
  agentStudio: css`
    max-height: min(760px, 80vh);
    overflow: auto;
    background: ${token.colorBgLayout};
  `,
  agentStudioLayout: css`
    display: grid;
    grid-template-columns: minmax(0, 1.55fr) minmax(280px, 0.75fr);
    gap: 14px;
    padding: 16px;
    @media (max-width: 840px) {
      grid-template-columns: 1fr;
    }
  `,
  agentStudioMain: css`
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 14px;
  `,
  agentStudioAside: css`
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 14px;
  `,
  studioSection: css`
    padding: 16px;
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: ${token.borderRadiusLG}px;
    background: ${token.colorBgContainer};
  `,
  studioSectionHeader: css`
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 14px;
    h3 {
      display: flex;
      align-items: center;
      gap: 7px;
      margin: 0;
      color: ${token.colorText};
      font-size: 13px;
    }
    p {
      margin: 4px 0 0;
      color: ${token.colorTextTertiary};
      font-size: 11px;
      line-height: 1.5;
    }
  `,
  twoColumnForm: css`
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 12px;
    @media (max-width: 640px) {
      grid-template-columns: 1fr;
    }
  `,
  runtimePicker: css`
    margin-bottom: 18px;
  `,
  runtimePickerHeader: css`
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 8px;
    > div {
      display: flex;
      min-width: 0;
      flex-direction: column;
      gap: 2px;
    }
    span {
      color: ${token.colorText};
      font-size: 12px;
      font-weight: 500;
    }
    small {
      color: ${token.colorTextTertiary};
      font-size: 11px;
      line-height: 1.45;
    }
  `,
  runtimeOptions: css`
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 8px;
    @media (max-width: 720px) {
      grid-template-columns: 1fr;
    }
  `,
  runtimeOption: css`
    position: relative;
    display: grid;
    min-width: 0;
    min-height: 66px;
    grid-template-columns: auto minmax(0, 1fr);
    align-items: center;
    gap: 9px;
    padding: 10px;
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: ${token.borderRadiusLG}px;
    background: ${token.colorBgContainer};
    color: ${token.colorText};
    cursor: pointer;
    text-align: left;
    transition: border-color 0.15s, background 0.15s, box-shadow 0.15s;
    &:hover,
    &:focus-visible {
      border-color: ${token.colorPrimaryBorder};
      background: ${token.colorFillQuaternary};
      outline: none;
    }
    &:disabled {
      cursor: not-allowed;
      opacity: 0.52;
    }
  `,
  runtimeOptionSelected: css`
    border-color: ${token.colorPrimaryBorder};
    background: ${token.colorPrimaryBg};
    box-shadow: 0 0 0 1px ${token.colorPrimaryBorder};
  `,
  runtimeOptionLogo: css`
    display: grid;
    width: 34px;
    height: 34px;
    place-items: center;
    overflow: hidden;
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: ${token.borderRadius}px;
    background: ${token.colorBgElevated};
    color: ${token.colorPrimary};
  `,
  runtimeProviderLogo: css`
    width: 22px;
    height: 22px;
    object-fit: contain;
  `,
  runtimeOptionCopy: css`
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 3px;
    strong,
    small {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    strong {
      padding-right: 16px;
      color: ${token.colorText};
      font-size: 12px;
      font-weight: 600;
    }
    small {
      color: ${token.colorTextTertiary};
      font-size: 10px;
    }
  `,
  runtimeOptionState: css`
    display: flex;
    grid-column: 1 / -1;
    align-items: center;
    gap: 5px;
    margin-top: -4px;
    color: ${token.colorTextTertiary};
    font-size: 10px;
    i {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: ${token.colorTextQuaternary};
    }
    i[data-online='true'] {
      background: ${token.colorSuccess};
    }
    small {
      margin-left: auto;
      color: ${token.colorTextQuaternary};
      font-family: ${token.fontFamilyCode};
      font-size: 9px;
    }
  `,
  runtimeOptionCheck: css`
    position: absolute;
    top: 9px;
    right: 9px;
    color: ${token.colorPrimary};
  `,
  runtimeOptionSkeleton: css`
    display: flex;
    min-height: 66px;
    align-items: center;
    gap: 9px;
    padding: 10px;
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: ${token.borderRadiusLG}px;
    .ant-skeleton {
      min-width: 0;
    }
    .ant-skeleton-paragraph {
      margin-block-start: 5px !important;
    }
    .ant-skeleton-paragraph li {
      height: 8px !important;
    }
  `,
  runtimePickerEmpty: css`
    margin-top: 8px;
    color: ${token.colorTextTertiary};
    font-size: 11px;
  `,
  runtimePickerError: css`
    margin-top: 8px;
    color: ${token.colorErrorText};
    font-size: 11px;
  `,
  avatarEditor: css`
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 14px;
    padding: 12px;
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: ${token.borderRadiusLG}px;
    background: ${token.colorFillQuaternary};
    > div:last-child {
      display: flex;
      align-items: center;
      gap: 4px;
    }
  `,
  capabilityGrid: css`
    display: grid;
    grid-template-columns: 1fr;
    gap: 4px;
    .ant-checkbox-wrapper {
      margin-inline-start: 0;
      padding: 7px 8px;
      border-radius: ${token.borderRadius}px;
      &:hover {
        background: ${token.colorFillQuaternary};
      }
    }
  `,
  studioFooter: css`
    position: sticky;
    bottom: 0;
    display: flex;
    justify-content: flex-end;
    gap: 8px;
    padding: 12px 16px;
    border-top: 1px solid ${token.colorBorderSecondary};
    background: color-mix(in srgb, ${token.colorBgContainer} 94%, transparent);
    backdrop-filter: blur(10px);
  `,
  taskCreateHeader: css`
    margin: -4px 0 8px;
    padding-right: 24px;
    span {
      color: ${token.colorTextTertiary};
      font-size: 11px;
    }
    h2 {
      margin: 3px 0 0;
      color: ${token.colorText};
      font-size: 15px;
    }
  `,
  taskCreateForm: css`
    .ant-form-item {
      margin-bottom: 12px;
    }
    > .ant-form-item:first-child {
      margin-bottom: 0;
    }
    > .ant-form-item:nth-child(2) {
      min-height: 150px;
      padding-top: 6px;
      border-top: 1px solid ${token.colorBorderSecondary};
      border-bottom: 1px solid ${token.colorBorderSecondary};
    }
    > .ant-form-item:nth-child(2) textarea {
      min-height: 132px;
      font-size: 13px;
      line-height: 1.6;
    }
  `,
  taskTitleInput: css`
    padding-inline: 0 !important;
    color: ${token.colorText};
    font-size: 20px;
    font-weight: 600;
    &::placeholder {
      color: ${token.colorTextQuaternary};
    }
  `,
  taskPropertyBar: css`
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    .ant-form-item {
      margin-bottom: 0;
    }
  `,
}));
