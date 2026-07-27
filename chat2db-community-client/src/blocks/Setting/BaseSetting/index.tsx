import themeAutoImg from '@/assets/img/theme-auto.png';
import themeDarkDimmedImg from '@/assets/img/theme-dark-dimmed.png';
import themeDarkImg from '@/assets/img/theme-dark.png';
import themeLightImg from '@/assets/img/theme-light.png';
import { runtimeEditionConfig } from '@/constants/runtimeEdition';
import { LangType } from '@/constants/settings';
import i18n from '@/i18n';
import { useGlobalStore } from '@/store/global';
import { settingSelectors } from '@/store/global/selectors';
import { refreshPage } from '@/utils';
import { PrimaryColors, primaryColorsScales, PrimaryGradient, Swatches, ThemeAppearance } from '@chat2db/ui';
import { Form, Input, Select } from 'antd';
import { ChevronDown } from 'lucide-react';
import { useMemo } from 'react';
import SettingSubsection from '../SettingSubsection';
import { useStyles } from './style';

const languageOptions = [
  { value: LangType.ZH_CN, label: '简体中文' },
  { value: LangType.EN_US, label: 'English' },
  { value: LangType.JA_JP, label: '日本語' },
  { value: LangType.ES_ES, label: 'Español' },
  { value: LangType.KO_KR, label: '한국어' },
];

const customFontSizeOptions = [
  { value: 12, label: '12px' },
  { value: 13, label: '13px' },
  { value: 14, label: '14px' },
  { value: 15, label: '15px' },
];

const themeList = [
  {
    code: ThemeAppearance.Light,
    name: i18n('setting.text.light'),
    img: themeLightImg,
  },
  {
    code: ThemeAppearance.Dark,
    name: i18n('setting.text.dark'),
    img: themeDarkImg,
  },
  {
    code: ThemeAppearance.DarkDimmed,
    name: i18n('setting.text.darkDimmed'),
    img: themeDarkDimmedImg,
  },
  {
    code: ThemeAppearance.Auto,
    name: i18n('setting.text.followOS'),
    img: themeAutoImg,
  },
];

// baseBody basic settings
export default function BaseSetting() {
  const { styles, cx } = useStyles();
  const {
    appearance,
    setAppearance,
    primaryColor,
    setPrimaryColor,
    language,
    setLanguage,
    customFont,
    setCustomFont,
    customFontSize,
    setCustomFontSize,
    isCN,
  } = useGlobalStore((state) => {
    return {
      ...settingSelectors.currentBaseSetting(state),
      setAppearance: state.setAppearance,
      isCN: state.appConfig.isCN,
      setPrimaryColor: state.setPrimaryColor,
      setNeutralColor: state.setNeutralColor,
      setLanguage: state.setLanguage,
      setCustomFont: state.setCustomFont,
      setCustomFontSize: state.setCustomFontSize,
    };
  });

  // If it is not a domestic version, Chinese will not be displayed.
  const curLanguageOptions = useMemo(() => {
    if (runtimeEditionConfig.languageRegionRestricted && !isCN) {
      return languageOptions.filter((item) => item.value !== LangType.ZH_CN);
    }
    return languageOptions;
  }, [isCN]);

  // If it is not a domestic version, Chinese will not be displayed.
  const curLanguage = useMemo(() => {
    if (runtimeEditionConfig.languageRegionRestricted && !isCN && language === LangType.ZH_CN) {
      return LangType.EN_US;
    }
    return language;
  }, [language, isCN]);

  const isDark = appearance.includes('dark');

  const primaryColorsSwatches = useMemo(
    () =>
      Object.keys(primaryColorsScales).map((k) => ({
        label: k,
        value: (primaryColorsScales[k as PrimaryColors][isDark ? 'dark' : 'light'] as PrimaryGradient).colorPrimary,
      })),
    [isDark],
  );

  function changeLang(value: LangType) {
    setLanguage(value);
    window.setTimeout(refreshPage, 0);
  }

  function changeThemeMode(item: any) {
    setAppearance(item.code);
  }

  function changePrimaryColor(item: any) {
    setPrimaryColor(item);
  }

  // function changeNeutralColor(item: any) {
  //   setNeutralColor(item);
  // }

  return (
    <div className={styles.baseSettingBox}>
      <div>
        <SettingSubsection
          title={i18n('setting.title.backgroundColor')}
          describe={i18n('setting.text.backgroundColorDescribe')}
        />
        <div className={styles.backgroundList}>
          {themeList.map((t) => {
            return (
              <div key={t.code} className={styles.themeItemBox}>
                <div
                  className={cx(styles.themeBox, { [styles.activeThemeBox]: appearance == t.code })}
                  onClick={changeThemeMode.bind(null, t)}
                  style={{ backgroundImage: `url(${t.img})` }}
                />
                <div className={styles.themeName}>{t.name}</div>
              </div>
            );
          })}
        </div>
      </div>
      <div>
        <SettingSubsection title={i18n('setting.title.language')} describe={i18n('setting.title.languageDescribe')} />
        <Select
          value={curLanguage}
          style={{ width: 140 }}
          onChange={changeLang}
          options={curLanguageOptions}
          suffixIcon={<ChevronDown size={14} />}
        />
      </div>
      <div>
        <SettingSubsection
          title={i18n('setting.title.customFont')}
          describe={i18n('setting.title.customFontDescribe')}
        />
        <Form className={styles.customFontBox}>
          {/* <Form.Item label={i18n('setting.title.customFont')} name="customFont"> */}
          <Input
            value={customFont}
            style={{ width: 300 }}
            onChange={(e) => {
              setCustomFont(e.target.value);
            }}
            placeholder={i18n('setting.placeholder.customFont')}
          />
          {/* </Form.Item>
          <Form.Item label={i18n('setting.title.customFontSize')} name="customFontSize"> */}
          <Select
            value={customFontSize}
            placeholder={i18n('setting.title.customFontSizeDescribe')}
            style={{ width: 200 }}
            onChange={(e) => {
              setCustomFontSize(e);
            }}
            options={customFontSizeOptions}
            suffixIcon={<ChevronDown size={14} />}
          />
          {/* </Form.Item> */}
        </Form>
      </div>
      <div>
        <SettingSubsection
          title={i18n('setting.title.themeColor')}
          describe={i18n('setting.title.themeColorDescribe')}
        />
        <Swatches
          size={28}
          gap={12}
          activeColor={primaryColor?.label}
          colors={primaryColorsSwatches}
          onSelect={changePrimaryColor}
        />
      </div>
      {/* <div>
        <SettingSubsection
          title={i18n('setting.title.neutralColor')}
          describe={i18n('setting.title.neutralColorDescribe')}
        />
        <div className={styles.primaryColorList}>
          <Swatches
            size={28}
            gap={12}
            activeColor={neutralColor}
            colors={neutralColorsSwatches}
            onSelect={changeNeutralColor}
          />
        </div>
      </div> */}
    </div>
  );
}
