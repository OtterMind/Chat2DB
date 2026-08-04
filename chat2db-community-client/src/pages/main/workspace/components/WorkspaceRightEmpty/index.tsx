import { memo, Fragment } from 'react';
import { ChevronRight } from 'lucide-react';
import { Button } from 'antd';
import i18n from '@/i18n';
import { useStyles } from './style';
import classnames from 'classnames';
import { osNow } from '@/utils';
import { useGlobalStore } from '@/store/global';
import { IframeType } from '@/constants';
import { IconfontSvg } from '@chat2db/ui';

interface IProps {
  className?: string;
  slot: any;
}

const keyboardKey = (function () {
  if (osNow().isMac) {
    return {
      command: '⌘',
      Shift: '⇧',
    };
  }
  return {
    command: 'Ctrl',
    Shift: 'Shift',
  };
})();

const shortcutsList = [
  {
    title: i18n('common.text.executeSelectedSQL'),
    keys: [keyboardKey.command, 'R'],
  },
  {
    title: i18n('common.text.saveConsole'),
    keys: [keyboardKey.command, 'S'],
  },
  {
    title: i18n('common.button.createConsole'),
    keys: [keyboardKey.command, keyboardKey.Shift, 'L'],
  },
  {
    title: i18n('common.text.textToSQL'),
    keys: ['/'],
  },
  {
    title: i18n('common.text.moreAI'),
    keys: [i18n('common.text.editorRightClick')],
  },
];

export default memo<IProps>((props) => {
  const { className, slot } = props;
  const { styles } = useStyles();
  const { isEmbedIframe, dismissed, setWorkspaceAiIntroDismissed } = useGlobalStore((state) => ({
    isEmbedIframe: state.isEmbedIframe,
    dismissed: state.workspaceAiIntroDismissed,
    setWorkspaceAiIntroDismissed: state.setWorkspaceAiIntroDismissed,
  }));

  if (isEmbedIframe === IframeType.ZOER) {
    return null;
  }

  const handleGoToAI = () => {
    window.dispatchEvent(new CustomEvent('app:navigateTo', { detail: { page: 'stream' } }));
  };

  const handleDismiss = () => {
    setWorkspaceAiIntroDismissed(true);
  };

  if (!dismissed) {
    return (
      <div className={classnames(styles.box, className)}>
        <div className={styles.aiIntro}>
          <div className={styles.aiIconWrap}>
            <span className={styles.aiSpark}>✦</span>
          </div>
          <div className={styles.aiTitle}>Chat2DB AI</div>
          <div className={styles.aiDesc}>{i18n('stream.intro.desc')}</div>
          <div className={styles.featureRow}>
            <div className={styles.featureCard}>
              <IconfontSvg className={styles.featureIcon} code="icon-search" size={16} />
              <span>{i18n('stream.intro.feature.query')}</span>
            </div>
            <div className={styles.featureCard}>
              <IconfontSvg className={styles.featureIcon} code="icon-chart-square-bar" size={16} />
              <span>{i18n('stream.intro.feature.chart')}</span>
            </div>
            <div className={styles.featureCard}>
              <IconfontSvg className={styles.featureIcon} code="icon-sparkles" size={16} />
              <span>{i18n('stream.intro.feature.analysis')}</span>
            </div>
          </div>
          <Button type="primary" className={styles.aiCta} onClick={handleGoToAI}>
            {i18n('stream.intro.cta')}
            <ChevronRight className={styles.aiCtaArrow} size={14} />
          </Button>
          <button className={styles.dismissBtn} onClick={handleDismiss}>
            {i18n('stream.intro.dismiss')}
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className={classnames(styles.box, className)}>
      <div className={styles.letterpress}>Chat2DB</div>
      <div className={styles.shortcuts}>
        {shortcutsList.map((t, i) => {
          return (
            <div key={i} className={styles.shortcutsItem}>
              <div className={styles.title}>{t.title}</div>
              <div className={styles.plusSignBox}>
                {t.keys.map((item, index) => {
                  return (
                    <Fragment key={index}>
                      <span>{item}</span>
                      {index + 1 < t.keys.length && <span className={styles.plusSign}>+</span>}
                    </Fragment>
                  );
                })}
              </div>
            </div>
          );
        })}
      </div>
      {slot()}
    </div>
  );
});
