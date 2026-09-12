import React, {
  memo,
  useState,
  forwardRef,
  ForwardedRef,
  useImperativeHandle,
  useEffect,
  useRef,
  useCallback,
} from 'react';
import { Input, Select, Tag } from 'antd';
import { CloseOutlined } from '@ant-design/icons';
import { ChatSourceType, QuestionType } from '@/constants/chat';
import { PromptTableVO } from '@/typings/chat';
import { DatabaseTypeCode } from '@/constants';

import i18n from '@/i18n';
import AICascaderSource, { IAICascaderData } from '../AICascaderSource';
import AIAtMetion from '../AIAtMetion';
import { SuggestionItem } from '../AIAtMetion/interface';
import AIModelSelect from '../AIModelSelect';
import sqlService from '@/service/sql';
import { ITable } from '@/typings';
import { useGlobalStore } from '@/store/global';
import { useStyles } from './style';
import { keyboardKey } from '@/utils';
import { useAIStore } from '@/store/ai';
import { useWorkspaceStore } from '@/store/workspace';
import { captureAgentContext, contextScope } from '../../agentContext';
import type { AgentRunContextRequest } from '@/types/agentContext';
import { ErrorCode } from '@/constants/request';
import agentService from '@/service/agent';
import { commandSuggestions, detectInputSuggestion, replaceSkillTrigger, skillSuggestions, type InputSuggestionTrigger } from './inputSuggestions';

import { TextAreaRef } from 'antd/es/input/TextArea';
import { PageType } from '@/store/ai/slices/cascader/initialState';
import { debounce } from 'lodash';
import { IconButton } from '@chat2db/ui';
import PiToolSettings from '../PiToolSettings';
import aiAttachmentService, { IChatAttachment } from '@/service/aiAttachment';
import { isDesktop } from '@/utils/env';
import jcefApi from '@/jcef';
import feedback from '@/utils/feedback';
import {
  reconcileSelectedMentions,
  replaceMentionTrigger,
  upsertSelectedMention,
  type SelectedMention,
} from './mentionSelection';

export interface SendParams {
  input: string;
  questionType: QuestionType;
  source: ChatSourceType;
  // database information
  dataSourceId?: number;
  databaseName?: string;
  schemaName?: string;
  databaseType?: DatabaseTypeCode;
  tableName?: string;
  // selected table
  tableList?: PromptTableVO[];
  // model
  model?: string;

  // optimize sql, selected sql
  sql?: string;

  attachments?: IChatAttachment[];
  agentContext?: AgentRunContextRequest;
}

interface ChatInputProps {
  className?: string;
  chatInputAreaClassName?: string;
  loading?: boolean;
  sendDisabled?: boolean;
  contextInfo?: IAICascaderData;
  onContextChange?: (contextInfo: IAICascaderData) => void;
  // Whether to clear the input box after sending
  clearAfterSend?: boolean;
  inputRightAddons?: React.ReactNode;
  // Hide selection database
  hideDatabaseSelect?: boolean;
  modelOptions?: Array<{ label: string; value: string; isDefault?: boolean }>;
  showCustomModelEntry?: boolean;
  onCustomModelClick?: () => void;
  customModelText?: string;
  runtimeChoice?: 'DEFAULT' | 'PI';
  onRuntimeChange?: (value: 'DEFAULT' | 'PI') => void;
  prefillInputState?: { text: string; token: number; questionType?: QuestionType } | null;
  onChatSend?: (param: SendParams) => void;
  onStop?: () => void;
  autoSize?: boolean | { minRows?: number; maxRows?: number };
  autoFocus?: boolean;
}

export interface ChatInputPropsRef {
  triggerSend: (params: SendParams) => void;
  setQuestionType: (value: QuestionType) => void;
  focusInput: () => void;
  resetAttachments: () => void;
  openAttachmentPicker: () => void;
}

