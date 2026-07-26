import React, { memo, useState, forwardRef, ForwardedRef, useImperativeHandle, useEffect, useRef, useCallback } from 'react';
import { Input } from 'antd';
import { CloseOutlined } from '@ant-design/icons';
import { ChatSourceType, QuestionType } from '@/constants/chat';
import { PromptTableVO } from '@/typings/chat';

import i18n from '@/i18n';
import AICascaderSource, { IAICascaderData } from '../AICascaderSource';
import AIAtMetion from '../AIAtMetion';
import { SuggestionItem } from '../AIAtMetion/interface';
import AIModelSelect from '../AIModelSelect';
import sqlService from '@/service/sql';
import aiDataCollectionService from '@/service/aiDataCollection';
import { ITable } from '@/typings';
import { useGlobalStore } from '@/store/global';
import { useStyles } from './style';
import { keyboardKey } from '@/utils';
import { useAIStore } from '@/store/ai';
import { ErrorCode } from '@/constants/request';

import { TextAreaRef } from 'antd/es/input/TextArea';
import { PageType } from '@/store/ai/slices/cascader/initialState';
import { debounce } from 'lodash';
import { IconButton } from '@chat2db/ui';
import aiAttachmentService, { IChatAttachment } from '@/service/aiAttachment';
import { isDesktop } from '@/utils/env';
import jcefApi from '@/jcef';
import feedback from '@/utils/feedback';

export interface SendParams {
  input: string;
  questionType: QuestionType;
  source: ChatSourceType;
  // dataset ID
  dataSourceCollectionId?: number;
  // database information
  dataSourceId?: number;
  databaseName?: string;
  schemaName?: string;
  tableName?: string;
  // selected table
  tableList?: PromptTableVO[];
  // model
  model?: string;

  // optimize sql, selected sql
  sql?: string;

  attachments?: IChatAttachment[];
}

interface ChatInputProps {
  className?: string;
  chatInputAreaClassName?: string;
  loading?: boolean;
  contextInfo?: IAICascaderData;
  onContextChange?: (contextInfo: IAICascaderData) => void;
  // Whether to clear the input box after sending
  clearAfterSend?: boolean;
  dataSourceCollectionId?: number;
  inputRightAddons?: React.ReactNode;
  // Hide selection database
  hideDatabaseSelect?: boolean;
  modelOptions?: Array<{ label: string; value: string; isDefault?: boolean }>;
  showCustomModelEntry?: boolean;
  onCustomModelClick?: () => void;
  customModelText?: string;
  prefillInputState?: { text: string; token: number } | null;
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
    hideDatabaseSelect,
    modelOptions,
    showCustomModelEntry,
    onCustomModelClick,
    customModelText,
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
  const [tableList, setTableList] = useState<ITable[]>([]);
  const [selectedTable, setSelectedTable] = useState<string[]>([]);
  const [attachments, setAttachments] = useState<IChatAttachment[]>([]);
  const [attachmentLoading, setAttachmentLoading] = useState(false);
  const textareaRef = useRef<TextAreaRef>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const atInputRef = useRef<string>(''); // tracks the input after @
  const isTypingAfterAtRef = useRef<boolean>(false); // Whether to enter after @
  const isComposingRef = useRef<boolean>(false); // IME input method combination status

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

  const focusInput = useCallback(() => {
    const textarea = textareaRef.current?.resizableTextArea?.textArea;
    if (!textarea) return;
    textarea.focus();
    const length = textarea.value.length;
    textarea.setSelectionRange(length, length);
  }, []);

