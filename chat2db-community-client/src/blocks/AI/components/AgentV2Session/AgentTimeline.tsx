import type { ReactNode } from 'react';
import { Alert } from 'antd';
import QuestionCard from '@/components/QuestionCard';
import type { QuestionResponse } from '@/types/question';
import type { AgentApprovalItem, AgentTimelineEntry, AgentTraceEntry } from '../../agentEvents';
import type { AgentQuestionItem } from '../../agentQuestions';
import type { AgentChart } from '../../agentCharts';
import AgentApprovalCard from '../AgentApprovalCard';
import AgentChartCard from '../AgentChartCard';
import AgentTraceGroup from './AgentTraceGroup';
import { getAgentActivity } from './presentation';

export interface AgentTimelineProps {
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
  onDecideApproval: (approval: AgentApprovalItem, approved: boolean) => Promise<void>;
  onAnswerQuestion: (question: AgentQuestionItem, answer?: QuestionResponse) => Promise<void>;
}

export default function AgentTimeline(props: AgentTimelineProps) {
  const { entries, runId } = props;
  const activity = getAgentActivity(!!props.active, entries, runId, props.questions, props.approvals, props.cancelling);
  const charts = new Map(props.charts.filter((chart) => chart.runId === runId).map((chart) => [chart.id, chart]));
  const traces: AgentTraceEntry[] = entries.flatMap((entry) => entry.kind === 'trace'
    && (entry.trace.type === 'tool_call' || entry.trace.type === 'tool_result') ? [entry.trace] : []);
  const nodes: ReactNode[] = [];
  entries.forEach((entry) => {
    if (entry.kind === 'trace' && entry.trace.type !== 'error') return;
    let content: ReactNode;
    // The discriminated union covers every event kind.
    switch (entry.kind) {
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
          onDecide={(approved) => props.onDecideApproval(approval, approved)}
                              />;
        break;
      }
      default: break;
    }
    if (content) nodes.push(<div key={entry.sequence} data-agent-sequence={entry.sequence}>{content}</div>);
  });
  return <>{nodes}<AgentTraceGroup key="progress" entries={traces} activity={activity}
    status={props.status} onInspect={props.onInspectTools}
                  /></>;
}
