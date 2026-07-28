import assert from 'node:assert/strict';
import type { ChatVO } from '@/typings/chat';
import { applyChatInfoUpdate } from './chatInfoUpdate';

const firstChat: ChatVO = { id: 1, title: 'Before rename' };
const secondChat: ChatVO = { id: 2, title: 'Other chat' };
const originalChatList = [firstChat, secondChat];
const originalCurrentChat = {
  workspace: null,
  dashboard: null,
  chat: firstChat,
};

const updatedState = applyChatInfoUpdate(
  {
    chatList: originalChatList,
    currentChat: originalCurrentChat,
  },
  'chat',
  { id: 1, title: 'After rename' },
);

assert.equal(updatedState.currentChat.chat?.title, 'After rename');
assert.notEqual(updatedState.chatList, originalChatList);
assert.notEqual(updatedState.chatList[0], firstChat);
assert.equal(updatedState.chatList[0].title, 'After rename');
assert.equal(updatedState.chatList[1], secondChat);
assert.deepEqual(originalChatList, [firstChat, secondChat]);
assert.equal('id' in updatedState.currentChat, false);
assert.equal('title' in updatedState.currentChat, false);

const currentOnlyState = applyChatInfoUpdate(
  {
    chatList: [],
    currentChat: originalCurrentChat,
  },
  'chat',
  { id: 1, title: 'Renamed before list load' },
);

assert.equal(currentOnlyState.currentChat.chat?.title, 'Renamed before list load');
assert.deepEqual(currentOnlyState.chatList, []);

console.log('Chat metadata update tests passed');
