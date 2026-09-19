import { BookOpen } from 'lucide-react';
import { createStyles } from 'antd-style';
import { splitSkillMessage } from './presentation';

const useStyles = createStyles(({ css }) => ({
  skill: css`
    display: inline-flex;
    align-items: center;
    gap: 6px;
    max-width: 100%;
    padding: 1px 8px;
    border: 1px solid rgba(255, 255, 255, 0.35);
    border-radius: 7px;
    background: rgba(0, 0, 0, 0.14);
    color: inherit;
    font-size: 12px;
    font-weight: 600;
    line-height: 22px;
    vertical-align: baseline;
    white-space: nowrap;
    svg { flex-shrink: 0; align-self: center; }
    span { min-width: 0; overflow-wrap: anywhere; white-space: normal; }
  `,
}));

export default function AgentUserMessage({ content }: { content: string }) {
  const { styles } = useStyles();
  const skill = splitSkillMessage(content);
  if (!skill) return <>{content}</>;
  return <>{skill.prefix}<span className={styles.skill} data-agent-skill={skill.name} title={skill.command}>
    <BookOpen size={13} aria-hidden="true" /><span>{skill.command}</span>
  </span>{skill.text}</>;
}