  useImperativeHandle(ref, () => ({
    triggerSend,
    setQuestionType: () => {},
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
    window.setTimeout(() => {
      focusInput();
    }, 0);
  }, [focusInput, prefillInputState?.token]);

  useEffect(() => {
    tableListWithoutSearchKey.current = [];
    setSelectedTable([]);
    setTableList([]);
    if (cascaderDataMap[mainPageActiveTab]) {
      fetchTableList(cascaderDataMap[mainPageActiveTab], '');
    }
  }, [cascaderDataMap[mainPageActiveTab]]);

  const fetchTableList = useRef(
    debounce(async (_contextInfo: IAICascaderData, searchKey: string) => {
      if (!_contextInfo) return;
      if ('dataSourceCollectionId' in _contextInfo && _contextInfo?.dataSourceCollectionId) {
        if (!searchKey && tableListWithoutSearchKey.current.length) {
          setTableList(tableListWithoutSearchKey.current);
          return;
        }
        const res = await aiDataCollectionService.getAiDataCollectionElementList({
          id: _contextInfo.dataSourceCollectionId,
          pageNo: 1,
          pageSize: 1000,
        });
        const list: ITable[] =
          res?.elements?.map((item) => ({
            name: item.tableName,
            comment: '',
            tableType: item.type,
          })) || [];
        const filteredList = searchKey
          ? list.filter((item) => item.name.toLowerCase().includes(searchKey.toLowerCase()))
          : list;
        if (!searchKey) {
          tableListWithoutSearchKey.current = filteredList;
        }
        setTableList(filteredList);
        return;
      }
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
          if (error?.errorCode === 'QUERY_DATASOURCE_ERROR' || error?.errorCode === ErrorCode.NeedLoggedIn) {
            tableListWithoutSearchKey.current = [];
            setTableList([]);
            clearCascaderData(mainPageActiveTab as PageType);
          }
          return;
        }

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
    if (loading || attachmentLoading) return;

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
     * Insert test data DATA_INSERT
     */
    const questionType = params?.questionType || QuestionType.ORDINARY_CHAT;

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
          dataSourceCollectionId:
            params?.dataSourceCollectionId || ('dataSourceCollectionId' in contextInfo ? contextInfo?.dataSourceCollectionId : undefined),
          dataSourceId: params?.dataSourceId || ('dataSourceId' in contextInfo ? contextInfo?.dataSourceId : undefined),
          databaseName: params?.databaseName || ('databaseName' in contextInfo ? contextInfo?.databaseName : undefined),
          schemaName: params?.schemaName || ('schemaName' in contextInfo ? contextInfo?.schemaName : undefined),
          tableName: params?.tableName ?? undefined,
        }
      : null;

    const _params = {
      ..._contextInfo,
      ...params,
      questionType,
      input: finalInput,
      source,
      model: useAIStore.getState().selectedModel?.value,
      tableList: (selectedTable || []).map((i) => ({
        tableName: i,
        tableType: tableList.find((s) => s.name === i)?.tableType,
      })) as any,
      attachments: finalAttachments,
    };

    onChatSend?.(_params);

    setSelectedTable([]);
    setAttachments([]);
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
    if (clearAfterSend) {
      setInputValue('');
    }
  };

  const triggerSend = (params: SendParams) => {
    if (loading) return;

    handleSend(params);
  };

  const handleChange = (value: string) => {
    // Check whether the table name is deleted
    const removedTables = selectedTable.filter((tableName) => {
      const pattern = new RegExp(`@${tableName}\\s`);
      return !pattern.test(value);
    });

    // Remove deleted tables from selectedTable.
    if (removedTables.length > 0) {
      setSelectedTable((prev) => prev.filter((table) => !removedTables.includes(table)));
    }

    setInputValue(value);

    if (!value) {
      return;
    }
  };

  const _contextHasDatabase = (contextInfo?: IAICascaderData | null) => {
    if (!contextInfo) {
      return false;
    }
    return Boolean(
      ('dataSourceCollectionId' in contextInfo && contextInfo.dataSourceCollectionId) ||
        ('dataSourceId' in contextInfo && contextInfo.dataSourceId),
    );
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

  const getTableList = (info: string) => {
    const tables: SuggestionItem[] = (tableList || []).map((table) => ({
      label: table.name,
      value: table.name,
      tableType: table.tableType,
    }));

    if (!info) return tables;

    return tables.filter((item) => item.label.toLowerCase().includes(info.toLowerCase()));
  };

  const getTextBeforeCursor = (input: string, cursorPosition: number) => {
    return input.slice(0, cursorPosition);
  };

  const isSameContextInfo = (prev: IAICascaderData, next: IAICascaderData) => {
    if (prev === next) {
      return true;
    }
    if (!prev || !next) {
      return !prev && !next;
    }

    const prevIsCollection = 'dataSourceCollectionId' in prev;
    const nextIsCollection = 'dataSourceCollectionId' in next;
    if (prevIsCollection || nextIsCollection) {
      return (
        prevIsCollection &&
        nextIsCollection &&
        prev.dataSourceCollectionId === next.dataSourceCollectionId
      );
    }

    return (
      prev.dataSourceId === next.dataSourceId &&
      prev.databaseName === next.databaseName &&
      prev.schemaName === next.schemaName
    );
  };

  return (
    <AIAtMetion
      className={className}
      items={getTableList as any}
      onSelect={(v) => {
        const textarea = textareaRef.current;
        const cursorPos = textarea?.resizableTextArea?.textArea?.selectionStart || 0;

        const textBeforeCursor = inputValue.slice(0, cursorPos);
        const lastAtIndex = textBeforeCursor.lastIndexOf('@');

        if (lastAtIndex !== -1) {
          const beforeAt = inputValue.slice(0, lastAtIndex);
          const afterCursor = inputValue.slice(cursorPos);

          setInputValue(beforeAt + '@' + v + ' ' + afterCursor);
        } else {
          setInputValue((prev) => prev + '@' + v + ' ');
        }

        setSelectedTable((prev) => [...prev, v]);
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
            onChange={(e) => handleChange(e.target.value)}
            onCompositionStart={() => {
              isComposingRef.current = true;
            }}
            onCompositionEnd={() => {
              isComposingRef.current = false;
            }}
            onKeyDown={(e) => {
              // Enter sends, Shift+Enter inserts a newline, and IME composition does not send.
              if (e.key === 'Enter' && !e.shiftKey && !isComposingRef.current && !loading) {
                if (!isOpen) {
                  e.preventDefault();
                  handleSend();
                }
              }

              // Mention handling waits for the value update with setTimeout.
              setTimeout(() => {
                const target = e.target as HTMLTextAreaElement;
                const cursorPosition = target.selectionStart;
                const currentInputValue = target.value;
                const textBeforeCursor = getTextBeforeCursor(currentInputValue, cursorPosition);

                if (e.key === 'Escape' || e.key === 'Enter' || e.key === ' ') {
                  isTypingAfterAtRef.current = false;
                  atInputRef.current = '';
                  onTrigger(false);
                }

                if (e.key === '@') {
                  isTypingAfterAtRef.current = true;
                  atInputRef.current = '';
                  onTrigger('');
                  fetchTableList(cascaderDataMap[mainPageActiveTab], '');
                } else if (isTypingAfterAtRef.current) {
                  if (
                    e.key.startsWith('Arrow') ||
                    e.key === 'Home' ||
                    e.key === 'End' ||
                    e.key === 'PageUp' ||
                    e.key === 'PageDown' ||
                    e.key === 'Shift' ||
                    e.key === 'Control' ||
                    e.key === 'Alt' ||
                    e.key === 'Meta' ||
                    e.key === ' '
                  ) {
                    onKeyDown(e);
                    return;
                  }

                  const lastAtIndex = textBeforeCursor.lastIndexOf('@');
                  if (lastAtIndex === -1) {
                    isTypingAfterAtRef.current = false;
                    atInputRef.current = '';
                    onTrigger(false);
                  } else {
                    const newAtInput = textBeforeCursor.slice(lastAtIndex + 1);
                    if (newAtInput !== atInputRef.current) {
                      atInputRef.current = newAtInput;
                      onTrigger(atInputRef.current);
                      fetchTableList(cascaderDataMap[mainPageActiveTab], atInputRef.current);
                    }
                  }
                }

                onKeyDown(e);
              });
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
                  disabled={!inputValue.trim() && !attachments.length}
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
