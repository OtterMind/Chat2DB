import { useEffect } from 'react';
import { useGlobalStore } from '@/store/global';
import jcefApi from '@/jcef';
import { isDesktop } from '@/utils/env';

const useJcef = () => {
  const { appearance, language } = useGlobalStore((state) => {
    return {
      appearance: state.baseSetting.appearance,
      language: state.baseSetting.language,
    };
  });

  useEffect(() => {
    if (!isDesktop) {
      return;
    }
    jcefApi.updateSettings({
      appearance,
      language,
    });
  }, [appearance, language]);
};

export default useJcef;
