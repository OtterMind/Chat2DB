import { clientRuntime } from '@client-runtime';
import { LangType } from '@/constants/settings';
import { useGlobalStore } from '@/store/global';
import { refreshPage } from '@/utils';
import { useEffect } from 'react';

const useEnglish = () => {
  const { language, isCN, isReady, setLanguage } = useGlobalStore((s) => ({
    language: s.baseSetting.language,
    isCN: s.appConfig.isCN,
    isReady: s.appConfig.isReady,
    setLanguage: s.setLanguage,
  }));

  useEffect(() => {
    if (clientRuntime.restrictChineseOutsideChina && isReady && !isCN && language === LangType.ZH_CN) {
      setLanguage(LangType.EN_US);
      refreshPage();
    }
  }, [isCN, language, isReady]);
};

export default useEnglish;
