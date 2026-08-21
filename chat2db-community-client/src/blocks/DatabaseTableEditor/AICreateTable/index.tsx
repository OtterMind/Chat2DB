import React, { memo, useEffect, useRef, useState, forwardRef, useImperativeHandle, ForwardedRef } from 'react';
import i18n from '@/i18n';
import { useStyles } from './style';
import { ChatInput, IconfontSvg } from '@chat2db/ui';
import FieldPromptInput, { FieldPromptInputRef } from '@/components/FieldPromptInput';
import ExecuteSQL from '@/components/ExecuteSQL';
import { QuestionType } from '@/constants/chat';
import { IDatabaseBaseInfo } from '@/typings';
import useSSERequest, { SSERequestStatus } from '@/hooks/useSSERequest';
import DraggableResizableModal, { DraggableResizableModalRef } from '@/components/DraggableResizableModal';
import { useStyles as useGlobalStyle } from '@/styles/useGlobalStyle';
import miscServices from '@/service/misc';

interface IProps {
  className?: string;
  databaseBaseInfo: IDatabaseBaseInfo;
  executeSuccessCallBack?: (res: any) => void;
}

export interface IAICreateTableRef {
  open: (position: { x: number; y: number; width: number }) => void;
}

const AICreateTable = forwardRef((props: IProps, ref: ForwardedRef<IAICreateTableRef>) => {
  const { databaseBaseInfo, executeSuccessCallBack } = props;
  const [executeSQLBoxShow, setExecuteSQLBoxShow] = useState(false);
  const { styles, cx } = useStyles();
  const { styles: globalStyle } = useGlobalStyle();
  const fieldPromptInputTableNameRef = useRef<FieldPromptInputRef>(null);
  const fieldPromptInputColumnNameRef = useRef<FieldPromptInputRef>(null);
  const aiCreateTableRef = useRef<HTMLDivElement>(null);
  const monacoEditorRef = useRef<any>(null);
  const chatInputBoxRef = useRef<HTMLDivElement>(null);
  // accumulated lastFragment
  const cumulateLastFragmentRef = useRef<string>('');
  const draggableResizableModalRef = useRef<DraggableResizableModalRef>(null);
  const [chatInputValue, setChatInputValue] = useState('');
  const [content, setContent] = useState('');
  const {
    status,
    request,
    stop: stopAiStream,
  } = useSSERequest(
    {
      baseURL: '/api/v3/ai/chat/stream',
    },
    (parsedData) => {
      if ((parsedData.type as string) === 'answer') {
        setContent((prev) => prev + parsedData.content);
        // If the editor is not initialized, the lastFragment is accumulated
        if (!monacoEditorRef.current) {
          cumulateLastFragmentRef.current += parsedData.content;
        } else {
          if (cumulateLastFragmentRef.current) {
            monacoEditorRef.current
              ?.getMonacoEditorRef()
              ?.setValue(cumulateLastFragmentRef.current + parsedData.content);
            cumulateLastFragmentRef.current = '';
          } else {
            monacoEditorRef.current?.getMonacoEditorRef()?.setValue(parsedData.content);
          }
        }
      }
    },
  );

  useEffect(() => {
    if (status === SSERequestStatus.FINISH) {
      miscServices.characterHandler({ text: content }).then((res) => {
        monacoEditorRef.current?.getMonacoEditorRef()?.setValue(res, 'reset');
      });
    }
  }, [status]);

  const closeEventSource = () => {
    stopAiStream();
  };

  useEffect(() => {
    if (!content) {
      setExecuteSQLBoxShow(false);
      draggableResizableModalRef.current?.changeDimensions({ height: getChatInputHeight() });
    } else {
      draggableResizableModalRef.current?.changeDimensions({ height: 600 });
      setExecuteSQLBoxShow(true);
    }
  }, [content]);

  const renderInputCenter = () => {
    return (
      <div className={styles.inputCenter}>
        <FieldPromptInput
          autoFocus
          minWidth={80}
          fieldPrompt={i18n('editTable.label.tableName')}
          ref={fieldPromptInputTableNameRef}
          onEnter={handleSend}
          maxLines={1}
        />
        <FieldPromptInput
          minWidth={300}
          fieldPrompt={i18n('editTable.label.columnName')}
          ref={fieldPromptInputColumnNameRef}
          onEnter={handleSend}
          maxLines={5}
        />
      </div>
    );
  };

  const renderInputLeftAddons = () => {
    return (
      <div className={styles.inputLeftAddonsBox}>
        <div className={styles.inputLeftAddons}>
          <IconfontSvg className={styles.icon} size="sm" code="icon-table" />
        </div>
      </div>
    );
  };

  const handleSend = () => {
    if (status === SSERequestStatus.LOADING) {
      return;
    }
    setContent('');
    draggableResizableModalRef.current?.changeDimensions({ height: getChatInputHeight() });
    if (!databaseBaseInfo.tableName) {
      const tableName = fieldPromptInputTableNameRef.current?.getValue() || '';
      const columnList = fieldPromptInputColumnNameRef.current?.getValue() || '';
      request({
        ...databaseBaseInfo,
        input: `Create table ${tableName} with these columns: ${columnList}`,
        tableName,
        columnList,
        questionType: QuestionType.TEXT_TO_CREATE_TABLE_STREAM,
        enableTools: false,
        persistHistory: false,
        databaseType: databaseBaseInfo.databaseType!,
      });
    } else {
      request({
        ...databaseBaseInfo,
        input: chatInputValue,
        questionType: QuestionType.TEXT_MODIFY_COLUMN,
        enableTools: false,
        persistHistory: false,
        databaseType: databaseBaseInfo.databaseType!,
      });
    }
  };

  useEffect(() => {
    // Bind the carriage return event to chatInputBoxRef. When the carriage enters, handleSend is triggered.
    const handleKeyDown = (event) => {
      if (event.key === 'Enter' && !databaseBaseInfo.tableName) {
        handleSend();
      }
    };
    const aiCreateTable = aiCreateTableRef.current;
    aiCreateTable?.addEventListener('keydown', handleKeyDown);
    return () => {
      aiCreateTable?.removeEventListener('keydown', handleKeyDown);
    };
  }, []);

  // prohibits bubbling and prohibits dragging
  const onMouseDown = (e: React.MouseEvent) => {
    e.stopPropagation();
  };

  useImperativeHandle(ref, () => ({
    open: (position) => {
      draggableResizableModalRef.current?.open(position);
    },
  }));

  const handleChatInputChange = (e) => {
    setChatInputValue(e.target.value);
  };

  const getChatInputHeight = () => {
    const chatInputBox = chatInputBoxRef.current;
    if (!chatInputBox) {
      return 0;
    }
    return chatInputBox.clientHeight + 16;
  };

  return (
    <div className={styles.aiCreateTable} ref={aiCreateTableRef}>
      <DraggableResizableModal
        className={cx({ [globalStyle.aiMoveGradient]: status === SSERequestStatus.LOADING })}
        ref={draggableResizableModalRef}
        sizeConstraints={{
          minWidth: 300,
          minHeight: 60,
        }}
        initialDimensions={{
          width: 0,
          x: 0,
          y: 0,
        }}
        resizeHandles={executeSQLBoxShow ? undefined : ['e', 'w']}
        onClose={() => {
          closeEventSource();
          setExecuteSQLBoxShow(false);
          draggableResizableModalRef.current?.changeDimensions({ height: getChatInputHeight() });
        }}
      >
        <div className={styles.aiCreateTableBox}>
          <div ref={chatInputBoxRef}>
            <ChatInput
              inputLeftAddons={renderInputLeftAddons()}
              className={styles.chatInput}
              inputCenter={databaseBaseInfo.tableName ? undefined : renderInputCenter()}
              onSend={handleSend}
              onChange={handleChatInputChange}
              loading={status === SSERequestStatus.LOADING}
              placeholder={i18n('editTable.placeholder.aiModifyTable')}
            />
          </div>
          {executeSQLBoxShow && (
            <div className={cx(styles.executeSQLBox)} onMouseDown={onMouseDown}>
              <ExecuteSQL
                className={styles.executeSQL}
                ref={monacoEditorRef}
                databaseBaseInfo={databaseBaseInfo}
                executeSuccessCallBack={(res) => {
                  draggableResizableModalRef.current?.close();
                  executeSuccessCallBack && executeSuccessCallBack(res);
                }}
              />
            </div>
          )}
        </div>
      </DraggableResizableModal>
    </div>
  );
});

export default memo(AICreateTable);
