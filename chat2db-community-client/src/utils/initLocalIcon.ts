import { isDevelopment } from './env';

const MONOCHROME_ICONFONT_SCRIPT_ID = 'chat2db-monochrome-iconfont';
const MONOCHROME_ICONFONT_SCRIPT = '/iconfont/iconfont.js';
const COLOR_ICONFONT_SCRIPT_ID = 'chat2db-color-iconfont';
const COLOR_ICONFONT_SCRIPT = '//at.alicdn.com/t/c/font_4551262_fnn84ra2j4v.js';

const appendIconfontScript = (id: string, src: string) => {
  if (document.getElementById(id)) {
    return;
  }

  const script = document.createElement('script');
  script.id = id;
  script.src = src;
  script.async = true;
  document.body.appendChild(script);
};

export const initializeDevEnvironmentIcon = () => {
  // The monochrome icon font must remain available offline in the desktop package.
  appendIconfontScript(MONOCHROME_ICONFONT_SCRIPT_ID, MONOCHROME_ICONFONT_SCRIPT);

  if (isDevelopment) {
    // color
    appendIconfontScript(COLOR_ICONFONT_SCRIPT_ID, COLOR_ICONFONT_SCRIPT);
  }
};
