import React, { useState } from 'react';
import { LangType, ThemeType } from '@/constants';
import i18n, { currentLang } from '@/i18n';
import classnames from 'classnames';
import themeDarkImg from '@/assets/img/theme-dark.png';
import themeLightImg from '@/assets/img/theme-light.png';
import themeAutoImg from '@/assets/img/theme-auto.png';
import { Select } from 'antd';
import Iconfont from '@/components/Iconfont';
import { setLang as setLangLocalStorage } from '@/utils/localStorage';
import { useTheme } from '@/hooks/useTheme';

import styles from './index.less';

const themeList = [
  {
    code: ThemeType.Light,
    name: i18n('setting.text.light'),
    img: themeLightImg,
  },
  {
    code: ThemeType.Dark,
    name: i18n('setting.text.dark'),
    img: themeDarkImg,
  },
  {
    code: ThemeType.DarkDimmed,
    name: i18n('setting.text.dark2'),
    img: themeDarkImg
  },
  {
    code: ThemeType.FollowOs,
    name: i18n('setting.text.followOS'),
    img: themeAutoImg,
  },
  // {
  //   code: 'eyeshield',
  //   name: '护眼',
  //   img: 'https://img.alicdn.com/imgextra/i1/O1CN01KGCqY21uJpuFjEQW2_!!6000000006017-2-tps-181-135.png'
  // },
];

const languageOptions = [
  { value: LangType.ZH_CN, label: '简体中文' },
  { value: LangType.EN_US, label: 'English' },
  { value: LangType.TR_TR, label: 'Turkish' },
  { value: LangType.JA_JP, label: '日本語' },
  { value: LangType.ES_ES, label: 'Spanish' },
]

const colorList = [
  {
    code: 'golden-purple',
    name: i18n('setting.label.violet'),
    color: '#9373ee',
  },
  {
    code: 'polar-blue',
    name: i18n('setting.label.blue'),
    color: '#1a90ff',
  },
  {
    code: 'blue2',
    name: i18n('setting.label.violet'),
    color: '#00c3ee',
  },
  {
    code: 'polar-green',
    name: i18n('setting.label.green'),
    color: '#039e74',
  },
  {
    code: 'gold',
    name: i18n('setting.label.violet'),
    color: '#9a7d56',
  },
  {
    code: 'silver',
    name: i18n('setting.label.violet'),
    color: '#8e8374',
  },
  {
    code: 'red',
    name: i18n('setting.label.violet'),
    color: '#fd6874',
  },
  {
    code: 'orange',
    name: i18n('setting.label.violet'),
    color: '#fa8c16',
  },
];

// baseBody 基础设置
export default function BaseSetting() {
  const [appTheme, setAppTheme] = useTheme();
  const [currentTheme, setCurrentTheme] = useState<ThemeType>(appTheme.backgroundColor);
  const [currentPrimaryColor, setCurrentPrimaryColor] = useState(localStorage.getItem('primary-color'));

  const changePrimaryColor = (item: any) => {
    const html = document.documentElement;
    html.setAttribute('primary-color', item.code);
    localStorage.setItem('primary-color', item.code);
    setCurrentPrimaryColor(item.code);
    setAppTheme({
      ...appTheme,
      primaryColor: item.code,
    });
  };

  function changeLang(e: any) {
    setLangLocalStorage(e);
    //切换语言时，需要设置cookie，用来改变后台服务的Locale
    const date = new Date('2030-12-30 12:30:00').toUTCString();
    document.cookie = `CHAT2DB.LOCALE=${e};Expires=${date}`;
    location.reload();
  }

  function handleChangeTheme(backgroundColor: any) {
    setAppTheme({
      ...appTheme,
      backgroundColor,
    });
    setCurrentTheme(backgroundColor);
  }

  // const changeSqlEditorFontSize = (e: any) => {
  // }

  return (
    <>
      <div className={styles.title}>{i18n('setting.title.backgroundColor')}</div>
      <ul className={styles.backgroundList}>
        {themeList.map((t) => {
          return (
            <div key={t.code} className={styles.themeItemBox}>
              <div
                className={classnames({ [styles.current]: currentTheme == t.code }, styles.themeImg)}
                onClick={handleChangeTheme.bind(null, t.code)}
                style={{ backgroundImage: `url(${t.img})` }}
              />
              <div className={styles.themeName}>{t.name}</div>
            </div>
          );
        })}
      </ul>
      <div className={styles.title}>{i18n('setting.title.language')}</div>
      <div className={styles.langBox}>
        {/* <Radio.Group onChange={changeLang} value={currentLang}>
          <Radio value={LangType.ZH_CN}>简体中文</Radio>
          <Radio value={LangType.EN_US}>English</Radio>
          <Radio value={LangType.TR_TR}>Turkish</Radio>
          <Radio value={LangType.ES_ES}>Spanish</Radio>
        </Radio.Group> */}
        <Select
          style={{ width: 120 }}
          onChange={changeLang}
          value={currentLang}
          options={languageOptions}
        />
      </div>
      <div className={styles.title}>{i18n('setting.title.themeColor')}</div>
      <ul className={styles.primaryColorList}>
        {colorList.map((item) => {
          return (
            <div key={item.code} className={styles.themeColorItem}>
              <div
                className={styles.colorLump}
                key={item.code}
                onClick={changePrimaryColor.bind(null, item)}
                style={{ backgroundColor: item.color }}
              >
                {currentPrimaryColor == item.code && <Iconfont code="&#xe617;" />}
              </div>
              {/* <div className={styles.colorName}>{item.name}</div> */}
            </div>
          );
        })}
        {/* <ColorPicker placement='bottomLeft' onChange={setCustomColor}>
          <div className={classnames(styles.themeColorItem, styles.customColorItem) }>
            <div
              className={styles.colorLump}
              onClick={()=>{}}
            >
              自定义
            </div>
          </div>
        </ColorPicker> */}
      </ul>
      {/* <div className={styles.title}>{i18n('setting.title.sqlEditorFontSize')}</div>
      <div className={styles.sqlEditorFontSize}>
        <Radio.Group onChange={changeSqlEditorFontSize}>
          <Radio value={12}>12</Radio>
          <Radio value={14}>14</Radio>
          <Radio value={16}>16</Radio>
        </Radio.Group>
      </div> */}
      
    </>
  );
}
