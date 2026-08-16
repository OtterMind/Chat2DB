import { defineConfig } from 'umi';
import { communityProductConfig } from './product.community';
import { createMainRootRoute } from './src/utils/mainPageNavigation';
import { extractYarnConfig, generateBuildTime } from './src/utils/package';

const MonacoWebpackPlugin = require('monaco-editor-webpack-plugin');
const path = require('path');

const RUNTIME_MODE = {
  WEB: 'web',
  DESKTOP: 'desktop',
  OFFLINE: 'offline',
  COMMUNITY: 'community',
};
const LOCAL_DESKTOP_RUNTIME_MODES = [RUNTIME_MODE.DESKTOP, RUNTIME_MODE.OFFLINE, RUNTIME_MODE.COMMUNITY];
const DEFAULT_LOGO_URL = 'https://cdn.chat2db-ai.com/img/logo.svg';
const DEFAULT_PROXY_TARGET = 'http://127.0.0.1:11921';
const STORAGE_STORE_NAMES = ['Global', 'User', 'Org', 'Workspace', 'AI', 'Tree'];
const GOOGLE_TAG_MANAGER_SCRIPT = {
  src: 'https://www.googletagmanager.com/gtag/js?id=G-PLPZ9PBJEY',
  async: true,
};

type RuntimeMode = (typeof RUNTIME_MODE)[keyof typeof RUNTIME_MODE];
type RuntimeProfile = {
  title: string;
  defaultAppName: string;
  localDesktop: boolean;
  community: boolean;
  localLogo: boolean;
  defaultProxyTarget: string;
  storageKeyPrefix: string;
  storageVersionKey: string;
};

const COMMERCIAL_PROFILE: RuntimeProfile = {
  title: 'Chat2DB Pro',
  defaultAppName: 'chat2db-pro',
  localDesktop: false,
  community: false,
  localLogo: false,
  defaultProxyTarget: DEFAULT_PROXY_TARGET,
  storageKeyPrefix: 'Chat2DB_',
  storageVersionKey: 'app-local-storage-versions',
};

const RUNTIME_PROFILES: Record<string, RuntimeProfile> = {
  [RUNTIME_MODE.WEB]: COMMERCIAL_PROFILE,
  [RUNTIME_MODE.DESKTOP]: {
    ...COMMERCIAL_PROFILE,
    localDesktop: true,
  },
  [RUNTIME_MODE.OFFLINE]: {
    ...COMMERCIAL_PROFILE,
    defaultAppName: 'chat2db-local',
    localDesktop: true,
    storageKeyPrefix: 'Chat2DB_Local_',
    storageVersionKey: 'app-local-storage-versions-local',
  },
  [RUNTIME_MODE.COMMUNITY]: {
    title: communityProductConfig.title,
    defaultAppName: communityProductConfig.defaultAppName,
    localDesktop: true,
    community: true,
    localLogo: communityProductConfig.localLogo,
    defaultProxyTarget: communityProductConfig.defaultProxyTarget,
    storageKeyPrefix: communityProductConfig.storageKeyPrefix,
    storageVersionKey: communityProductConfig.storageVersionKey,
  },
};

// yarn run build --app_port=xx Get the parameters passed in from the command line when packaging
// How to get parameters yarn_config.app_port
const yarn_config = extractYarnConfig(process.argv);

const getRuntimeMode = (): RuntimeMode => (process.env.UMI_ENV || RUNTIME_MODE.WEB) as RuntimeMode;

const createStorageKeys = (storageKeyPrefix: string) =>
  STORAGE_STORE_NAMES.map((storeName) => `${storageKeyPrefix}${storeName}_Store`);

const createBuildProfile = () => {
  const runtimeMode = getRuntimeMode();
  const runtimeProfile = RUNTIME_PROFILES[runtimeMode] || COMMERCIAL_PROFILE;
  const isLocalDesktop = runtimeProfile.localDesktop || LOCAL_DESKTOP_RUNTIME_MODES.includes(runtimeMode);
  const isDevelopment = process.env.NODE_ENV === 'development';
  const publicPath = yarn_config.public_path || process.env.UMI_PublicPath || (isLocalDesktop && !isDevelopment ? './' : '/');
  const assetPublicPath = publicPath.endsWith('/') ? publicPath : `${publicPath}/`;

  return {
    runtimeMode,
    isCommunity: runtimeProfile.community,
    isLocalDesktop,
    title: runtimeProfile.title,
    appName: process.env.APP_NAME || runtimeProfile.defaultAppName,
    publicPath,
    faviconUrl: runtimeProfile.localLogo ? `${assetPublicPath}logo.ico` : DEFAULT_LOGO_URL,
    defaultProxyTarget: runtimeProfile.defaultProxyTarget,
    storageVersionKey: runtimeProfile.storageVersionKey,
    storageKeys: createStorageKeys(runtimeProfile.storageKeyPrefix),
  };
};

const buildProfile = createBuildProfile();
const disableMfsu = process.env.DISABLE_MFSU === 'true' || (process.env.NODE_ENV === 'development' && buildProfile.isCommunity);
const GLOBAL_LAYOUT_COMPONENT = buildProfile.isCommunity
  ? '@/layouts/GlobalLayout/CommunityLayout'
  : '@/layouts/GlobalLayout';
const MAIN_COMPONENT = buildProfile.isCommunity ? '@/pages/main/CommunityMainPage' : 'main';

