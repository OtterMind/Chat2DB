import type { ReactNode } from 'react';
import { Alert } from 'antd';
import QuestionCard from '@/components/QuestionCard';
import type { QuestionResponse } from '@/types/question';
import type { AgentApprovalDecision, AgentApprovalItem, AgentTimelineEntry } from '../../agentEvents';
import type { AgentQuestionItem } from '../../agentQuestions';
import type { AgentChart } from '../../agentCharts';
import AgentApprovalCard from '../AgentApprovalCard';
import AgentChartCard from '../AgentChartCard';
import AgentTraceGroup from './AgentTraceGroup';
import { getAgentActivity } from './presentation';
import { timelineSections } from './timelineSections';

export interface AgentTimelineProps {
  sessionId?: string;
  entries: AgentTimelineEntry[];
  runId?: string;
  active?: boolean;
  cancelling?: boolean;
  status?: 'failed' | 'cancelled' | 'unknown';
  onInspectTools?: () => void;
  charts: AgentChart[];
  approvals: AgentApprovalItem[];
  questions: AgentQuestionItem[];
  renderMarkdown: (content: string) => ReactNode;
  onDecideApproval: (approval: AgentApprovalItem, decision: AgentApprovalDecision) => Promise<void>;
  onAnswerQuestion: (question: AgentQuestionItem, answer?: QuestionResponse) => Promise<void>;
}

export default function AgentTimeline(props: AgentTimelineProps) {
  const { entries, runId } = props;
  const currentActivity = getAgentActivity(
    !!props.active, entries, runId, props.questions, props.approvals, props.cancelling,
  );
  // Waiting is already visible on the question/approval card.
  const activity = currentActivity?.kind === 'question' || currentActivity?.kind === 'approval'
    ? undefined : currentActivity;
  const charts = new Map(props.charts.filter((chart) => chart.runId === runId).map((chart) => [chart.id, chart]));
  const sections = timelineSections(entries);
  const lastSection = sections.at(-1);
  const nodes: ReactNode[] = [];
  sections.forEach((entry) => {
    let content: ReactNode;
    // The discriminated union covers every event kind.
    switch (entry.kind) {
      case 'tools':
        content = <AgentTraceGroup sessionId={props.sessionId} entries={entry.entries} runActive={props.active}
          activity={entry === lastSection ? activity : undefined}
          status={entry === lastSection ? props.status : undefined} onInspect={props.onInspectTools}
                  />;
        break;
      case 'text':
        content = props.renderMarkdown(entry.text);
        break;
      case 'trace':
        content = <Alert type="error" showIcon message={entry.trace.content} />;
        break;
      case 'chart': {
        const chart = charts.get(entry.id);
        content = chart && <AgentChartCard chart={chart} />;
        break;
      }
      case 'question': {
        const question = props.questions.find((item) => item.id === entry.id && item.runId === runId);
        content = question && <QuestionCard question={question.question} options={question.options}
          status={question.status} answer={question.answer}
          onAnswer={(answer) => props.onAnswerQuestion(question, answer)}
          onCancel={() => props.onAnswerQuestion(question)}
                              />;
        break;
      }
      case 'approval': {
        const approval = props.approvals.find((item) => item.id === entry.id && item.runId === runId);
        content = approval && <AgentApprovalCard approval={approval}
          onDecide={(decision) => props.onDecideApproval(approval, decision)}
                              />;
        break;
      }
      default: break;
    }
    if (content) nodes.push(<div key={entry.sequence} data-agent-sequence={entry.sequence}>{content}</div>);
  });
  return <>{nodes}{lastSection?.kind !== 'tools' && (activity || props.status) &&
    <AgentTraceGroup key="progress" entries={[]} activity={activity} status={props.status} />}</>;
}
