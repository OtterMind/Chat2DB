import { memo, useEffect, useRef, useState } from 'react';
import { useStyles } from './style';
import chatService from '@/service/chat';
import { ChatExamplePrompt } from '@/typings/chat';
import SQLPreview from '@/components/SQLPreview';
import { QuestionType } from '@/constants/chat';

interface IProps {
  className?: string;
  onSendQuestionTemplate: (item: ChatExamplePrompt) => void;
}

export default memo<IProps>(({ onSendQuestionTemplate }) => {
  const { styles } = useStyles();
  const [explamePromptList, setExamplePromptList] = useState<ChatExamplePrompt[]>([]);
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;
    queryExamplePrompt();
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const queryExamplePrompt = async () => {
    const res = await chatService.queryChatExamplePrompt({});
    if (res && mountedRef.current) {
      setExamplePromptList(res);
    }
  };

  const sendQuestionTemplate = (item) => {
    console.log('sendQuestionTemplate', item);
    onSendQuestionTemplate && onSendQuestionTemplate(item);
  };

  return (
    <div className={styles.chatQuestionTemplateBox}>
      <div className={styles.chatQuestionTemplate}>
        {explamePromptList.map((item, index) => {
          return (
            <div className={styles.questionTemplateItem} key={index} onClick={sendQuestionTemplate.bind(null, item)}>
              <div className={styles.title}>{item.title}</div>
              <div className={styles.description}>
                {[QuestionType.SQL_EXPLAIN, QuestionType.SQL_OPTIMIZER].includes(item.type) ? (
                  <SQLPreview className={styles.code} sql={item.content} source="chat-question-template" copyable={false} />
                ) : (
                  item.content
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
});
