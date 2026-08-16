import { FC } from 'react';
import ChatMenuList from './chatMenuList';
import ChatContainer from './chatContainer';
import SplitPane from 'react-split-pane';

export interface AIChatProps {}

const AIChat: FC<AIChatProps> = () => (
  <SplitPane size={220} pane2Style={{ width: '0px' }} minSize={180} maxSize={400} split="vertical" primary="first">
    <ChatMenuList />
    <ChatContainer isPage />
  </SplitPane>
);

export default AIChat;
