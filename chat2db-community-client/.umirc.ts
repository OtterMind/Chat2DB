import { defineConfig } from 'umi';
import { communityProductConfig } from './product.community';
import { createMainRootRoute } from './src/utils/mainPageNavigation';
import { extractYarnConfig, generateBuildTime } from './src/utils/package';

const MonacoWebpackPlugin = require('monaco-editor-webpack-plugin');
const path = require('path');

const STORAGE_STORE_NAMES = ['Global', 'User', 'Org', 'Workspace', 'AI', 'Tree'];
const yarnConfig = extractYarnConfig(process.argv);
const isDevelopment = process.env.NODE_ENV === 'development';
const publicPath = yarnConfig.public_path || process.env.UMI_PublicPath || (isDevelopment ? '/' : './');
const assetPublicPath = publicPath.endsWith('/') ? publicPath : `${publicPath}/`;
const storageKeys = STORAGE_STORE_NAMES.map(
  (storeName) => `${communityProductConfig.storageKeyPrefix}${storeName}_Store`,
);
const mainComponent = '@/pages/main';

const createStorageVersionScript = () => `
  var chat2dbStorageVersionKey = ${JSON.stringify(communityProductConfig.storageVersionKey)};
  var chat2dbStorageKeys = ${JSON.stringify(storageKeys)};
  if (localStorage.getItem(chat2dbStorageVersionKey) !== 'v6') {
    chat2dbStorageKeys.forEach(function (key) { localStorage.removeItem(key); });
    localStorage.setItem(chat2dbStorageVersionKey, 'v6');
  }
`;

export default defineConfig({
  title: communityProductConfig.title,
  alias: {
    '@client-extension': path.resolve(__dirname, 'src/client-extension/community.tsx'),
    '@client-runtime': path.resolve(__dirname, 'src/client-runtime/index.ts'),
  },
  base: '/',
  history: { type: 'hash' },
  publicPath,
  hash: false,
  ...(process.env.DISABLE_MFSU === 'true' || isDevelopment ? { mfsu: false } : {}),
  codeSplitting: { jsStrategy: 'depPerChunk' },
  routes: [
    {
      path: '/',
      component: '@/layouts/GlobalLayout/CommunityLayout',
      routes: [
        { path: '/test-jcef', component: 'test-jcef' },
        { path: '/demo', component: 'demo' },
        { path: '/demo2', component: 'demo2' },
        {
          path: '/',
          component: '@/layouts/loginLayout',
          routes: [
            { path: '/zoer-db', component: 'zoerDB' },
            { path: '/settings/:tab', component: mainComponent },
            { path: '/team/:section', redirect: '/workspace' },
            { path: '/team', redirect: '/workspace' },
            { path: '/dashboard/share/:dashboardId', component: mainComponent },
            { path: '/dashboard/:dashboardId', component: mainComponent },
            { path: '/dashboard', component: mainComponent },
            { path: '/stream/:chatId', component: mainComponent },
            { path: '/stream', component: mainComponent },
            { path: '/workspace', component: mainComponent },
            { path: '/plugin', component: mainComponent },
            { path: '/connections', redirect: '/workspace' },
            createMainRootRoute(true, mainComponent),
          ],
        },
      ],
    },
  ],
  npmClient: 'yarn',
  plugins: ['./plugins/htmlPlugin.ts'],
  chainWebpack(config) {
    config.optimization.innerGraph(false);
    config.plugin('monaco-editor').use(MonacoWebpackPlugin, [
      { languages: ['mysql', 'pgsql', 'sql', 'json'] },
    ]);
  },
  proxy: {
    '/api': {
      target: yarnConfig.proxy_target || communityProductConfig.defaultProxyTarget,
      secure: false,
      changeOrigin: true,
      proxyTimeout: 0,
      timeout: 0,
    },
  },
  targets: { chrome: 80 },
  links: [
    {
      rel: 'icon',
      type: 'image/ico',
      sizes: '32x32',
      href: `${assetPublicPath}logo.ico`,
    },
  ],
  headScripts: [createStorageVersionScript()],
  favicons: [`${assetPublicPath}logo.ico`],
  define: {
    __ENV__: process.env.NODE_ENV,
    __RUNTIME_ENV__: 'community',
    __APP_NAME__: communityProductConfig.defaultAppName,
    __APP_CAPITAL_NAME__: communityProductConfig.capitalName,
    __APP_DISPLAY_NAME__: communityProductConfig.title,
    __APP_PROTOCOL_SCHEME__: communityProductConfig.protocolScheme,
    __STORAGE_KEY_PREFIX__: communityProductConfig.storageKeyPrefix,
    __STORAGE_VERSION_KEY__: communityProductConfig.storageVersionKey,
    __BUILD_TIME__: generateBuildTime(),
    __APP_VERSION__: yarnConfig.app_version || '5.3.0',
    __PRINT_LOGS__: yarnConfig.print_logs === 'true',
    __GATEWAY_URL__: undefined,
    __WEBAPP__: false,
  },
  esbuildMinifyIIFE: true,
  extraBabelPlugins: [require.resolve('babel-plugin-antd-style')],
});
