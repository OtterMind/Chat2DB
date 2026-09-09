import assert from 'node:assert/strict';
import zh from '@/i18n/zh-CN/setting';
import en from '@/i18n/en-US/setting';
import ja from '@/i18n/ja-JP/setting';
import ko from '@/i18n/ko-KR/setting';
import es from '@/i18n/es-ES/setting';
import { toolDescription } from './model';
import type { AgentToolState } from '@/service/agent';

const tool: AgentToolState = { name: 'bash', description: 'Bash', category: 'BUILTIN', status: 'DISABLED' };
let messages: Record<string, string> = zh;
const translate = (key: string) => messages[key] || key;
assert.equal(toolDescription(tool, translate), zh['setting.agent.tool.bash']);
messages = en;
assert.equal(toolDescription(tool, translate), en['setting.agent.tool.bash']);
assert.notEqual(toolDescription(tool, translate), zh['setting.agent.tool.bash']);
for (const locale of [zh, en, ja, ko, es]) {
  assert.ok(locale['setting.agent.toolStatus.UNAVAILABLE']);
  assert.ok(locale['setting.agent.workingDirectory.hint']);
  assert.ok(locale['setting.agent.workingDirectory.choose']);
  assert.ok(locale['setting.agent.workingDirectory.parent']);
  assert.ok(locale['setting.agent.tool.execute_sql']);
}
assert.equal(toolDescription({ ...tool, name: 'custom_tool', description: 'Custom tool description' }, translate),
  'Custom tool description');
