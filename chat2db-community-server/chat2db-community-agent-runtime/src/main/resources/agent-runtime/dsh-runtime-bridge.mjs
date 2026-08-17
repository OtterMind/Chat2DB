#!/usr/bin/env node

import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { randomUUID } from 'node:crypto';

let hostProcess;
let baseUrl;
let muxSocket;
let hostSocket;
let activeSessionId;
let completedTurn;
let finalText = '';
let latestUsage = {};

function send(message) {
  process.stdout.write(`${JSON.stringify(message)}\n`);
}

function result(id, value) {
  send({ jsonrpc: '2.0', id, result: value });
}

function fail(id, code, message) {
  send({ jsonrpc: '2.0', id, error: { code, message } });
}

function notify(method, params) {
  send({ jsonrpc: '2.0', method, params });
}

function rpcEnvelope(method, payload) {
  return { type: 'client-request', rpcId: randomUUID(), method, payload };
}

async function call(method, payload = {}) {
  const envelope = rpcEnvelope(method, payload);
  const response = await fetch(`${baseUrl}/api/${method}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(envelope),
  });
  if (!response.ok) throw new Error(`DSH ${method} transport failed with HTTP ${response.status}`);
  const body = await response.json();
  if (body.rpcId !== envelope.rpcId) throw new Error(`DSH ${method} returned a mismatched rpcId`);
  if (!body.result?.ok) throw new Error(body.result?.error?.message || `DSH ${method} failed`);
  return body.result.value;
}

async function respond(rpcId, value) {
  const response = await fetch(`${baseUrl}/api/respond`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ type: 'client-response', rpcId, result: { ok: true, value } }),
  });
  if (!response.ok) throw new Error(`DSH response transport failed with HTTP ${response.status}`);
  return response.json();
}

function openSocket(path, onFrame) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(`${baseUrl.replace(/^http/, 'ws')}${path}`);
    socket.addEventListener('open', () => resolve(socket), { once: true });
    socket.addEventListener('error', () => reject(new Error(`DSH WebSocket failed: ${path}`)), { once: true });
    socket.addEventListener('message', event => {
      try {
        const message = JSON.parse(String(event.data));
        onFrame(message);
      } catch (error) {
        notify('bridge/error', { message: `Invalid DSH event frame: ${error.message}` });
      }
    });
    socket.addEventListener('close', () => {
      if (hostProcess && hostProcess.exitCode === null) {
        notify('bridge/error', { message: `DSH WebSocket closed unexpectedly: ${path}` });
      }
    });
  });
}

function messageText(message) {
  return (message?.content || []).filter(block => block?.type === 'text')
    .map(block => block.text || '').join('');
}

function handleSessionEvent(event, view) {
  const data = event?.data || {};
  switch (event?.type) {
    case 'assistant/chunk': {
      const chunk = data.chunk || {};
      if (chunk.type === 'text-delta') {
        finalText += chunk.text || '';
        notify('runtime/event', { type: 'MESSAGE_DELTA', content: chunk.text || '', payload: event });
      } else if (chunk.type === 'reasoning-delta') {
        notify('runtime/event', { type: 'REASONING_DELTA', content: chunk.text || '', payload: event });
      }
      break;
    }
    case 'assistant/message': {
      if (!finalText) finalText = messageText(data.message);
      if (data.usage) {
        latestUsage = data.usage;
        notify('runtime/event', { type: 'USAGE', content: 'DSH token usage updated', payload: data.usage });
      }
      break;
    }
    case 'tool/call':
      notify('runtime/event', { type: 'TOOL_CALL', content: `${data.name || 'tool'}: ${data.callId || ''}`, payload: { event, view } });
      break;
    case 'tool/result':
      notify('runtime/event', { type: 'TOOL_RESULT', content: data.message ? messageText(data.message) : '', payload: { event, view } });
      break;
    case 'turn/start':
      notify('runtime/turn-started', { sessionId: activeSessionId, turnId: String(data.turn) });
      break;
    case 'turn/end':
      completedTurn?.({ turnId: String(data.turn), reason: data.reason || { kind: 'error' } });
      break;
    default:
      break;
  }
}

async function handleMux(message) {
  const frame = message?.payload;
  if (!frame || (frame.sessionId && frame.sessionId !== activeSessionId)) return;
  if (frame.type === 'session/event') {
    handleSessionEvent(frame.event, frame.view);
  } else if (frame.type === 'approval/requested') {
    notify('runtime/approval-requested', {
      rpcId: message.rpcId,
      sessionId: frame.sessionId,
      approvalId: frame.approvalId,
      toolName: frame.toolName,
      callId: frame.callId,
      reason: frame.reason,
    });
  } else if (frame.type === 'stream/error') {
    notify('bridge/error', { message: frame.error?.message || 'DSH event stream failed' });
  }
}

function waitForUrl(child) {
  return new Promise((resolve, reject) => {
    let buffer = '';
    const timeout = setTimeout(() => reject(new Error('DSH Web Host startup timed out')), 30000);
    const inspect = chunk => {
      buffer += chunk.toString('utf8');
      const match = buffer.match(/dsh web:\s+(http:\/\/127\.0\.0\.1:\d+)/);
      if (match) {
        clearTimeout(timeout);
        resolve(match[1]);
      }
      if (buffer.length > 65536) buffer = buffer.slice(-32768);
    };
    child.stdout.on('data', inspect);
    child.stderr.on('data', chunk => process.stderr.write(chunk));
    child.once('exit', code => {
      clearTimeout(timeout);
      reject(new Error(`DSH Web Host exited before startup (code ${code})`));
    });
  });
}

async function initialize(params) {
  if (hostProcess) throw new Error('DSH bridge is already initialized');
  const args = ['--profile', 'web'];
  for (const patch of params.patches || []) args.push('--patch', patch);
  args.push('--host', '127.0.0.1', '--port', '0');
  for (const argument of params.customArguments || []) args.push(argument);
  hostProcess = spawn(params.executable, args, {
    cwd: params.cwd,
    env: { ...process.env, DSH_CWD: params.cwd },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  baseUrl = await waitForUrl(hostProcess);
  muxSocket = await openSocket('/api/events.mux', handleMux);
  hostSocket = await openSocket('/api/events.host', message => {
    const frame = message?.payload;
    if (frame?.type === 'host/agent-error' && frame.sessionId === activeSessionId) {
      notify('bridge/error', { message: frame.message || 'DSH Agent failed' });
    }
  });
  await call('host.describe');
  return { protocolVersion: 'chat2db-dsh-bridge-v1' };
}

async function startTurn(params) {
  finalText = '';
  latestUsage = {};
  if (params.resumeSessionId) {
    activeSessionId = params.resumeSessionId;
    await call('session.history', { sessionId: activeSessionId, maxMessages: 1 });
  } else {
    const created = await call('session.create', { cwd: params.cwd });
    activeSessionId = created.sessionId;
  }
  notify('runtime/session-updated', { sessionId: activeSessionId, resumed: Boolean(params.resumeSessionId) });
  const completion = new Promise(resolve => { completedTurn = resolve; });
  await call('session.prompt', {
    sessionId: activeSessionId,
    mode: 'queue',
    content: [{ type: 'text', text: params.prompt }],
  });
  const turn = await completion;
  completedTurn = undefined;
  const kind = turn.reason?.kind;
  if (kind !== 'completed') {
    const error = new Error(turn.reason?.error?.message || `DSH turn ended with ${kind || 'unknown status'}`);
    error.code = kind === 'aborted' ? 'CANCELLED' : 'TURN_FAILED';
    throw error;
  }
  return { sessionId: activeSessionId, turnId: turn.turnId, finalResponse: finalText, usage: latestUsage };
}

async function handleRequest(message) {
  try {
    switch (message.method) {
      case 'initialize':
        result(message.id, await initialize(message.params || {}));
        break;
      case 'turn/start':
        result(message.id, await startTurn(message.params || {}));
        break;
      case 'turn/cancel':
        if (activeSessionId) await call('session.cancel', { sessionId: activeSessionId });
        result(message.id, { accepted: true });
        break;
      case 'approval/respond': {
        const params = message.params || {};
        const receipt = await respond(params.rpcId, {
          sessionId: params.sessionId,
          approvalId: params.approvalId,
          outcome: params.approved ? 'allowed-once' : 'rejected',
        });
        result(message.id, receipt);
        break;
      }
      case 'shutdown':
        result(message.id, { accepted: true });
        shutdown();
        break;
      default:
        fail(message.id, -32601, `Unknown DSH bridge method: ${message.method}`);
    }
  } catch (error) {
    fail(message.id, error.code === 'CANCELLED' ? -32800 : -32000, error.message || String(error));
  }
}

function shutdown() {
  try { muxSocket?.close(); } catch {}
  try { hostSocket?.close(); } catch {}
  if (hostProcess?.exitCode === null) hostProcess.kill('SIGTERM');
  setTimeout(() => {
    if (hostProcess?.exitCode === null) hostProcess.kill('SIGKILL');
    process.exit(0);
  }, 1500).unref();
}

createInterface({ input: process.stdin, crlfDelay: Infinity }).on('line', line => {
  if (!line.trim()) return;
  try {
    const message = JSON.parse(line);
    if (message.jsonrpc !== '2.0' || message.id === undefined || !message.method) {
      throw new Error('invalid JSON-RPC request');
    }
    void handleRequest(message);
  } catch (error) {
    notify('bridge/error', { message: error.message || String(error) });
  }
}).on('close', shutdown);

process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
