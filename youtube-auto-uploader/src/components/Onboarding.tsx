import { useState } from 'react';
import { Bot, CheckCircle2, ExternalLink, KeyRound, Video } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { useStore } from '@/lib/store';

const slides = [
  { icon: Video, title: 'Welcome', text: 'Upload authorized videos, monitor channels, and create Shorts from one private desktop application.' },
  { icon: Bot, title: 'Local AI prerequisites', text: 'Install Ollama and pull qwen2.5:7b-instruct-q4_0. AI analysis stays on this computer.' },
  { icon: KeyRound, title: 'Connect your channel', text: 'Your Google OAuth desktop credentials and refresh token are protected by Windows Credential Manager.' },
  { icon: CheckCircle2, title: 'Ready to publish', text: 'Start with a private upload, configure auto-sync, or turn a long video into short highlights.' }
] as const;

export function Onboarding(): JSX.Element {
  const [index, setIndex] = useState(0);
  const navigate = useNavigate();
  const setSettings = useStore((state) => state.setSettings);
  const current = slides[index] ?? slides[0];
  const Icon = current.icon;
  const finish = async (destination = '/'): Promise<void> => {
    setSettings(await window.api.settings.set({ onboardingComplete: true }));
    navigate(destination);
  };
  return <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-sm grid place-items-center p-6">
    <section className="card w-full max-w-2xl p-8 text-center">
      <div className="w-20 h-20 mx-auto rounded-2xl bg-red-600/15 text-red-500 grid place-items-center"><Icon size={40}/></div>
      <div className="flex justify-center gap-2 mt-6">{slides.map((slide, position) => <span key={slide.title} className={`h-2 rounded-full ${position === index ? 'w-8 bg-red-600' : 'w-2 bg-zinc-700'}`}/>)}</div>
      <h1 className="text-2xl font-bold mt-6">{current.title}</h1><p className="muted mt-3 max-w-lg mx-auto">{current.text}</p>
      {index === 1 && <button className="btn mt-5" onClick={() => void window.api.openExternal('https://ollama.com/download/windows')}>Download Ollama <ExternalLink className="inline" size={15}/></button>}
      <div className="flex justify-between mt-8"><button className="btn" onClick={() => void finish()}>Skip</button><div className="flex gap-2">{index > 0 && <button className="btn" onClick={() => setIndex((value) => value - 1)}>Back</button>}{index < slides.length - 1 ? <button className="btn btn-primary" onClick={() => setIndex((value) => value + 1)}>Next</button> : <><button className="btn" onClick={() => void finish('/clipper')}>Open Clipper</button><button className="btn btn-primary" onClick={() => void finish('/upload')}>Upload first video</button></>}</div></div>
    </section>
  </div>;
}
