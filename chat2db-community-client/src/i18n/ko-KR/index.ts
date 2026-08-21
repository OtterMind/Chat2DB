import { LangType } from '@/constants/settings';
import common from './common';
import connection from './connection';
import menu from './menu';
import setting from './setting';
import workspace from './workspace';
import dashboard from './dashboard';
import chat from './chat';
import login from './login';
import editTable from './editTable';
import editTableData from './editTableData';
import sqlEditor from './sqlEditor';
import spaceSetting from './spaceSetting';
import price from './price';
import monaco from './monaco';
import ai from './ai';
import stream from './stream';
import userGuide from './userGuide';
import feedback from './feedback';
import notification from './notification';
import redis from './redis';
import invite from './invite';
import team from './team';
import plugin from './plugin';
import license from './license';

export default {
  lang: LangType.KO_KR,
  ...common,
  ...setting,
  ...connection,
  ...workspace,
  ...menu,
  ...dashboard,
  ...chat,
  ...login,
  ...editTable,
  ...editTableData,
  ...sqlEditor,
  ...spaceSetting,
  ...price,
  ...monaco,
  ...ai,
  ...stream,
  ...userGuide,
  ...feedback,
  ...notification,
  ...redis,
  ...invite,
  ...team,
  ...plugin,
  ...license,
};
