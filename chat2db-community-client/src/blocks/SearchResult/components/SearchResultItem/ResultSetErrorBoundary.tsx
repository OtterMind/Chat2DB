import { Component, ErrorInfo, Fragment, ReactNode } from 'react';

interface IProps {
  children: ReactNode;
  fallback: (error: Error, retry: () => void) => ReactNode;
  resetKey: string;
}

interface IState {
  error: Error | null;
  retryKey: number;
}

export default class ResultSetErrorBoundary extends Component<IProps, IState> {
  static getDerivedStateFromError(error: Error): Partial<IState> {
    return { error };
  }

  state: IState = {
    error: null,
    retryKey: 0,
  };

  componentDidUpdate(previousProps: IProps) {
    if (previousProps.resetKey !== this.props.resetKey && this.state.error) {
      this.setState({ error: null });
    }
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    console.error('Result set rendering failed', error, errorInfo);
  }

  retry = () => {
    this.setState(({ retryKey }) => ({
      error: null,
      retryKey: retryKey + 1,
    }));
  };

  render() {
    const { children, fallback } = this.props;
    const { error, retryKey } = this.state;
    if (error) {
      return fallback(error, this.retry);
    }
    return <Fragment key={retryKey}>{children}</Fragment>;
  }
}
