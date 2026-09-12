export const CHAT_COMMANDS = ['new', 'model', 'tools', 'copy', 'export', 'help'] as const;
export type ChatCommand = typeof CHAT_COMMANDS[number];
export type ConversationCommand = Extract<ChatCommand, 'new' | 'copy' | 'export'>;

export const parseChatCommand = (input: string): ChatCommand | undefined => {
  const name = input.trim().slice(1);
  return input.trim().startsWith('/') ? CHAT_COMMANDS.find((command) => command === name) : undefined;
};

export const isUnsupportedChatCommand = (input: string) =>
  /^\/[a-z][a-z-]*(?:\s|$)/i.test(input.trim()) && !parseChatCommand(input);
