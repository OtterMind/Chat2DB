import themeAutoImg from '@/assets/img/theme-auto.png';
import themeDarkDimmedImg from '@/assets/img/theme-dark-dimmed.png';
import themeDarkImg from '@/assets/img/theme-dark.png';
import themeLightImg from '@/assets/img/theme-light.png';
import { runtimeEditionConfig } from '@/constants/runtimeEdition';
import type { LangType } from '@/constants/settings';
import i18n from '@/i18n';
import { useGlobalStore } from '@/store/global';
import { settingSelectors } from '@/store/global/selectors';
import { refreshPage } from '@/utils';
import { PrimaryColors, primaryColorsScales, PrimaryGradient, ThemeAppearance } from '@chat2db/ui';
import { Input, Select } from 'antd';
import { Check, ChevronDown, Globe, Palette, Type } from 'lucide-react';
import { useMemo } from 'react';
import { getAvailableLanguageOptions, resolveCurrentLanguage } from './model';
import { useStyles } from './style';

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
      setLanguage: state.setLanguage,
      setCustomFont: state.setCustomFont,
      setCustomFontSize: state.setCustomFontSize,
    };
  });

  // If it is not a domestic version, Chinese will not be displayed.
  const curLanguageOptions = useMemo(() => {
    return getAvailableLanguageOptions(runtimeEditionConfig.languageRegionRestricted, isCN);
  }, [isCN]);

  // If it is not a domestic version, Chinese will not be displayed.
  const curLanguage = useMemo(() => {
    return resolveCurrentLanguage(language, runtimeEditionConfig.languageRegionRestricted, isCN);
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

  function changeThemeMode(code: ThemeAppearance) {
    setAppearance(code);
  }

  function changePrimaryColor(item: any) {
    setPrimaryColor(item);
  }

  return (
    <div className={styles.settingsList}>
      <div className={styles.settingRow} data-setting-group="appearance" data-setting-search-id="basic.appearance">
        <div className={styles.settingMeta}>
          <Palette aria-hidden="true" className={styles.settingGroupIcon} size={18} strokeWidth={1.8} />
          <div className={styles.settingMetaContent}>
            <div className={styles.settingTitle} data-setting-search-title="true">
              {i18n('setting.title.appearance')}
            </div>
            <div className={styles.settingDescription}>{i18n('setting.text.appearanceDescribe')}</div>
          </div>
        </div>
        <div className={styles.settingStack}>
          <div className={styles.controlBlock}>
            <div className={styles.fieldLabel}>{i18n('setting.title.backgroundColor')}</div>
            <div aria-label={i18n('setting.title.backgroundColor')} className={styles.backgroundList} role="group">
              {themeList.map((t) => {
                const isActive = appearance === t.code;
                return (
                  <button
                    aria-pressed={isActive}
                    className={cx(styles.themeItemBox, { [styles.activeThemeItemBox]: isActive })}
                    key={t.code}
                    onClick={() => changeThemeMode(t.code)}
                    type="button"
                  >
                    <span
                      className={cx(styles.themeBox, { [styles.activeThemeBox]: isActive })}
                      style={{ backgroundImage: `url(${t.img})` }}
                    >
                      {isActive ? (
                        <span className={styles.themeCheck}>
                          <Check aria-hidden="true" size={13} strokeWidth={2.2} />
                        </span>
                      ) : null}
                    </span>
                    <span className={styles.themeName}>{t.name}</span>
                  </button>
                );
              })}
            </div>
          </div>
          <div aria-label={i18n('setting.title.themeColor')} className={styles.controlBlock} role="group">
            <div className={styles.fieldLabel}>{i18n('setting.title.themeColor')}</div>
            <div className={styles.swatchesBox}>
              {primaryColorsSwatches.map((color) => {
                const isActive = primaryColor?.label === color.label;
                return (
                  <button
                    aria-label={color.label}
                    aria-pressed={isActive}
                    className={cx(styles.colorSwatch, { [styles.activeColorSwatch]: isActive })}
                    key={color.label}
                    onClick={() => changePrimaryColor(color)}
                    style={{ backgroundColor: color.value }}
                    title={color.label}
                    type="button"
                  >
                    {isActive ? <Check aria-hidden="true" size={14} strokeWidth={2.5} /> : null}
                  </button>
                );
              })}
            </div>
          </div>
        </div>
      </div>
      <div className={styles.settingRow} data-setting-group="language" data-setting-search-id="basic.language">
        <div className={styles.settingMeta}>
          <Globe aria-hidden="true" className={styles.settingGroupIcon} size={18} strokeWidth={1.8} />
          <div className={styles.settingMetaContent}>
            <div className={styles.settingTitle} data-setting-search-title="true">
              {i18n('setting.title.language')}
            </div>
            <div className={styles.settingDescription}>{i18n('setting.title.languageDescribe')}</div>
          </div>
        </div>
        <div className={styles.settingControl}>
          <Select
            aria-label={i18n('setting.title.language')}
            className={styles.languageSelect}
            value={curLanguage}
            onChange={changeLang}
            options={curLanguageOptions}
            suffixIcon={<ChevronDown size={14} />}
          />
        </div>
      </div>
      <div className={styles.settingRow} data-setting-group="typography" data-setting-search-id="basic.typography">
        <div className={styles.settingMeta}>
          <Type aria-hidden="true" className={styles.settingGroupIcon} size={18} strokeWidth={1.8} />
          <div className={styles.settingMetaContent}>
            <div className={styles.settingTitle} data-setting-search-title="true">
              {i18n('setting.title.interfaceFont')}
            </div>
            <div className={styles.settingDescription}>{i18n('setting.text.interfaceFontDescribe')}</div>
          </div>
        </div>
        <div className={styles.fontControls}>
          <label className={styles.fieldControl}>
            <span className={styles.fieldLabel}>{i18n('setting.title.customFont')}</span>
            <Input
              aria-label={i18n('setting.title.customFont')}
              value={customFont}
              onChange={(e) => {
                setCustomFont(e.target.value);
              }}
              placeholder={i18n('setting.placeholder.customFont')}
            />
          </label>
          <label className={styles.fieldControl}>
            <span className={styles.fieldLabel}>{i18n('setting.title.customFontSize')}</span>
            <Select
              aria-label={i18n('setting.title.customFontSize')}
              value={customFontSize}
              placeholder={i18n('setting.title.customFontSizeDescribe')}
              onChange={(value) => {
                setCustomFontSize(value);
              }}
              options={customFontSizeOptions}
              suffixIcon={<ChevronDown size={14} />}
            />
          </label>
        </div>
      </div>
    </div>
  );
}
