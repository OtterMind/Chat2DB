import React, { memo, useMemo, useState } from 'react';
import { ChevronDown } from 'lucide-react';
import { useStyles } from './style';
import { Spin } from 'antd';
import { AnswerPartsStatus, AnswerPartsType } from '@/constants/chat';
import { IconfontSvg } from '@chat2db/ui';
import i18n from '@/i18n';
import { AnswerParts } from '@/typings/chat';

interface IProps {
  className?: string;
  parts: AnswerParts;
  children?: React.ReactNode;
}

const analyzingConfig = {
  [AnswerPartsType.MARKDOWN]: {
    analyzingText: 'chat.ai.common.analyzingText',
    analyzedText: 'chat.ai.common.analyzedText',
  },
  [AnswerPartsType.DASHBOARD]: {
    analyzingText: 'chat.ai.common.analyzingText.dashboard',
    analyzedText: 'chat.ai.common.analyzedText.dashboard',
  },
  [AnswerPartsType.DATA]: {
    analyzingText: 'chat.ai.common.analyzingText.data',
    analyzedText: 'chat.ai.common.analyzedText.data',
  },
};

export default memo<IProps>((props) => {
  const { className, parts } = props;
  const { partType, status, loadingText } = parts;
  // const loading = status === AnswerPartsStatus.LOADING;
  const { styles, cx } = useStyles();
  const currentConfig = analyzingConfig[partType || AnswerPartsType.MARKDOWN];
  const [hiddenAnalyze, setHiddenAnalyze] = useState(false);

  // useEffect(() => {

  //   if (chatMessageRenderType === AnswerPartsType.MARKDOWN) {
  //     setHiddenAnalyze(true);
  //   }
  // }, [chatMessageRenderType, step]);

  const loading = useMemo(() => {
    return status === AnswerPartsStatus.LOADING;
  }, [status]);

  const renderAnalyzing = () => {
    return (
      <>
        <div className={styles.prefix}>
          <Spin size="small" />
        </div>
        <span className={styles.text}> {loadingText || i18n(currentConfig.analyzingText as any)}</span>
      </>
    );
  };

  const renderAnalyzed = () => {
    return (
      <>
        <div className={styles.prefix}>
          <IconfontSvg className={styles.succeedIcon} size="md" code="icon-wancheng" />
        </div>
        <span className={styles.text}>{loadingText || i18n(currentConfig.analyzedText as any)}</span>
      </>
    );
  };

  const renderAnalyze = () => {
    if (!currentConfig) {
      return null;
    }
    return (
      <div className={cx(styles.analyze, className)}>
        <div className={styles.analyzeCenter} onClick={() => setHiddenAnalyze(!hiddenAnalyze)}>
          {loading ? renderAnalyzing() : renderAnalyzed()}
          {/* {renderAnalyzing()} */}
          <span className={cx(styles.fold, { [styles.unfold]: !hiddenAnalyze })}>
            <ChevronDown size={12} />
          </span>
        </div>
      </div>
    );
  };

  return (
    <div className={styles.container}>
      {renderAnalyze()}
      {/* <div className={cx(styles.analyzeContent)}>{props.children}</div> */}
      <div className={cx(styles.analyzeContent, { [styles.hiddenContent]: hiddenAnalyze })}>{props.children}</div>
    </div>
  );
});
