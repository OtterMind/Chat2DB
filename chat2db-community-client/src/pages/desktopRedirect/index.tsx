import { useEffect } from 'react';
import { Button } from 'antd';
import styles from './index.less';
import { openWebPage } from '@/utils/url';
import { APP_CONFIG } from '@/constants/appConfig';

export default function DesktopRedirect() {
  useEffect(() => {
    openWebPage(`${APP_CONFIG.protocolScheme}://connections`);
  }, []);

  return (
    <div className={styles.styles}>
      <Button
        onClick={() => {
          openWebPage(`${APP_CONFIG.protocolScheme}://connections`);
        }}
      >
        Open Chat2DB
      </Button>
    </div>
  );
}
