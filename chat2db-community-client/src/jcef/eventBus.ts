const listeners = {};

export enum JavaPushActionType {
  AI_SSE_MESSAGE = 'ai_sse_message', // AI SSE messages
  OPEN_FILE = 'open_file', // open file
  DRAG_FILE = 'drag_file', // Drag and drop files
  AUTO_PROGRESS = 'update_progress', // Automatic updates
  STARTUP_COMPLETE = 'startup_complete', // Startup completed
  IS_WINDOW_MAXIMIZED = 'is_window_maximized', // Whether the window is maximized
  WINDOW_FULL_SCREEN_CHANGED = 'window_full_screen_changed', // Native full screen state changed
  OSS_LOGIN = 'oss_login', // OSS login requires jumping to the homepage
  SQL_EXECUTION_EVENT = 'sql_execution_event', // SQL execution events
  TERMINAL_OUTPUT = 'terminal_output', // Integrated terminal output
  TERMINAL_EXIT = 'terminal_exit', // Integrated terminal exit
  APP_EXIT_REQUESTED = 'app_exit_requested', // Native application exit requested
  APP_EXIT_RESULT = 'app_exit_result', // Native application exit request result
}

export const JcefEventBus = {
  on(eventType, callback) {
    if (!listeners[eventType]) {
      listeners[eventType] = [];
    }
    listeners[eventType].push(callback);
  },

  off(eventType, callback?) {
    if (!callback) {
      delete listeners[eventType];
      return;
    }
    listeners[eventType] = listeners[eventType]?.filter((listener) => listener !== callback);
    if (!listeners[eventType]?.length) {
      delete listeners[eventType];
    }
  },

  publish(eventType, data) {
    if (listeners[eventType]) {
      listeners[eventType].forEach((callback) => callback(data));
    }
  },
};