const ATTACHMENT_ACCEPT = '.pdf,.doc,.docx,.md,.txt,.json,.csv,.xlsx,.xls';
const ATTACHMENT_FILE_TYPES = ['pdf', 'doc', 'docx', 'md', 'txt', 'json', 'csv', 'xlsx', 'xls'];
const ATTACHMENT_PARSE_MESSAGE_KEY = 'chat-attachment-parse';
const AIChatInput = forwardRef((props: ChatInputProps, ref: ForwardedRef<ChatInputPropsRef>) => {
  const {
    className,
    chatInputAreaClassName,
    loading,
    sendDisabled = false,
    hideDatabaseSelect,
    modelOptions,
    showCustomModelEntry,
    onCustomModelClick,
    customModelText,
    runtimeChoice,
    onRuntimeChange,
    prefillInputState,
    onChatSend,
    onContextChange,
    onStop,
    clearAfterSend = true,
    autoSize,
    autoFocus = false,
  } = props;
  const { styles } = useStyles();
  const [inputValue, setInputValue] = useState('');
  const [prefillQuestionType, setPrefillQuestionType] = useState<QuestionType>();
  const [tableList, setTableList] = useState<ITable[]>([]);
  const [selectedMentions, setSelectedMentions] = useState<SelectedMention[]>([]);
  const [suggestionTrigger, setSuggestionTrigger] = useState<InputSuggestionTrigger | null>(null);
  const [skills, setSkills] = useState<string[]>([]);
  const [attachments, setAttachments] = useState<IChatAttachment[]>([]);
  const [attachmentLoading, setAttachmentLoading] = useState(false);
  const textareaRef = useRef<TextAreaRef>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const isComposingRef = useRef<boolean>(false); // IME input method combination status
  const tableRequestSequenceRef = useRef(0);

  // caches tables without search conditions
  const tableListWithoutSearchKey = useRef<ITable[]>([]);

  const { mainPageActiveTab } = useGlobalStore((state) => ({
    mainPageActiveTab: state.mainPageActiveTab,
  }));

  const { cascaderDataMap, setCascaderData, clearCascaderData } = useAIStore((state) => ({
    cascaderDataMap: state.cascaderDataMap,
    setCascaderData: state.setCascaderData,
    clearCascaderData: state.clearCascaderData,
  }));

  useEffect(() => {
    setSuggestionTrigger(null);
    setSkills([]);
    if (runtimeChoice !== 'PI') return;
    const controller = new AbortController();
    agentService.listSkills(undefined, { signal: controller.signal })
      .then((names) => { if (!controller.signal.aborted) setSkills(names); })
      .catch(() => { if (!controller.signal.aborted) feedback.error(i18n('stream.skill.loadFailed')); });
    return () => controller.abort();
  }, [runtimeChoice]);

  const activeWorkspaceTab = useWorkspaceStore((state) =>
    state.workspaceTabList?.find((tab) => tab.id === state.activeConsoleId));
  const activeTable = activeWorkspaceTab?.uniqueData;

  useEffect(() => {
    if (runtimeChoice !== 'PI' || mainPageActiveTab !== 'workspace' || !activeTable?.dataSourceId
        || !(activeTable.tableName || activeTable.viewName)) return;
    const selected = {
      dataSourceId: activeTable.dataSourceId,
      dataSourceName: activeTable.dataSourceName,
      databaseType: activeTable.databaseType,
      databaseName: activeTable.databaseName,
      schemaName: activeTable.schemaName,
    };
    if (!isSameContextInfo(useAIStore.getState().cascaderDataMap.workspace, selected)) {
      setCascaderData('workspace', selected);
    }
  }, [runtimeChoice, mainPageActiveTab, activeWorkspaceTab?.id, activeTable?.dataSourceId, activeTable?.databaseName,
    activeTable?.schemaName, activeTable?.tableName, activeTable?.viewName]);

  const focusInput = useCallback(() => {
    const textarea = textareaRef.current?.resizableTextArea?.textArea;
    if (!textarea) return;
    textarea.focus();
    const length = textarea.value.length;
    textarea.setSelectionRange(length, length);
  }, []);

  useImperativeHandle(ref, () => ({
    triggerSend,
    setQuestionType: setPrefillQuestionType,
    focusInput,
    resetAttachments: () => {
      setAttachments([]);
      if (fileInputRef.current) {
        fileInputRef.current.value = '';
      }
    },
    openAttachmentPicker: () => {
      handleAttachmentTrigger();
    },
  }));

  useEffect(() => {
    if (!autoFocus) return;

    const timer = window.setTimeout(() => {
      focusInput();
    }, 0);

    return () => {
      window.clearTimeout(timer);
    };
  }, [autoFocus, focusInput]);

  useEffect(() => {
    if (props.contextInfo) {
      setCascaderData(mainPageActiveTab as PageType, props.contextInfo ?? null);
    }
  }, [props.contextInfo, mainPageActiveTab]);

  useEffect(() => {
    if (!prefillInputState?.token) {
      return;
    }

    setInputValue(prefillInputState.text || '');
    setPrefillQuestionType(prefillInputState.questionType);
    window.setTimeout(() => {
      focusInput();
    }, 0);
  }, [focusInput, prefillInputState?.token]);

  useEffect(() => {
    tableListWithoutSearchKey.current = [];
    tableRequestSequenceRef.current += 1;
    setSelectedMentions([]);
    setSuggestionTrigger(null);
    setTableList([]);
    if (cascaderDataMap[mainPageActiveTab]) {
      fetchTableList(cascaderDataMap[mainPageActiveTab], '');
    }
  }, [cascaderDataMap[mainPageActiveTab]]);

  const fetchTableList = useRef(
    debounce(async (_contextInfo: IAICascaderData, searchKey: string, requestSequence?: number) => {
      if (!_contextInfo) return;
      if (requestSequence !== undefined && requestSequence !== tableRequestSequenceRef.current) return;
      if ('dataSourceId' in _contextInfo && _contextInfo?.dataSourceId) {
        if (!searchKey && tableListWithoutSearchKey.current.length) {
          setTableList(tableListWithoutSearchKey.current);
          return;
        }
        let res;
        let viewRes;
        try {
          res = await sqlService.getTableList({
            dataSourceId: _contextInfo.dataSourceId,
            databaseName: _contextInfo.databaseName,
            schemaName: _contextInfo.schemaName,
            pageNo: 1,
            pageSize: 1000,
            searchKey,
          });
          viewRes = await sqlService.getViewList({
            dataSourceId: _contextInfo.dataSourceId,
            databaseName: _contextInfo.databaseName,
            schemaName: _contextInfo.schemaName,
            pageNo: 1,
            pageSize: 1000,
            searchKey,
          });
        } catch (error) {
          if (requestSequence !== undefined && requestSequence !== tableRequestSequenceRef.current) return;
          const requestError = error as { errorCode?: string };
          if (
            requestError.errorCode === 'QUERY_DATASOURCE_ERROR' ||
            requestError.errorCode === ErrorCode.NeedLoggedIn
          ) {
            tableListWithoutSearchKey.current = [];
            setTableList([]);
            clearCascaderData(mainPageActiveTab as PageType);
          }
          return;
        }

        if (requestSequence !== undefined && requestSequence !== tableRequestSequenceRef.current) return;

        const atTableList =
          res.data?.map((s) => ({
            ...s,
            tableType: 'TABLE',
          })) || [];

        const atViewList =
          viewRes.data?.map((s) => ({
            ...s,
            tableType: 'VIEW',
          })) || [];

        const list = [...atTableList, ...atViewList];

        if (!searchKey) {
          tableListWithoutSearchKey.current = list;
        }

        setTableList(list);
      }
    }, 300),
  ).current;

  useEffect(() => {
    return () => fetchTableList.cancel();
  }, []);

  const handleSend = async (params?: SendParams) => {
    if (loading || attachmentLoading || sendDisabled) return;

    /**
     * source parameter
     * workspace drawer: DATASOURCE_DRAWER_CHAT
     * dashboard drawer: DASHBOARD_DRAWER_CHAT
     * chat drawer: DRAWER_CHAT
     * console box: DATASOURCE_CONSOLE_CHAT
     *
     */
    let source = params?.source || ChatSourceType.DASHBOARD_DRAWER_CHAT;
    if (mainPageActiveTab === 'workspace') {
      source = ChatSourceType.DATASOURCE_DRAWER_CHAT;
    } else if (mainPageActiveTab === 'dashboard') {
      source = ChatSourceType.DASHBOARD_DRAWER_CHAT;
    } else if (mainPageActiveTab === 'chat' || mainPageActiveTab === 'stream') {
      source = ChatSourceType.DRAWER_CHAT;
    }

    /**
     * questionType parameter
     * default is ORDINARY_CHAT
     * console opens as NL_2_SQL
     */
    const questionType = params?.questionType || prefillQuestionType || QuestionType.ORDINARY_CHAT;

    const finalAttachments = params?.attachments ?? attachments;
    const rawInput = params?.input ?? inputValue;
    const trimmedInput = (rawInput || '').trim();
    const finalInput =
      trimmedInput ||
      (finalAttachments.length
        ? _contextHasDatabase(cascaderDataMap[mainPageActiveTab])
          ? '请结合已上传文件和当前数据库上下文进行联合分析，给出关键发现、验证思路和建议。'
          : '请基于已上传文件进行分析，给出摘要、关键发现、风险点和建议。'
        : '');

    if (!finalInput) return;

    const contextInfo = cascaderDataMap[mainPageActiveTab];
    const _contextInfo = contextInfo
      ? {
          ...contextInfo,
          dataSourceId: params?.dataSourceId || ('dataSourceId' in contextInfo ? contextInfo?.dataSourceId : undefined),
          databaseName: params?.databaseName || ('databaseName' in contextInfo ? contextInfo?.databaseName : undefined),
          schemaName: params?.schemaName || ('schemaName' in contextInfo ? contextInfo?.schemaName : undefined),
          tableName: params?.tableName ?? undefined,
        }
      : null;

    const workspace = useWorkspaceStore.getState();
    const currentWorkspaceTable = mainPageActiveTab === 'workspace'
      ? workspace.workspaceTabList?.find((tab) => tab.id === workspace.activeConsoleId)?.uniqueData : undefined;
    const selectedScope = params?.dataSourceId !== undefined
      ? params : contextInfo && 'dataSourceId' in contextInfo ? contextInfo : null;
    const mentions = selectedMentions.flatMap((mention) => mention.contextObject ? [mention.contextObject] : []);
    const currentTable = params?.tableName ? params : currentWorkspaceTable;
    const agentContext = captureAgentContext(selectedScope, currentTable, mentions);
    const _params = {
      ..._contextInfo,
      ...params,
      questionType,
      input: finalInput,
      source,
      model: useAIStore.getState().selectedModel?.value,
      agentContext,
      tableList: selectedMentions
        .map((mention) => ({ tableName: mention.tableName, tableType: mention.tableType })) as any,
      attachments: finalAttachments,
    };

    onChatSend?.(_params);

    setSelectedMentions([]);
    setPrefillQuestionType(undefined);
    setAttachments([]);
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
    if (clearAfterSend) {
      setInputValue('');
      setSuggestionTrigger(null);
    }
  };

  const triggerSend = (params: SendParams) => {
    if (loading || sendDisabled) return;

    handleSend(params);
  };

  const handleChange = (value: string) => {
    setSelectedMentions((previous) => reconcileSelectedMentions(value, previous));
    setInputValue(value);

    if (!value) {
      setPrefillQuestionType(undefined);
      return;
    }
  };

  const _contextHasDatabase = (contextInfo?: IAICascaderData | null) => {
    if (!contextInfo) {
      return false;
    }
    return Boolean('dataSourceId' in contextInfo && contextInfo.dataSourceId);
  };

  const parseSelectedFiles = useCallback(
    async (selectedFiles: Array<{ file?: File; filePath?: string; fileName?: string }>) => {
      if (!selectedFiles.length) {
        return;
      }

      setAttachmentLoading(true);
      feedback.loading({
        content: i18n('stream.attachment.parsing'),
        key: ATTACHMENT_PARSE_MESSAGE_KEY,
        duration: 0,
      });

      try {
        const results = await Promise.allSettled(
          selectedFiles.map((item) =>
            aiAttachmentService.parseAttachment({
              file: item.file,
              filePath: item.filePath,
              fileName: item.fileName,
            }),
          ),
        );

        const parsedAttachments = results
          .filter((item): item is PromiseFulfilledResult<IChatAttachment> => item.status === 'fulfilled')
          .map((item) => item.value);

        console.log('[AI attachments] parsed result', {
          requestedCount: selectedFiles.length,
          successCount: parsedAttachments.length,
          attachments: parsedAttachments.map((attachment) => ({
            fileName: attachment.fileName,
            fileType: attachment.fileType,
            contentCategory: attachment.contentCategory,
            contentLength: attachment.contentLength,
            truncated: attachment.truncated,
            contentPreview: attachment.content?.slice(0, 200),
          })),
        });

        if (parsedAttachments.length) {
          setAttachments((prev) => {
            const next = [...prev];
            parsedAttachments.forEach((attachment) => {
              const duplicateIndex = next.findIndex(
                (item) => item.fileName === attachment.fileName && item.content === attachment.content,
              );
              if (duplicateIndex === -1) {
                next.push(attachment);
              }
            });
            return next;
          });
        }

        const failedCount = results.length - parsedAttachments.length;
        if (!parsedAttachments.length) {
          feedback.error({
            content: i18n('stream.attachment.parseFailed'),
            key: ATTACHMENT_PARSE_MESSAGE_KEY,
          });
          return;
        }

        if (failedCount > 0) {
          feedback.warning({
            content: i18n('stream.attachment.partialFailed', parsedAttachments.length),
            key: ATTACHMENT_PARSE_MESSAGE_KEY,
          });
          return;
        }

        feedback.destroy(ATTACHMENT_PARSE_MESSAGE_KEY);
      } catch {
        feedback.error({
          content: i18n('stream.attachment.parseFailed'),
          key: ATTACHMENT_PARSE_MESSAGE_KEY,
        });
      } finally {
        setAttachmentLoading(false);
      }
    },
    [],
  );

  const handleAttachmentTrigger = useCallback(() => {
    if (attachmentLoading || loading) {
      return;
    }

    if (isDesktop) {
      jcefApi
        .selectFile({
          fileTypeList: ATTACHMENT_FILE_TYPES,
          multiple: true,
        })
        .then((data) => {
          const selectedFiles =
            data?.map((item) => ({
              filePath: item.filePath,
              fileName: item.fileName,
            })) || [];
          return parseSelectedFiles(selectedFiles);
        })
        .catch(() => {
          feedback.error(i18n('stream.attachment.parseFailed'));
        });
      return;
    }

    fileInputRef.current?.click();
  }, [attachmentLoading, loading, parseSelectedFiles]);

  const handleFileInputChange = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const selectedFiles = Array.from(event.target.files || []).map((file) => ({
      file,
      fileName: file.name,
    }));
    await parseSelectedFiles(selectedFiles);
    event.target.value = '';
  };

  const removeAttachment = (index: number) => {
    setAttachments((prev) => prev.filter((_, currentIndex) => currentIndex !== index));
  };

  const getSuggestionList = (info?: InputSuggestionTrigger) => {
    if (info?.kind === 'slash') return runtimeChoice === 'PI' ? [...commandSuggestions(info.query), ...skillSuggestions(skills, info.query)] : [];
    const selected = cascaderDataMap[mainPageActiveTab];
    const scope = contextScope(selected && 'dataSourceId' in selected ? selected : null);
    const tables: SuggestionItem[] = (tableList || []).map((table) => ({
      label: table.name,
      value: JSON.stringify([scope?.dataSourceId, scope?.database, scope?.schema, table.tableType, table.name]),
      kind: 'table',
      tableName: table.name,
      tableType: table.tableType,
      contextObject: scope ? { ...scope, type: table.tableType === 'VIEW' ? 'VIEW' : 'TABLE',
        name: table.name, source: 'MENTION' } : undefined,
      extra: table.tableType === 'VIEW' ? '视图' : '表',
    }));
    if (!info?.query) return tables;
    return tables.filter((item) => item.label.toLowerCase().includes(info.query.toLowerCase()));
  };

  const updateSuggestions = (
    value: string,
    cursor: number,
    onTrigger: (info?: InputSuggestionTrigger | false) => void,
  ) => {
    const nextTrigger = detectInputSuggestion(value, cursor, runtimeChoice);
    tableRequestSequenceRef.current += 1;
    setSuggestionTrigger(nextTrigger);
    if (nextTrigger?.kind === 'table') {
      setTableList([]);
      fetchTableList(cascaderDataMap[mainPageActiveTab], nextTrigger.query, tableRequestSequenceRef.current);
    }
    onTrigger(nextTrigger || false);
  };

  const isSameContextInfo = (prev: IAICascaderData, next: IAICascaderData) => {
    if (prev === next) {
      return true;
    }
    if (!prev || !next) {
      return !prev && !next;
    }

    return (
      prev.dataSourceId === next.dataSourceId &&
      prev.databaseName === next.databaseName &&
      prev.schemaName === next.schemaName
    );
  };

  return (
    <AIAtMetion<InputSuggestionTrigger>
      className={className}
      items={getSuggestionList}
      open={!!suggestionTrigger}
      onOpenChange={(open) => { if (!open) setSuggestionTrigger(null); }}
      onSelect={(item) => {
        const textarea = textareaRef.current?.resizableTextArea?.textArea;
        const cursor = textarea?.selectionStart ?? inputValue.length;
        const activeTrigger = suggestionTrigger || detectInputSuggestion(inputValue, cursor, runtimeChoice);
        if (!activeTrigger || (activeTrigger.kind === 'table') !== (item.kind === 'table')) return;
        const replacement = item.kind !== 'table'
          ? replaceSkillTrigger(inputValue, activeTrigger, item.label)
          : replaceMentionTrigger(inputValue, activeTrigger, item.label);

        setInputValue(replacement.value);
        if (item.kind === 'table') setSelectedMentions((previous) => {
          const nextMention: SelectedMention = {
            value: item.value,
            label: item.label,
            kind: item.kind,
            tableName: item.tableName,
            tableType: item.tableType,
            contextObject: item.contextObject,
          };
          return upsertSelectedMention(previous, nextMention);
        });
        tableRequestSequenceRef.current += 1;
        setSuggestionTrigger(null);
        window.setTimeout(() => {
          textarea?.focus();
          textarea?.setSelectionRange(replacement.cursor, replacement.cursor);
        }, 0);
      }}
    >
      {({ onTrigger, onKeyDown, isOpen }) => (
        <div className={`${styles.chatInputArea}${chatInputAreaClassName ? ` ${chatInputAreaClassName}` : ''}`}>
          <input
            ref={fileInputRef}
            type="file"
            accept={ATTACHMENT_ACCEPT}
            multiple
            className={styles.hiddenFileInput}
            onChange={handleFileInputChange}
          />
          {!!attachments.length && (
            <div className={styles.attachmentList}>
              {attachments.map((attachment, index) => (
                <div key={`${attachment.fileName}-${index}`} className={styles.attachmentItem}>
                  <span className={styles.attachmentName} title={attachment.fileName}>
                    {attachment.fileName}
                  </span>
                  <button
                    type="button"
                    className={styles.attachmentRemoveButton}
                    onClick={() => removeAttachment(index)}
                  >
                    <CloseOutlined />
                  </button>
                </div>
              ))}
            </div>
          )}
          <Input.TextArea
            ref={textareaRef}
            className={styles.textarea}
            placeholder={loading ? undefined : i18n('ai.input.placeholder', `${keyboardKey.command} + K`)}
            value={inputValue}
            disabled={loading || attachmentLoading}
            autoSize={autoSize ?? { minRows: 1, maxRows: 8 }}
            onChange={(e) => {
              const value = e.target.value;
              handleChange(value);
              if (!isComposingRef.current) {
                updateSuggestions(value, e.target.selectionStart, onTrigger);
              }
            }}
            onCompositionStart={() => {
              isComposingRef.current = true;
            }}
            onCompositionEnd={(e) => {
              isComposingRef.current = false;
              updateSuggestions(e.currentTarget.value, e.currentTarget.selectionStart, onTrigger);
            }}
            onClick={(e) => {
              updateSuggestions(e.currentTarget.value, e.currentTarget.selectionStart, onTrigger);
            }}
            onKeyDown={(e) => {
              if (isComposingRef.current || e.nativeEvent.isComposing || e.keyCode === 229) return;
              if (isOpen && ['ArrowDown', 'ArrowUp', 'ArrowRight', 'ArrowLeft', 'Enter', 'Tab', 'Escape'].includes(e.key)) {
                onKeyDown(e);
                if (e.defaultPrevented) return;
              }

              // Enter sends, Shift+Enter inserts a newline, and IME composition does not send.
              if (e.key === 'Enter' && !e.shiftKey && !isComposingRef.current && !loading) {
                e.preventDefault();
                handleSend();
              }
            }}
            onKeyUp={(e) => {
              if (!isComposingRef.current && ['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(e.key)) {
                updateSuggestions(e.currentTarget.value, e.currentTarget.selectionStart, onTrigger);
              }
            }}
          />
          <div className={styles.bottomAddonsRow}>
            <div className={styles.bottomAddonsLeft}>
              {!hideDatabaseSelect && (
                <AICascaderSource
                  contextInfo={cascaderDataMap[mainPageActiveTab]}
                  onFileSelect={handleAttachmentTrigger}
                  onChange={(data) => {
                    const prevContext = cascaderDataMap[mainPageActiveTab];
                    if (isSameContextInfo(prevContext, data)) {
                      return;
                    }
                    setCascaderData(mainPageActiveTab as PageType, data);
                    onContextChange?.(data);
                  }}
                />
              )}
            </div>
            <div className={styles.bottomAddonsRight}>
              {runtimeChoice ? (
                <Select
                  className={styles.runtimeSelect}
                  size="small"
                  variant="borderless"
                  aria-label="Agent"
                  disabled={loading || !onRuntimeChange}
                  popupMatchSelectWidth={156}
                  value={runtimeChoice}
                  options={[
                  { value: 'DEFAULT', label: i18n('stream.runtime.default') },
                  { value: 'PI', label: i18n('stream.runtime.pi') },
                ]}
                  optionRender={(option) => (
                  <div className={styles.runtimeOption}>
                    <span>{option.label}</span>
                    {option.value === 'PI' ? <Tag color="gold">Beta</Tag> : null}
                  </div>
                )}
                  onChange={onRuntimeChange}
                />
              ) : null}
              {runtimeChoice === 'PI' ? <PiToolSettings /> : null}
              <AIModelSelect
                options={modelOptions}
                showCustomModelEntry={showCustomModelEntry}
                onCustomModelClick={onCustomModelClick}
                customModelText={customModelText}
              />
              {loading ? (
                <IconButton
                  size={{
                    boxSize: 30,
                    iconSize: 22,
                  }}
                  code="icon-chat-stop"
                  className={styles.stopButton}
                  onClick={onStop}
                />
              ) : (
                <IconButton
                  size={{
                    boxSize: 30,
                    iconSize: 22,
                  }}
                  code="icon-chat-send"
                  className={styles.sendButton}
                  disabled={sendDisabled || (!inputValue.trim() && !attachments.length)}
                  onClick={() => handleSend()}
                />
                // <Button
                //   type="primary"
                //   size="small"
                //   shape="circle"
                //   className={styles.sendButton}
                //   icon={<ArrowUpOutlined />}
                //   disabled={!inputValue.trim()}
                //   onClick={() => handleSend()}
                // />
              )}
            </div>
          </div>
        </div>
      )}
    </AIAtMetion>
  );
});

export default memo(AIChatInput);