const chainWebpack = (config: any, { webpack }: any) => {
  // Webpack 5.88 can replace @xterm/xterm TaskQueue base classes with null.
  config.optimization.innerGraph(false);
  config.plugin('monaco-editor').use(MonacoWebpackPlugin, [
    {
      languages: ['mysql', 'pgsql', 'sql', 'json'],
    },
  ]);
  if (buildProfile.isCommunity) {
    config.resolve.alias
      .set('@/components/Price', path.resolve(__dirname, 'src/community-stubs/components/Price.tsx'))
      .set('@/service/pricing', path.resolve(__dirname, 'src/community-stubs/service/pricing.ts'))
      .set('@/service/invitation', path.resolve(__dirname, 'src/community-stubs/service/invitation.ts'))
      .set('@/service/license', path.resolve(__dirname, 'src/community-stubs/service/license.ts'));
  }
};

const createLoginRoutes = () =>
  buildProfile.isCommunity
    ? []
    : [
        {
          path: '/login',
          component: '@/layouts/unLoginLayout',
          routes: [{ path: '/login', component: '@/pages/login/index' }],
        },
      ];

const createCommercialRoutes = () =>
  buildProfile.isCommunity
    ? []
    : [
        {
          path: '/price',
          component: 'price',
        },
        {
          path: '/invite',
          component: 'invite',
        },
        {
          path: '/purchase',
          component: 'purchase',
        },
      ];

const createTeamRoute = () =>
  buildProfile.isCommunity
    ? {
        path: '/team',
        redirect: '/workspace',
      }
    : {
        path: '/team',
        component: MAIN_COMPONENT,
      };

const createDashboardRoutes = () => {
  const dashboardPaths = ['/dashboard/share/:dashboardId', '/dashboard/:dashboardId', '/dashboard'];
  return dashboardPaths.map((path) => ({
    path,
    component: MAIN_COMPONENT,
  }));
};

const createStorageVersionScript = () => `
      var chat2dbStorageVersionKey = ${JSON.stringify(buildProfile.storageVersionKey)};
      var chat2dbStorageKeys = ${JSON.stringify(buildProfile.storageKeys)};
      if (localStorage.getItem(chat2dbStorageVersionKey) !== 'v6') {
        chat2dbStorageKeys.forEach(function (key) {
          localStorage.removeItem(key);
        });
        localStorage.setItem(chat2dbStorageVersionKey, 'v6');
      }
    `;

const createHeadScripts = () => [
  createStorageVersionScript(),
  ...(buildProfile.isCommunity ? [] : [GOOGLE_TAG_MANAGER_SCRIPT]),
];

export default defineConfig({
  title: buildProfile.title,
  base: '/',
  history: buildProfile.isLocalDesktop ? { type: 'hash' } : undefined,
  publicPath: buildProfile.publicPath,
  hash: false,
  ...(disableMfsu ? { mfsu: false } : {}),
  codeSplitting: {
    jsStrategy: 'depPerChunk',
  },
  routes: [
    {
      path: '/',
      component: GLOBAL_LAYOUT_COMPONENT,
      routes: [
        {
          path: '/test-jcef',
          component: 'test-jcef',
        },
        {
          path: '/demo',
          component: 'demo',
        },
        {
          path: '/demo2',
          component: 'demo2',
        },
        ...createLoginRoutes(),
        {
          path: '/',
          component: '@/layouts/loginLayout',
          routes: [
            {
              path: '/zoer-db',
              component: 'zoerDB',
            },
            ...createCommercialRoutes(),
            {
              path: '/settings/:tab',
              component: MAIN_COMPONENT,
            },
            createTeamRoute(),
            ...createDashboardRoutes(),
            {
              path: '/chat',
              component: MAIN_COMPONENT,
            },
            {
              path: '/chat/:chatId',
              component: MAIN_COMPONENT,
            },
            {
              path: '/stream/:chatId',
              component: MAIN_COMPONENT,
            },
            {
              path: '/stream',
              component: MAIN_COMPONENT,
            },
            {
              path: '/workspace',
              component: MAIN_COMPONENT,
            },
            {
              path: 'plugin',
              component: MAIN_COMPONENT,
            },
            {
              path: '/knowledge-management',
              component: MAIN_COMPONENT,
            },
            // Compatible with older versions
            {
              path: '/connections',
              redirect: '/workspace',
            },
            createMainRootRoute(buildProfile.isCommunity, MAIN_COMPONENT),
          ],
        },
      ],
    },
  ],
  npmClient: 'yarn',
  plugins: ['./plugins/htmlPlugin.ts'],
  chainWebpack,
  proxy: {
    '/api': {
      target: yarn_config.proxy_target || buildProfile.defaultProxyTarget,
      secure: false,
      changeOrigin: true,
      proxyTimeout: 0,
      timeout: 0,
    },
    '/client/remaininguses/': {
      target: 'http://127.0.0.1:1889',
      changeOrigin: true,
    },
  },
  targets: {
    chrome: 80,
  },
  // links: [{
  //   rel: 'manifest',
  //   href: 'manifest.json',
  // }],
  links: [{ rel: 'icon', type: 'image/ico', sizes: '32x32', href: buildProfile.faviconUrl }],
  headScripts: createHeadScripts(),
  favicons: [buildProfile.faviconUrl],
  define: {
    __ENV__: process.env.NODE_ENV,
    __RUNTIME_ENV__: buildProfile.runtimeMode,
    __APP_NAME__: buildProfile.appName,
    __BUILD_TIME__: generateBuildTime(),
    __APP_VERSION__: yarn_config.app_version || '5.3.0',
    __PRINT_LOGS__: yarn_config.print_logs === 'true',
    __GATEWAY_URL__: yarn_config.gateway_url,
    __WEBAPP__: yarn_config.webapp === 'true',
  },
  esbuildMinifyIIFE: true,
  extraBabelPlugins: [require.resolve('babel-plugin-antd-style')],
  // jsMinifierOptions: {
  //   drop: ['debugger'],
  // },
});
