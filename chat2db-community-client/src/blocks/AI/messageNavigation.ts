export interface MessageNavigationSource {
  id: string;
  role: string;
  content?: string;
}

export interface UserMessageNavigationItem {
  id: string;
  index: number;
  label: string;
  assistantPreview?: string;
}

function normalizePreview(content?: string) {
  return content
    ?.trim()
    .replace(/```[\s\S]*?```/g, ' code ')
    .replace(/[#>*`]|\[|\]/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

export function getMessageNavigationRailWidth(itemIndex: number, selectedIndex: number) {
  const minimumWidth = 8;
  if (selectedIndex < 0) {
    return minimumWidth;
  }
  const distance = Math.abs(itemIndex - selectedIndex);
  const variance = 1.7 * 1.7;
  return Math.round(minimumWidth + 22 * Math.exp(-(distance * distance) / (2 * variance)));
}

export function buildUserMessageNavigationItems(
  messages: MessageNavigationSource[],
  emptyLabel: string,
): UserMessageNavigationItem[] {
  const items: UserMessageNavigationItem[] = [];

  messages.forEach((message) => {
    if (message.role === 'user') {
      items.push({
        id: message.id,
        index: items.length + 1,
        label: normalizePreview(message.content) || emptyLabel,
      });
      return;
    }

    const currentItem = items[items.length - 1];
    if (message.role === 'assistant' && currentItem && !currentItem.assistantPreview) {
      currentItem.assistantPreview = normalizePreview(message.content);
    }
  });

  return items;
}
