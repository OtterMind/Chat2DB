import { useCallback, useEffect, useState } from 'react';
import { Bot, Check, Download, ExternalLink, FileVideo, Loader2, Trash2, Upload } from 'lucide-react';
import { toast } from 'sonner';
import type { ClipRow } from '../global';

const recommended = ['qwen2.5:7b-instruct-q4_0', 'llama3.1:8b-instruct', 'gemma2:9b-instruct'];

export function Clipper(): JSX.Element {
  const [url, setUrl] = useState('');
  const [file, setFile] = useState<string>();
  const [running, setRunning] = useState(false);
  const [models, setModels] = useState<string[]>([]);
  const [ollamaRunning, setOllamaRunning] = useState(false);
  const [model, setModel] = useState(recommended[0] ?? '');
  const [count, setCount] = useState(5);
  const [maxLength, setMaxLength] = useState(60);
  const [category, setCategory] = useState('Auto');
  const [aspect, setAspect] = useState('9:16');
  const [captions, setCaptions] = useState(true);
  const [smartZoom, setSmartZoom] = useState(true);
  const [music, setMusic] = useState(false);
  const [blurBackground, setBlurBackground] = useState(true);
  const [pull, setPull] = useState<{ percent: number; status: string }>();
  const [progress, setProgress] = useState<{ phase: string; percent: number; message?: string }>();
  const [clips, setClips] = useState<ClipRow[]>([]);

  const load = useCallback((): void => { void window.api.clipper.clips().then(setClips); }, []);
  const checkOllama = useCallback(async (): Promise<void> => {
    const status = await window.api.ollama.status();
    setOllamaRunning(status.running);
    setModels(status.models);
    if (status.models[0] && !status.models.includes(model)) setModel(status.models[0]);
  }, [model]);

  useEffect(() => {
    void checkOllama();
    load();
    const progressUnsubscribe = window.api.on('job:progress', (value) => setProgress(value as { phase: string; percent: number; message?: string }));
    const clipUnsubscribe = window.api.on('clip:ready', load);
    const pullUnsubscribe = window.api.on('ollama:pull-progress', (value) => setPull(value as { percent: number; status: string }));
    const doneUnsubscribe = window.api.on('job:done', () => { setRunning(false); load(); });
    return () => { progressUnsubscribe(); clipUnsubscribe(); pullUnsubscribe(); doneUnsubscribe(); };
  }, [checkOllama, load]);

  const start = async (): Promise<void> => {
    if (!ollamaRunning) { toast.error('Start Ollama before clipping'); return; }
    if (!models.includes(model)) { toast.error('Install the selected model first'); return; }
    if (!url && !file) { toast.error('Choose a YouTube URL or local video'); return; }
    try {
      setRunning(true);
      await window.api.clipper.start({ url: url || undefined, localPath: file, model, count, maxLength, category, aspect, captions, smartZoom, music, blurBackground });
      toast.success('Clipping pipeline started');
    } catch (error: unknown) {
      setRunning(false);
      toast.error(error instanceof Error ? error.message : String(error));
    }
  };

  const pullModel = async (): Promise<void> => {
    try { await window.api.ollama.pull(model); await checkOllama(); toast.success('Model installed'); }
    catch (error: unknown) { toast.error(error instanceof Error ? error.message : String(error)); }
  };

  const approveAll = async (): Promise<void> => {
    await Promise.all(clips.filter((clip) => clip.status === 'pending').map((clip) => window.api.clipper.update(clip.id, { status: 'approved' })));
    load();
  };

  const uploadApproved = async (): Promise<void> => {
    const approved = clips.filter((clip) => clip.status === 'approved');
    await Promise.all(approved.map((clip) => window.api.clipper.upload(clip.id)));
    toast.success(`${String(approved.length)} clips queued`);
    load();
  };

  return <div className="page">
    <div className="flex justify-between items-start">
      <div><h1 className="text-2xl font-bold">Local AI Clipper</h1><p className="muted mt-1">Highlights are analyzed locally through Ollama and rendered with FFmpeg.</p></div>
      <button className="btn flex gap-2" onClick={() => void window.api.openExternal('https://ollama.com/download/windows')}><ExternalLink size={17}/> Get Ollama</button>
    </div>

    <div className={`card p-4 mt-6 flex items-center justify-between ${ollamaRunning ? 'border-green-800' : 'border-amber-700'}`}>
      <div><b>{ollamaRunning ? 'Ollama is ready' : 'Ollama is not running'}</b><p className="muted text-sm">{models.length ? `${String(models.length)} model(s) installed` : 'Start Ollama and install a recommended model.'}</p></div>
      <button className="btn" onClick={() => void checkOllama()}>Retry</button>
    </div>

    <div className="card p-6 mt-5 grid grid-cols-2 gap-6">
      <div>
        <label className="label">YouTube URL</label>
        <input className="input" value={url} onChange={(event) => setUrl(event.target.value)} placeholder="https://youtube.com/watch?v=..."/>
        <div className="text-center muted my-3">or</div>
        <button className="btn w-full flex justify-center gap-2" onClick={() => void window.api.settings.pickFile().then((value) => value && setFile(value))}><FileVideo size={18}/><span className="truncate">{file ?? 'Choose local video'}</span></button>
      </div>
      <div className="space-y-4">
        <div><label className="label">Ollama model</label><div className="flex gap-2"><select className="input" value={model} onChange={(event) => setModel(event.target.value)}>{[...new Set([...models, ...recommended])].map((name) => <option key={name} value={name}>{models.includes(name) ? '★ ' : ''}{name}</option>)}</select>{!models.includes(model) && <button className="btn" onClick={() => void pullModel()}>Install</button>}</div></div>
        {pull && <div><div className="flex justify-between text-xs muted"><span>{pull.status}</span><span>{pull.percent}%</span></div><div className="progress mt-1"><div style={{ width: `${String(pull.percent)}%` }}/></div></div>}
        <div><label className="label">Clip count: {count}</label><input type="range" min="3" max="20" value={count} onChange={(event) => setCount(Number(event.target.value))} className="w-full accent-red-600"/></div>
        <div className="grid grid-cols-3 gap-2">
          <label><span className="label">Max length</span><select className="input" value={maxLength} onChange={(event) => setMaxLength(Number(event.target.value))}>{[15,30,60,90].map((seconds) => <option key={seconds} value={seconds}>{seconds}s</option>)}</select></label>
          <label><span className="label">Category</span><select className="input" value={category} onChange={(event) => setCategory(event.target.value)}>{['Auto','Sports','Gaming','Educational','Vlog','Comedy','News','Challenge'].map((value) => <option key={value}>{value}</option>)}</select></label>
          <label><span className="label">Aspect</span><select className="input" value={aspect} onChange={(event) => setAspect(event.target.value)}>{['9:16','1:1','16:9'].map((value) => <option key={value}>{value}</option>)}</select></label>
        </div>
        <div className="grid grid-cols-2 gap-2 text-sm">
          <Toggle label="Auto captions" value={captions} set={setCaptions}/><Toggle label="Smart zoom" value={smartZoom} set={setSmartZoom}/><Toggle label="Background music" value={music} set={setMusic}/><Toggle label="Blur background" value={blurBackground} set={setBlurBackground}/>
        </div>
        <button disabled={running} className="btn btn-primary w-full flex justify-center gap-2 disabled:opacity-50" onClick={() => void start()}>{running ? <Loader2 className="animate-spin" size={18}/> : <Bot size={18}/>} Find viral moments</button>
      </div>
    </div>

    {progress && <div className="card p-5 mt-5">
      <div className="flex justify-between mb-3"><span className="capitalize flex gap-2"><Loader2 className="animate-spin" size={18}/>{progress.phase}: {progress.message}</span><span>{progress.percent}%</span></div>
      <div className="progress"><div style={{ width: `${String(progress.percent)}%` }}/></div>
    </div>}

    {clips.length > 0 && <div className="flex justify-end gap-2 mt-6"><button className="btn" onClick={() => void approveAll()}><Check className="inline" size={16}/> Approve all</button><button className="btn btn-primary" onClick={() => void uploadApproved()}><Upload className="inline" size={16}/> Upload approved</button></div>}
    <div className="grid grid-cols-[repeat(auto-fit,minmax(280px,1fr))] gap-5 mt-4">{clips.map((clip) => <article className="card overflow-hidden" key={clip.id}>
      <video src={`media://file?path=${encodeURIComponent(clip.local_path)}`} poster={`media://file?path=${encodeURIComponent(clip.thumbnail_path)}`} controls className="w-full aspect-[9/16] max-h-[420px] bg-black"/>
      <div className="p-4"><div className="flex justify-between"><span className="badge bg-red-950 text-red-300">Score {clip.score}/10</span><span className="badge">{clip.status}</span></div><input className="input mt-3" defaultValue={clip.suggested_title} onBlur={(event) => void window.api.clipper.update(clip.id, { suggested_title: event.target.value })}/><div className="flex gap-2 mt-3"><button className="btn btn-primary flex-1" onClick={() => void window.api.clipper.update(clip.id, { status: 'approved' }).then(load)}><Check className="inline" size={16}/> Approve</button><button className="btn !p-2" aria-label="Export clip" onClick={() => void window.api.clipper.export(clip.id).then((value) => value && toast.success(`Saved to ${value}`))}><Download size={16}/></button><button className="btn !p-2" aria-label="Discard clip" onClick={() => void window.api.clipper.update(clip.id, { status: 'discarded' }).then(load)}><Trash2 size={16}/></button></div></div>
    </article>)}</div>
  </div>;
}

function Toggle({ label, value, set }: { label: string; value: boolean; set: (value: boolean) => void }): JSX.Element {
  return <label className="flex items-center gap-2 rounded border border-border p-2"><input type="checkbox" className="accent-red-600" checked={value} onChange={(event) => set(event.target.checked)}/>{label}</label>;
}
