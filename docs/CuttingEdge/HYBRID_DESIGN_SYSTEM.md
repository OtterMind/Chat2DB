# نقشه‌ی مهندسی کامل — Cutting Edge v0.9.34
# Hybrid Design System + Code Review + Feature Roadmap

> تاریخ: 2026-08-27
> شاخه: `arena/01a04055-chat2db`
> نگهدارنده: Yousef Ghorbanian
> وضعیت: Specification (هیچ تغییری در کد فعلی اعمال نشده)
> هدف: مبنای تصمیم‌گیری برای 1.0 و بعد از آن

---

## فهرست

- **بخش A:** بررسی کد و ۱۲ پیشنهاد دیباگ (debug)
- **بخش B:** ۱۰ ایده‌ی «خفن‌تر کردن» برنامه
- **بخش C:** سیستم طراحی Hybrid (۹۰٪ Minimal + ۹٪ Cyberpunk + ۱٪ Glass)
  - C1: Design Tokens
  - C2: Typography System
  - C3: Color & State
  - C4: Spacing & Layout
  - C5: Motion & Micro-interactions
  - C6: Component Library (Scoreboard, Timeline, TaskDock, AIAssistantFAB, ExportButton, EmptyState, ...)
  - C7: Page-by-Page Specification (Launcher, Studio, StyleMatch, Settings, ExportQueue, ExportComplete, ClipReview, Dashboard, Doctor, JobDetail, Uploads, NewJob, Home)
  - C8: State Machine (Empty / Loading / Error / Success / Skeleton)
  - C9: Accessibility (a11y / WCAG 2.1 AA)
  - C10: Internationalization (i18n) — فارسی + انگلیسی + RTL
  - C11: Dark Mode (فقط dark؛ چون دسکتاپ هست)
  - C12: Performance Budget
  - C13: Migration Strategy (از تم فعلی به Hybrid)
  - C14: Testing Strategy
- **بخش D:** نقشه‌ی راه ۱.۰ → ۲.۰
- **بخش E:** اصول طراحی (۱۰ قانون طلایی)
- **بخش F:** ضمیمه‌ها (لیست فایل‌ها، commit strategy)

---

# بخش A — بررسی کد و ۱۲ پیشنهاد دیباگ

این بخش بر اساس خواندن مستقیم کد در `/home/user/Chat2DB/ce-app/` نوشته شده. کل کد خوانده شده: **۹,۲۵۳ خط**.

### جمع‌بندی کلی

این پروژه از نظر **معماری، مستندسازی، و امنیت منطقی** جزو بهترین کدهایی‌ست که دیده‌ام. هر فایل یک ماژول مستقل با docstring آموزنده دارد، خطاها **با دلیل مکتوب** به لایه بالا منتقل می‌شوند، و هرجا «ممکن است اشتباه شود» یک `note`، یک `STATE.md` رفرنس یا یک `tests/test_xxx.py` وجود دارد. این نشانه‌ی یک تیم بزرگ نیست، نشانه‌ی یک **مهندس دقیق** است.

---

## A1. 🔴 بحرانی — `WebSocket ConnectionManager` نشتی دارد

**فایل:** `backend/app/websocket/job_events.py`
**خط:** ۹
**شدت:** بحرانی (production crash محتمل)

**کد فعلی:**
```python
async def broadcast(self, message: dict):
    for c in list(self._connections):  # ✓ copy گرفته
        try: await c.send_json(message)
        except Exception: self.disconnect(c)  # ⚠️ ولی همین خط مشکل دارد
```

**مشکل ۱ (Race):** اگر `send_json` با `RuntimeError` فالبک شود (مثلاً client disconnect شد ولی exception raise نشده)، connection در `_connections` باقی می‌ماند و broadcast بعدی روی آن hang می‌کند.

**مشکل ۲ (Task leak):** `broadcast` در حلقه await می‌کند — اگر ۲۰ client داشته باشید و یکی کُند باشد، همه منتظر آن می‌مانند. broadcast باید **concurrent** باشد.

**راه‌حل پیشنهادی:**
- استفاده از `asyncio.gather(..., return_exceptions=True)` برای ارسال همزمان
- استفاده از `asyncio.wait_for(..., timeout=2.0)` برای هر client
- نگهداری `set` به جای `list` + `asyncio.Lock` برای thread-safety
- پاک کردن dead connections در یک transaction واحد

**اولویت:** P0 — قبل از 1.0 باید حل شود.

---

## A2. 🔴 بحرانی — `runtime_packages.install` بدون timeout واقعی

**فایل:** `backend/core/runtime_packages.py`
**خط:** ۵۸
**شدت:** بحرانی (UI hang)

**کد فعلی:**
```python
process = subprocess.Popen([...])
# حلقه‌ای که فقط روی stdout صبر می‌کند
for line in process.stdout:
    ...
code = process.wait()  # ← اگر hang شود، هیچ‌وقت صدا زده نمی‌شود
```

**مشکل:** اگر pip روی شبکه‌ای hang کند، این تابع هیچ‌وقت `code = process.wait()` را صدا نمی‌زند. thread زنده می‌ماند و کاربر cancel هم نمی‌تواند بکند (cancellation فقط در `engine/cancellation.py` است).

**راه‌حل:**
- استفاده از `cancellation.run` (همان helper که در بقیه engine‌ها هست)
- timeout واقعی (`subprocess.Popen` با `timeout=` در `communicate()`)
- انتشار `cancelled` exception به caller

**اولویت:** P0.

---

## A3. 🔴 بحرانی — `database.py` بدون thread-safety

**فایل:** `backend/app/database.py`
**خط:** ۱۳
**شدت:** بحرانی (data race در load بالا)

**کد فعلی:**
```python
conn = sqlite3.connect(str(self.db_path), check_same_thread=False)
conn.row_factory = sqlite3.Row
```

**مشکل:** `check_same_thread=False` اجازه می‌دهد چند thread همزمان به یک connection بنویسند. اگر دو request همزمان بیایند و `commit()` در میانه فراخوانی شود، یکی race می‌برد و داده‌ی آن یکی گم می‌شود.

**راه‌حل:**
- `threading.local()` + یک connection per thread
- context manager `@transaction` با `BEGIN IMMEDIATE` + `COMMIT/ROLLBACK`
- `PRAGMA busy_timeout=5000` برای کاهش SQLITE_BUSY
- WAL mode (که الان هست — خوب)

**اولویت:** P0.

---

## A4. 🔴 بحرانی — روترها بدون Path validation

**فایل:** `backend/app/routers/captions.py` (و ۱۱ روتر دیگر)
**خط:** متعدد
**شدت:** بحرانی (security vulnerability)

**کد فعلی:**
```python
class TranscribeRequest(BaseModel):
    path: str  # ← هیچ validation ندارد

@router.post("/transcribe")
async def transcribe(payload: TranscribeRequest):
    media = Path(payload.path)  # ← مستقیم استفاده می‌شود
    if not media.exists():
        raise HTTPException(...)
```

**مشکل:** `payload.path` بدون validation به `Path(payload.path)` می‌رود. یک request مخرب می‌تواند `path="C:/Windows/System32/..."` بفرستد.

**راه‌حل:**
- Pydantic validator با `_safe_path` که:
  - Path را `resolve()` کند
  - بررسی کند داخل `~/CuttingEdge` باشد (`is_relative_to`)
  - بررسی کند فایل وجود داشته باشد
  - بررسی کند از یک size limit (مثلاً ۴GB) تجاوز نکند
- همین pattern به همه‌ی ۱۲ روتر اعمال شود: `media`, `analyze`, `proxy`, `reframe`, `render`, `uploads`, `clips`, `projects`, `assistant`, `style`, `ai`, `tasks`

**اولویت:** P0 (security).

---

## A5. 🟠 مهم — `Race` در planners: Ollama warm-up

**فایل:** `backend/core/brain/planners.py`
**خط:** ۲۰۰ (`ollama_plan`)
**شدت:** مهم (UX ضعیف)

**کد فعلی:**
```python
response = requests.post(f"{OLLAMA_URL}/api/generate", ..., timeout=timeout)
```

**مشکل:** `timeout=120` یعنی اگر Ollama در حال load مدل باشد (مخصوصاً اولین بار، ممکن است ۲-۳ دقیقه طول بکشد)، کل request می‌میرد و در scoreboard ثبت می‌شود. روی خروجی race تأثیری ندارد، ولی UX ضعیف است.

**راه‌حل:**
- یک مرحله‌ی warm-up با timeout کوتاه (۱۰s) قبل از request اصلی
- retry policy برای timeout
- در UI، وقتی `note == "warmup failed"` بود، یک chip زرد: «مدل در حال load، یک بار دیگر امتحان کن»
- در `scoreboard` یک status جدید: `"pending"` (نه `0.00`)

**اولویت:** P1.

---

## A6. 🟠 مهم — `Timeline.tsx` listener re-creation در هر فریم

**فایل:** `frontend/src/editor/Timeline.tsx`
**خط:** ۱۰۰-۱۴۰
**شدت:** مهم (UX bug)

**کد فعلی:**
```typescript
useEffect(() => {
  if (!drag) return
  const onMove = (e: PointerEvent) => { ... }
  window.addEventListener('pointermove', onMove)
  return () => { ... }
}, [drag, clips, magnets, ...])  // ← clips و magnets در dependency
```

**مشکل:** هر تغییر clip (مثلاً تریم ۱ فریم)، effect teardown می‌شود و دوباره listener اضافه می‌شود. در یک drag طولانی اگر state تغییر کند (snapping guide)، listener از کار می‌افتد.

**راه‌حل:**
- الگوی «latest ref»: منطق `onMove` را به یک `useRef` از state منتقل کن
- `useEffect` فقط به `[drag]` وابسته باشد
- `stateRef.current` در ابتدای handler خوانده شود

**اولویت:** P1.

---

## A7. 🟠 مهم — `PreviewMonitor` RAF در background tab

**فایل:** `frontend/src/editor/PreviewMonitor.tsx`
**خط:** ۲۸۰
**شدت:** مهم (UX bug)

**کد فعلی:**
```typescript
useEffect(() => {
  if (!playing) return
  const tick = () => {
    const now = performance.now()
    const wall = (now - previous) / 1000
    previous = now
    let next = state.playhead + wall  // ← اگر background tab باشد، wall = 1s
    ...
    state.setPlayhead(next)  // ← جهش بزرگ
    frame = requestAnimationFrame(tick)
  }
})
```

**مشکل:** اگر کاربر در حین پخش Tab را عوض کند، `requestAnimationFrame` در background ~1Hz می‌شود و playhead عقب می‌ماند. وقتی برگردد، یک جهش بزرگ ثبت می‌شود.

**راه‌حل:**
- `if (wall > 0.25) return` (skip frame بیش از ۲۵۰ms)
- `document.visibilityState` listener: وقتی tab مخفی است، `playing = false` (pause)
- وقتی برگشت، از wall clock ۰ شروع کند نه از jump

**اولویت:** P1.

---

## A8. 🟠 مهم — `WebSocket` reconnect ساده

**فایل:** `frontend/src/api/websocket.ts`
**خط:** ۳۱
**شدت:** مهم (پس از ۵ تلاش reconnect نمی‌کند)

**کد فعلی:**
```typescript
this.socket.onclose = () => {
  if (this.reconnectAttempts < 5) {
    setTimeout(() => { this.reconnectAttempts++; this.connect() }, 1000 * this.reconnectAttempts)
  }
  // ← بعد از ۵ بار، دیگر reconnect نمی‌کند
}
```

**راه‌حل:**
- Exponential backoff با cap (۳۰ ثانیه)
- پس از ۳۰ تلاش (~5 دقیقه)، یک banner «Realtime channel lost — click to retry» نمایش بده
- یک متد `manualReconnect()` برای کاربر

**اولویت:** P1.

---

## A9. 🟡 بهبود — `Zustand selectors` نادقیق

**فایل:** `frontend/src/editor/model.ts` (و همه‌ی consumerها)
**شدت:** بهبود (re-render بیش از حد)

**کد فعلی:**
```typescript
const { tracks, clips, transitions, selectedId, playhead, ... } = useEditor()
// ← همه‌ی state می‌گیرد
```

**مشکل:** اگر فقط `playhead` عوض شود، هم Timeline و هم PreviewMonitor و هم Inspector همه re-render می‌شوند.

**راه‌حل:**
- Selector-based: `const playhead = useEditor(s => s.playhead)`
- برای Timeline (که به چندتا نیاز دارد): `useShallow` از `zustand/shallow`
- این می‌تواند ۳-۵ برابر performance بهتر بدهد در timeline با ۱۰۰+ clip

**اولویت:** P2 (Performance).

---

## A10. 🟡 بهبود — Logging ساخت‌یافته وجود ندارد

**شدت:** بهبود (debug در production سخت)

**کد فعلی:**
```python
# backend/app/main.py
print(f"  {__app_name__} v{__version__} starting on 0.0.0.0:{settings.backend_port}")
```

**راه‌حل:**
- `structlog` یا `loguru` با JSON output
- `RotatingFileHandler` (10MB × 5 backup)
- یک اسکریپت `crash_report.py` که `~/CuttingEdge/data/logs/*.jsonl` را به یک zip قابل share تبدیل کند
- این پاسخ همان چیزی‌ست که در STATE.md به آن اشاره شده («گزارش خرابی محلی»)

**اولویت:** P2.

---

## A11. 🟡 بهبود — `axios` بدون interceptor

**فایل:** `frontend/src/api/client.ts`
**شدت:** بهبود (UX error handling)

**راه‌حل:**
- response interceptor:
  - 401 → banner «token expired»
  - 503 با `detail: "faster-whisper not installed"` → toast با دکمه‌ی «نصب»
  - 404 silent (برای endpointهای optional مثل `/api/ai/cuda/status` در build بدون GPU)
- request interceptor:
  - timing measurement (برای analytics خودکار endpointهای کند)
  - correlation ID برای tracing

**اولویت:** P2.

---

## A12. 🟢 جزئی — `Timeline.tsx` و `PreviewMonitor.tsx` consistency

**فایل:** `frontend/src/editor/Timeline.tsx` (خط ۱۱۶)
**شدت:** جزئی (code smell)

**کد فعلی:**
```typescript
const xToTime = useCallback(
  (clientX: number) => {
    const rect = laneRef.current?.getBoundingClientRect()  // ⚠️ در callback
    ...
  },
  [pxPerSecond]
)
```

**راه‌حل:** `const lane = laneRef.current` را اول تابع بگیر. این فقط consistency است.

**اولویت:** P3.

---

# بخش B — ۱۰ ایده‌ی «خفن‌تر کردن» برنامه

## B1. 🧠 «Director Mode» — دستور زبان طبیعی به ادیت

**توضیح:** دکمه‌ای در Toolbar با آیکون میکروفون → voice input → ترنسکرایب (faster-whisper که دارید) → به `planner.make_plan` بفرست → نتیجه را قبل از apply نشان بده.

**چرا خفن:** ترکیب دو feature موجود = یک feature جدید با ۵۰ خط کد.

**تخمین:** ۱ هفته (UI + integration + tests).

---

## B2. 🎬 «Cut on Emotion» — تحلیل احساسات چهره

**توضیح:** در `engine/analyze.py` یک pipeline اضافه کنید که فریم‌ها را به یک `vit-fer` (مدل کوچک facial emotion recognition، ~50MB) بدهد و `strength` را emotion-weighted کند.

**چرا خفن:** highlight لحظات لبخند، خشم، غم — کاملاً on-device، بدون API.

**تخمین:** ۲ هفته (مدل + pipeline + UI).

---

## B3. 📐 Multi-Cam Switcher

**توضیح:** اگر timeline چندین زاویه از یک رویداد دارد (مثلاً ۳ دوربین از یک کنسرت)، یک mode که **خودش بر اساس صدا (applause, whoosh, who-talks) زاویه را عوض کند**.

**چرا خفن:** «AI auto-cut» که در OBS و StreamYard مشابه آن هست، اما on-device.

**تخمین:** ۳ هفته.

---

## B4. 🎵 Beat-Synced Text Animations

**توضیح:** `keyframes` در `Clip` وجود دارد. animation‌های متن (scale, slide, rotate) که روی beat فعال می‌شوند. مثلاً «بوم» → کلمه zoom in. این را می‌توان با CSS keyframes + `data-key` که در PreviewMonitor دارید، ۱۰۰ خط کد.

**چرا خفن:** Captions در TikTok این را دارند؛ شما هم داشته باشید.

**تخمین:** ۱ هفته.

---

## B5. 🌐 Export Recipe Marketplace

**توضیح:** یک دایرکتوری `~/CuttingEdge/recipes/*.json` که کاربر بتواند recipe خود را share کند:
```json
{
  "name": "Vlog Cinematic",
  "tempo": 120,
  "cuts": [{"at": 0.0, "duration": 2.5}, {"at": 2.5, "duration": 1.8}],
  "color_grade": "warm",
  "captions": {"style": "tiktok-pop", "highlight": "#FF2D9C"}
}
```

**چرا خفن:** recipeها را می‌توان در GitHub Gist یا خود chat2db (که دارید!) share کرد. اکوسیستم می‌سازد.

**تخمین:** ۲ هفته (spec + sync + UI).

---

## B6. ⚡ GPU Direct Path برای NVENC

**توضیح:** شما `engine/gpu.py` دارید (۵۸۰ خط). در `compose.py` (`ffmpeg_binary()`)، اگر GPU NVIDIA موجود باشد، از `h264_nvenc` به جای `libx264` استفاده کنید.

**چرا خفن:** **۵-۱۰× سریع‌تر** export. فقط در export settings یک گزینه «Use GPU encoder» اضافه کنید.

**تخمین:** ۱ روز.

---

## B7. 🖼 Smart Thumbnail Generator

**توضیح:** وقتی timeline ساخته می‌شود، `analyze.py` می‌تواند ۱۰ فریم امتیازدهی شده برگرداند (نه فقط یکی). UI از آن‌ها برای ساخت یک storyboard ۱۰‌تایی استفاده کند.

**چرا خفن:** این همان «Video Summary» یوتیوب است، on-device.

**تخمین:** ۱ هفته.

---

## B8. 🧩 Plugin System برای Engines

**توضیح:** شما در `STATE.md` به «کانال پلاگین TTS» اشاره کردید. این را جدی بگیرید: یک API کوچک:
```python
class Provider(Protocol):
    name: str
    def transcribe(self, path: str) -> dict: ...
    def is_available(self) -> bool: ...
```

هر provider در `~/CuttingEdge/providers/<name>/` یک پوشه دارد. این الگوی **VSCode-style** است.

**چرا خفن:** نگهداری ۱۰× ساده‌تر. TTS, Diarization, Color Grading, VFX همه به عنوان plugin.

**تخمین:** ۳ هفته.

---

## B9. 📊 Performance HUD

**توضیح:** یک overlay (toggle با F3) که در preview monitor نمایش دهد:
- FPS واقعی (نه target)
- decode time per frame
- WebSocket events per second
- حافظه‌ی Python (از `tracemalloc`)

**چرا خفن:** کاربر می‌فهمد چرا timeline کُند شده. DaVinci هم چنین چیزی دارد.

**تخمین:** ۲ روز.

---

## B10. 🎯 «Director's Cut» Review

**توضیح:** برای هر clip یک score خودکار (از brain) + یک note کاربر ذخیره شود. در `ClipReview` یک حالت "Compare 3 versions" اضافه کنید: کنار هم ۳ کلیپ که سه planner مختلف ساخته‌اند. کاربر بهترین را انتخاب می‌کند.

**چرا خفن:** **این خودش آموزش مدل است** — implicit feedback برای آینده.

**تخمین:** ۱ هفته.

---

# بخش C — سیستم طراحی Hybrid

> ۹۰٪ Minimal Pro + ۹٪ Cyberpunk accent + ۱٪ Glassmorphism overlay

---

## C1. Design Tokens

### C1.1 — Foundation (Minimal Pro)

```
SURFACES (تیره → روشن):
--ce-bg:               #0A0A0A   ← canvas
--ce-bg-elev:          #0F0F14   ← sidebar, toolbar
--ce-surface:          #18181F   ← card, modal
--ce-surface-2:        #25252E   ← nested card, hover

BORDERS:
--ce-border:           rgba(255, 255, 255, 0.08)
--ce-border-strong:    rgba(255, 255, 255, 0.16)
--ce-divider:          rgba(255, 255, 255, 0.06)

TEXT (با opacity hierarchy):
--ce-text:             #FFFFFF                 (primary)
--ce-text-secondary:   rgba(255, 255, 255, 0.7)
--ce-text-tertiary:    rgba(255, 255, 255, 0.5)
--ce-text-disabled:    rgba(255, 255, 255, 0.3)
```

### C1.2 — Accent (Cyberpunk) — فقط ۵ المان

```
--ce-neon-pink:        #FF2D9C   ← primary action, playhead
--ce-neon-cyan:        #00F0FF   ← info, scoreboard title, ruler
--ce-neon-purple:      #A855F7   ← text lane, AI
--ce-neon-green:       #10F0A0   ← success, online, winner border
--ce-neon-amber:       #FFB800   ← warning, beat grid, pending
--ce-neon-red:         #EF4444   ← error, danger

GLOWS (box-shadow values):
--ce-glow-pink:        0 0 8px rgba(255, 45, 156, 0.6)
--ce-glow-pink-lg:     0 0 16px rgba(255, 45, 156, 0.8)
--ce-glow-cyan:        0 0 8px rgba(0, 240, 255, 0.5)
--ce-glow-green:       0 0 12px rgba(16, 240, 160, 0.5)
--ce-glow-amber:       0 0 8px rgba(255, 184, 0, 0.4)
```

### C1.3 — Overlay (Glassmorphism) — فقط floating UI

```
--ce-glass-bg:         rgba(255, 255, 255, 0.05)
--ce-glass-bg-strong:  rgba(255, 255, 255, 0.08)
--ce-glass-border:     rgba(255, 255, 255, 0.12)
--ce-glass-blur:       blur(20px)
--ce-glass-blur-lg:    blur(40px)
--ce-glass-shadow:     0 8px 32px rgba(0, 0, 0, 0.4)
```

### C1.4 — Spacing (4px grid)

```
--ce-space-1:          4px
--ce-space-2:          8px
--ce-space-3:          12px
--ce-space-4:          16px
--ce-space-5:          24px
--ce-space-6:          32px
--ce-space-7:          48px
--ce-space-8:          64px
--ce-space-9:          96px
```

### C1.5 — Radius

```
--ce-radius-sm:        6px    ← small chips
--ce-radius-md:        8px    ← buttons
--ce-radius-lg:        12px   ← cards
--ce-radius-xl:        16px   ← modals
```

### C1.6 — Motion

```
--ce-ease:             cubic-bezier(0.22, 0.61, 0.36, 1)
--ce-ease-in:          cubic-bezier(0.4, 0, 1, 1)
--ce-ease-out:         cubic-bezier(0, 0, 0.2, 1)
--ce-duration-fast:    150ms
--ce-duration:         200ms   ← default
--ce-duration-slow:    300ms
```

### C1.7 — Z-index

```
--ce-z-base:           1
--ce-z-sticky:         20       ← task dock
--ce-z-overlay:        40       ← AI FAB, AI panel
--ce-z-modal:          60       ← Modals
--ce-z-toast:          80       ← Messages
```

---

## C2. Typography System

### فونت‌ها:
```
--ce-font-sans:        'Inter', 'Vazirmatn', system-ui, -apple-system, sans-serif
--ce-font-mono:        'JetBrains Mono', 'Consolas', 'Menlo', monospace
--ce-font-fa:          'Vazirmatn', 'Inter', system-ui, sans-serif
```

### Scale:

| Token | Size | Weight | Use |
|-------|------|--------|-----|
| `ce-display` | 32px | 500 | Page hero |
| `ce-h1` | 24px | 500 | Section title |
| `ce-h2` | 18px | 500 | Card title |
| `ce-h3` | 14px | 500 | Subhead |
| `ce-body` | 14px | 400 | Paragraph |
| `ce-body-sm` | 13px | 400 | Description |
| `ce-caption` | 12px | 400 | Metadata |
| `ce-eyebrow` | 11px | 500 | UPPERCASE, +2 tracking |
| `ce-mono` | 14px | 400 | Numbers, codes |
| `ce-mono-sm` | 12px | 400 | Small numbers |
| `ce-mono-lg` | 18px+ | 500 | Scoreboard scores |

### قواعد طلایی Typography:

1. **هر عدد** (timecode, size, score, %, ms) با `ce-mono` — حتی اگر کوچک باشد
2. **هر عنوان** با `ce-h1` یا `ce-h2` یا `ce-h3`
3. **هر label** (مثل «Format», «Quality») با `ce-eyebrow`
4. **هر توضیح** با `ce-body` یا `ce-body-sm`
5. **فارسی** خودکار Vazirmatn می‌گیرد (font fallback)
6. **letter-spacing** در display: -0.01em (فشرده، مدرن)
7. **letter-spacing** در eyebrow: +0.08em (باز، رسمی)

---

## C3. Color & State

### Semantic mapping:

| Token | Value | استفاده |
|-------|-------|--------|
| `--ce-status-online` | `--ce-neon-green` | Backend online, peer connected |
| `--ce-status-warning` | `--ce-neon-amber` | Beat grid, pending, slow |
| `--ce-status-error` | `--ce-neon-red` | Crash, validation, timeout |
| `--ce-status-info` | `--ce-neon-cyan` | Scoreboard title, info chip |

### Accent semantic:

| Token | Value | استفاده |
|-------|-------|--------|
| `--ce-accent` | `--ce-neon-pink` | دکمه‌ی primary، playhead، export |
| `--ce-accent-glow` | `--ce-glow-pink` | با shadow برای highlight |
| `--ce-accent-subtle` | `rgba(255, 45, 156, 0.16)` | background tint |

### قوانین استفاده از رنگ:

1. **هر رنگ فقط یک وظیفه.** اگر pink هم primary action باشد هم notification، اشتباه است.
2. **هیچ‌وقت دو المان هم‌جنس هم‌رنگ نباشند** مگر در یک گروه معنایی.
3. **رنگ هرگز تنها نشانه‌ی وضعیت نباشد.** یک disabled button فقط color نیست؛ باید opacity پایین + cursor + label هم داشته باشد.
4. **contrast ratio** ≥ 4.5:1 برای متن، 3:1 برای المان‌های بزرگ.

---

## C4. Spacing & Layout

### Grid system:
- **Max content width:** 1100px (برای Settings, StyleMatch, ExportQueue)
- **Max content width:** 1360px (برای Studio — چون timeline عریض است)
- **Left/right padding:** 40px (desktop)، 24px (tablet)، 16px (mobile)
- **Top padding از header:** 80px (برای page hero)

### Vertical rhythm:
- بین major sections: 64px (`--ce-space-8`)
- بین minor sections: 32px (`--ce-space-6`)
- بین cards: 16px (`--ce-space-4`)
- بین items در یک list: 8px (`--ce-space-2`)

### Shell:
- Header height: 56px
- Task dock height: 60px (در حالت expanded)، 32px (compact)
- Sidebar widths: 64px (icon-only)، 200px (compact)، 280px (expanded)

---

## C5. Motion & Micro-interactions

### اصول:

1. **200ms** default برای همه‌ی transitions
2. **cubic-bezier(0.22, 0.61, 0.36, 1)** — اوج در ۶۰٪، settle آرام
3. **`prefers-reduced-motion: reduce`** → همه به 0.01ms
4. **transform و opacity** فقط (هیچ‌وقت width/height/top/left)
5. **همیشه `will-change`** برای المان‌های متحرک

### Micro-interactions فهرست:

| المان | تعامل | Motion |
|-------|------|--------|
| Button | hover | background change 150ms, no scale |
| Button | active | scale(0.98) 100ms |
| Card | hover | border-color to `--ce-border-strong` 200ms |
| Input | focus | border-color to `--ce-neon-cyan` + 1px ring 200ms |
| Toggle | on/off | slide 200ms with overshoot |
| Modal | open | fade 200ms + scale 0.95→1 |
| Modal | close | fade 150ms + scale 1→0.95 |
| Toast | enter | slide-up 200ms + fade |
| Toast | exit | slide-right 200ms + fade |
| Tab | switch | underline slides 200ms (transform) |
| Dropdown | open | fade 150ms + translateY(4px → 0) |
| Page transition | mount | fade 280ms + translateY(10px → 0) |
| Scoreboard loading | skeleton | pulse 1.2s ease-in-out infinite |
| Scoreboard complete | number tick | each digit animates 300ms ease-out |
| Playhead | move | no transition — instant |
| Clip drag | move | no transition — direct follow |
| AI panel | open | spring (stiffness 420, damping 38) |
| Glass overlay | enter | opacity 200ms + backdrop-filter fade |

### Page-level transitions:
- استفاده از `framer-motion` (که الان دارید) برای:
  - Page enter/exit (fade + 10px Y)
  - Shared element (wordmark از launcher به section) — `layoutId="ce-wordmark"`
  - Modal scale + fade
  - Stagger list items (هر ۲۰ms تأخیر)

---

## C6. Component Library

### C6.1 — `<Scoreboard>` (Style Match)

**Props:**
- `winner: string` (required)
- `scoreboard: ScoreboardEntry[]` (required)
- `variant?: 'minimal' | 'cyberpunk'` (default: 'minimal')
- `metrics?: Record<string, number>` (optional — برای breakdown)
- `elapsedSeconds?: number` (optional)
- `cta?: ReactNode` (optional — برای Apply button)

**Type:**
```typescript
interface ScoreboardEntry {
  name: string           // "ollama:qwen2.5"
  score: number          // 0.83
  shots?: number         // 15
  seconds?: number       // 28.4
  note?: string          // "Strongest hook first"
  skipped?: string[]     // ["no plan"] یا undefined
}
```

**States:**
- `loading` (skeleton + pulse)
- `success` (data + winner highlighted)
- `error` (red border + retry button)
- `empty` (no candidates — shouldn't happen but handled)

**Anatomy:**
```
┌─────────────────────────────────────────┐
│ BRAIN DECISION              14.2s       │ ← header (eyebrow + time)
│ ─────────────────                        │ ← hairline
│                                         │
│ ┌─winner (3px green left border)─┐     │ ← row 1
│ │ ollama:qwen2.5      ★          │     │
│ │ Strongest hook first  0.83     │     │ ← (cyberpunk: pink glow)
│ │ 15 shots · 28.4s      [Used]   │     │
│ └────────────────────────────────┘     │
│                                         │
│ rules+beats              0.78           │ ← row 2
│ Snapped to music grid    [Candidate]    │
│                                         │
│ rules                    0.71           │ ← row 3
│ Strongest moments        [Baseline]     │
│                                         │
│ ollama:moondream         0.69           │ ← row 4 (dimmer)
│ No usable answer      ~~  [Skipped]     │
│                                         │
│ ─────────────────                        │
│ Score breakdown — qwen2.5               │ ← metrics (optional)
│ rhythm     ████████░░ 0.80               │
│ duration   █████████░ 0.90               │
│ meaning    ███████░░░ 0.70               │
│ freshness  ████████░░ 0.85               │
│                                         │
│ ─────────────────                        │
│ [ Apply to timeline     ⏎ ]             │ ← CTA
└─────────────────────────────────────────┘
```

**Cyberpunk تفاوت‌ها:**
- Title "BRAIN DECISION" → neon cyan با glow
- Thin neon cyan line under title
- Winner row: 3px neon green left border + subtle pink tint
- "0.83" → neon pink با glow halo و ★ icon
- "Used" pill → neon green

**Minimal تفاوت‌ها:**
- Title → white at 50%
- Hairline (1px white at 8%)
- Winner row: subtle white left border (1px)
- "0.83" → clean white
- "Used" → green text (no border)

---

### C6.2 — `<Timeline>` (بدون تغییر در behavior، فقط skin)

**Playhead:**
- Stroke: `var(--ce-neon-pink)` (#FF2D9C)
- Width: 2px
- Glow: `var(--ce-glow-pink)` (4px halo)
- Top: مثلث ۸px (نوک تیز، صورتی نئونی)
- Bottom: مثلث ۸px
- در centred mode: width 2.5px + glow بیشتر

**Ruler:**
- Background: `--ce-bg-elev` (#0F0F14)
- Timecodes: 10px mono, white at 40%
- Tick marks: 1px white at 22%
- Beat grid (bars): 1px `--ce-neon-amber` at 30% (هر ۴ beat روشن‌تر)
- Beat grid (beats): 1px `--ce-neon-amber` at 45%

**Lane:**
- Background: linear-gradient(90deg, transparent 0 79px, rgba(148,163,184,0.05) 79px 80px) — همین الان هست
- Locked lane: 4% red tint

**Clip:**
- Border: 1px white at 14% (همین الان)
- Selected: 2px white outline (همین الان)
- Background: linear-gradient با رنگ clip + alpha
- Film strip: همین الان
- Waveform: همین الان

**Track header:**
- Background: `--ce-bg-elev`
- Track name: 12px medium, white at 70%
- Icon buttons: 28x28px, hover → white at 100%

**Corner (zoom controls):**
- Same as before
- "+", "−", "Fit", "Crosshair" buttons
- "px/s" indicator in mono

**Junction (transition marker):**
- Diamond shape (rotated square)
- Unset: white at 30% border, transparent fill
- Set: pink-to-cyan gradient

---

### C6.3 — `<TaskDock>` (Glassmorphism)

**Position:** sticky bottom
**Height:** 60px (expanded), 32px (compact)
**Layout:**
```
┌─────────────────────────────────────────────────────────────┐
│  TaskDock (glass)                                  [Clear]  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │ Style Match  │  │ Whisper      │  │ Export       │       │
│  │ ███████░░ 67%│  │ ████░░ 42%   │  │ ✓ Done       │       │
│  └──────────────┘  └──────────────┘  └──────────────┘       │
└─────────────────────────────────────────────────────────────┘
```

**Style:**
- Background: `var(--ce-glass-bg)` (rgba(255,255,255,0.05))
- Backdrop-filter: `blur(20px)`
- Border-top: 1px `var(--ce-glass-border)`
- Border-radius: 16px 16px 0 0
- Shadow: `0 -8px 32px rgba(0,0,0,0.4)` (به سمت بالا)

**Task card:**
- Background: rgba(255,255,255,0.02)
- Border: 1px var(--ce-border)
- Border-radius: 8px
- Progress bar:
  - Background: rgba(255,255,255,0.06)
  - Fill: linear-gradient(90deg, var(--ce-neon-cyan), var(--ce-neon-pink))
  - Height: 3px
  - Border-radius: 2px
- Status text: 11px mono

---

### C6.4 — `<AIAssistantFAB>` (Hybrid: glass + glow)

**Position:** fixed bottom-right
**Size:** 52x52px
**State:**
- Idle: glass circle, sparkle icon, subtle pink glow
- Listening: animated pulse glow (2s loop)
- Thinking: spinning ring + cyan glow
- Open: AI panel slides up (spring)

**Idle style:**
```css
background: var(--ce-glass-bg-strong);
backdrop-filter: blur(20px);
border: 1px var(--ce-glass-border);
box-shadow: var(--ce-glow-pink), 0 8px 24px rgba(0,0,0,0.5);
```

**Listening state:**
- Pulse animation: `box-shadow` از 8px به 16px، 2s ease-in-out infinite
- Inner sparkle rotates 360° (3s linear infinite)

**AI Panel (وقتی باز است):**
- Width: min(420px, calc(100vw - 44px))
- Position: bottom 80px
- Background: glass-strong
- Header: "AI Assistant" + close button
- Input: textbox + send button
- Chips: پیشنهادات سریع (style match، find cuts، explain score)
- Dry-run preview: لیست عملیات قبل از apply
- Log: نتایج قبلی

---

### C6.5 — `<ExportButton>` (Hybrid: minimal + cyberpunk accent)

**State 1 — Idle (before export):**
- Background: `--ce-surface-2`
- Text: white, 13px medium
- Border: 1px `--ce-neon-pink` (با 30% opacity)
- Padding: 8px 16px
- Border-radius: 8px

**State 2 — Hover:**
- Border: 1px `--ce-neon-pink` (با 100% opacity)
- Box-shadow: `--ce-glow-pink`
- Transition: 200ms ease

**State 3 — Encoding (in progress):**
- Background: `--ce-surface-2`
- Spinner icon (16px) + text
- Border: 1px `--ce-neon-amber` (با 100% opacity)
- Box-shadow: `--ce-glow-amber`

**State 4 — Done:**
- Background: linear-gradient(135deg, `--ce-neon-green`, `--ce-neon-cyan`)
- Text: black, 13px medium
- Box-shadow: `--ce-glow-green`

**State 5 — Failed:**
- Background: `--ce-surface-2`
- Text: `--ce-neon-red`
- Border: 1px `--ce-neon-red`

---

### C6.6 — `<EmptyState>`, `<LoadingState>`, `<ErrorState>`, `<SuccessState>`

(طبق `REDESIGN_17_EMPTY_LOADING_STATES.png`)

**Empty:**
- Icon: geometric, 32px, white at 30%
- Title: 16px medium, white
- Description: 12px, white at 50%
- CTA: text button with underline

**Loading:**
- Spinner: 32px ring, white at 30% track, neon cyan arc
- Title: 16px medium, white
- Status: 12px mono, white at 50% ("00:00:08 · Detecting scenes")
- Progress bar: 3px tall, 200px wide, neon cyan

**Error:**
- Icon: triangle with !, neon red, 32px
- Title: 16px medium, white
- Description: 12px, white at 50%
- Error code: 10px mono, neon red ("ERR_FORMAT_UNSUPPORTED")
- Buttons: "Try again" (white) + "Open docs ↗" (white at 50%)

**Success:**
- Icon: circle 40px with ✓, neon green with glow
- Title: 16px medium, white
- Subtitle: 12px mono, white at 50%
- Path: 10px mono, neon green ("★ Saved to: /Exports")

---

### C6.7 — دیگر Components

| Component | استایل |
|-----------|--------|
| `<Button>` | Minimal, variants: primary, ghost, danger, link |
| `<Input>` | Minimal با focus ring neon cyan |
| `<Toggle>` | Minimal با track slide |
| `<Select>` | Minimal با chevron |
| `<Modal>` | Glass with backdrop blur |
| `<Toast>` | Glass slide-up |
| `<Chip>` | Pill با border + opacity |
| `<ProgressBar>` | Track + fill (gradient برای accent) |
| `<Tab>` | Text با underline animated |
| `<Tooltip>` | Glass mini |
| `<Card>` | Surface با border |
| `<Avatar>` | Circle با initials |
| `<Badge>` | Pill کوچک |
| `<KeyboardShortcut>` | Mono kbd |
| `<Slider>` | Track + thumb |

---

## C7. Page-by-Page Specification

### C7.1 — Launcher (Home)

**Style:** ۹۰٪ Minimal + ۹٪ Cyberpunk (در action buttons) + ۱٪ Glass (AI FAB)

**Layout:**
```
┌─────────────────────────────────────────────────────────────┐
│                                          [⌘K] [Avatar]     │ ← Top bar (minimal)
│                                                             │
│                                                             │
│                                                             │
│                  CUTTING EDGE                               │ ← Wordmark (large)
│              AI-powered desktop video editor                │
│                       v0.9.34                               │
│                                                             │
│              ─── (1px hairline, 60px) ───                   │
│                                                             │
│         ┌────────────────┐  ┌────────────────┐              │
│         │ + New Project  │  │ ✦ Style Match  │              │ ← Actions
│         │   (cyan dot)   │  │   (pink dot)   │              │   (cyberpunk dots)
│         └────────────────┘  └────────────────┘              │
│                                                             │
│                                                             │
│   RECENT                                                    │ ← Section title
│   ───                                                       │
│   [Thumb] alpha_vlog_2025.edl                  ● synced     │ ← Project row
│            Modified 2h ago · 14.2s · 3 clips                │
│   ───                                                       │
│   [Thumb] intro_v3.mp4                      ● local         │
│            Modified 1d ago · 8.4s · 2 clips                 │
│   ───                                                       │
│                                                             │
│                                                             │
│  ┌─────────────────────────────────────────────┐            │
│  │ ✦ Ask AI                          (glass)  │            │ ← AI FAB
│  └─────────────────────────────────────────────┘            │
│                                                             │
│  ● Backend online  v0.9.34  12.4 GB free  6.2 GB VRAM      │ ← Status
└─────────────────────────────────────────────────────────────┘
```

**Components استفاده‌شده:**
- `<Button variant="minimal-accent">` × 2 (با dot)
- `<EmptyState>` (اگر recent خالی باشد)
- `<AIAssistantFAB variant="glass">`
- `<StatusBar>`

---

### C7.2 — Studio (Editor)

**Style:** ۹۵٪ Minimal + ۴٪ Cyberpunk (Playhead, AI button, Export) + ۱٪ Glass (Task dock)

**Layout:**
```
┌─────────────────────────────────────────────────────────────────────────┐
│ Cutting Edge  alpha_vlog_2025.edl            ● online  6.2GB  [Export]│
├──────────┬───────────────────────────────────────────────┬────────────┤
│          │                                               │            │
│  Tools   │           Preview Monitor (16:9)              │ Properties │
│  (icon)  │                                               │            │
│          │   00:00:14:22                       ● REC      │  Position  │
│  Cut     │   [video content]                              │  00:14:22  │
│  Copy    │                                               │            │
│  Trim    │   1920×1080 · 30fps                            │  Duration  │
│  Trans   │                                               │  2.5s      │
│  Text    │                                               │            │
│  Music   │                                               │  Speed     │
│  Export  │                                               │  1.0x      │
│          │                                               │            │
│  AI ✦    │                                               │  Volume    │
│          │                                               │  67%       │
│          │                                               │            │
│          │                                               │  BRAIN     │ ← scoreboard (compact)
│          │                                               │  rules 0.71│
│          │                                               │  qwen 0.83★│
│          │                                               │  → USED    │
│          │                                               │            │
├──────────┴───────────────────────────────────────────────┴────────────┤
│ [▶] [⏸] [✂] [📋] [🗑] [↶] [↷] [Magnet]   [Import]   [Export 1.0x]   │ ← Toolbar
├─────────────────────────────────────────────────────────────────────────┤
│ 00:00  00:10  00:20  00:30  00:40  00:50  01:00                       │ ← Ruler (neon amber beats)
│      |       |       |       |       |       |                       │
│ V1:  [========clip1========]   [==clip2==]   [===clip3===]            │ ← Video lane
│ A1:  [~~~~~~~~waveform~~~~~~~~]                                      │ ← Audio lane
│ T1:       [text clip]                                                │ ← Text lane
│              ↑ playhead (neon pink, 2px + glow)                      │
├─────────────────────────────────────────────────────────────────────────┤
│  [TaskDock glass: Style Match 67% | Whisper 42% | Export ✓]          │
└─────────────────────────────────────────────────────────────────────────┘
```

**نکات کلیدی:**
- Playhead تنها المان Cyberpunk در timeline (۲px pink با glow)
- Beat grid (اگر detect شده) با neon amber
- Task dock در پایین (glass)
- AI Assistant FAB در bottom-right (glass + glow)
- Export button در toolbar با border neon pink

---

### C7.3 — Style Match (Brain Output)

**Style:** ۹۵٪ Minimal + ۵٪ Cyberpunk (فقط winner score)

**Layout:**
```
┌─────────────────────────────────────────────────────────────┐
│ Cutting Edge  / Style Match                  ● 14.2s  [Apply ⏎]
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ANALYSIS COMPLETE                                          │ ← Eyebrow
│  Brain selected qwen2.5                                     │ ← H1
│  0.83 score · 15 shots · 28.4s · 4 candidates               │ ← Subtitle
│                                                             │
│  ─── (1px, 80px wide) ───                                   │
│                                                             │
│  BRAIN DECISION                                             │ ← Scoreboard
│  ──── (neon cyan, 30% opacity)                              │
│                                                             │
│  ┌─winner (3px green border)─┐                               │
│  │ ollama:qwen2.5      ★    │                               │
│  │ Strongest hook first     │                               │
│  │ 15 shots · 28.4s   Used  │                               │
│  │              0.83 ←neon  │                               │
│  └──────────────────────────┘                               │
│                                                             │
│  rules+beats                                               │
│  Snapped to music grid                                     │
│  16 shots · 27.9s    Candidate                             │
│              0.78                                           │
│                                                             │
│  rules                                                     │
│  Strongest moments                                         │
│  16 shots · 28.4s    Baseline                              │
│              0.71                                           │
│                                                             │
│  ~~ollama:moondream~~                                      │
│  No usable answer                                          │
│  12 shots · 22.3s    Skipped                               │
│              0.69                                           │
│                                                             │
│  ───                                                       │
│  Score breakdown — qwen2.5                                 │
│  rhythm     ████████░░ 0.80                                 │
│  duration   █████████░ 0.90                                 │
│  meaning    ███████░░░ 0.70                                 │
│  freshness  ████████░░ 0.85                                 │
│                                                             │
│  ─────────────────────────────────────────────────          │
│                                                             │
│  ┌──────────────────┐  ┌──────────────────┐                 │
│  │ 15 shots         │  │ Trace (glass)    │                 │
│  │ ───              │  │ ───              │                 │
│  │ 01 00:00-02.5   │  │ • 00:00.0        │                 │
│  │    "She said..." │  │   Analyzing ref  │                 │
│  │    0.85          │  │ • 00:02.4        │                 │
│  │ ───              │  │   8 peaks found  │                 │
│  │ 02 00:02-04.2   │  │ • 00:05.1        │                 │
│  │    "Most imp.."  │  │   Calling qwen   │                 │
│  │    0.82          │  │ • 00:11.3        │                 │
│  │ ...              │  │   12 picks       │                 │
│  └──────────────────┘  │ • 00:12.8        │                 │
│                         │   Scoring        │                 │
│                         │ • 00:14.2        │                 │
│                         │   Winner: qwen   │                 │
│                         └──────────────────┘                 │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**نکات کلیدی:**
- Title "BRAIN DECISION" تنها متن cyberpunk (neon cyan)
- Score "0.83" تنها عدد cyberpunk (neon pink + glow + ★)
- Trace panel تنها المان glass (اگر visual effect می‌خواهیم)
- بقیه ۱۰۰٪ minimal

---

### C7.4 — Settings / AI Runtime

**Style:** ۱۰۰٪ Minimal

**Layout:**
```
┌─────────────────────────────────────────────────────────────┐
│ Cutting Edge  / Settings                       [⌘K] [👤]    │
├─────────────────────────────────────────────────────────────┤
│  LOCAL AI ENGINES                                           │ ← Eyebrow
│  AI Runtime                                                 │ ← H1
│  Manage local models for vision, planning, transcription   │ ← Subtitle
│                                                             │
│  ─── (1px, 60px wide) ───                                   │
│                                                             │
│  ● Backend online │ Ollama 0.3.12 │ faster-whisper ✓ │ CUDA ✓
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ GPU                                  [Test]          │   │
│  │ NVIDIA GeForce GTX 1650                              │   │
│  │                                                     │   │
│  │ VRAM  3.1 / 4.0 GB                                   │   │
│  │ ████████████░░░░░░                                  │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ Ollama                                  ● Running    │   │
│  │ Local LLM service for vision and planning models     │   │
│  │ v0.3.12 · :11434 · 3 models         Manage →        │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ faster-whisper                           ● Loaded    │   │
│  │ Local speech-to-text engine                         │   │
│  │ model: large-v3 · ready · auto      Manage →        │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ Demucs (dimmed)                       ○ Not installed│   │
│  │ Source separation for vocals and instruments        │   │
│  │ 124 MB · ready: false              → Install         │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  AVAILABLE MODELS                                           │
│  ───                                                       │
│  qwen2.5vl:3b        [vision]  3.2 GB   ✓ installed  Select│
│  qwen2.5vl:7b        [vision]  6.0 GB   ○ not pulled  → Pull│
│  moondream           [vision]  1.7 GB   ✓ installed  Select│
│  llama3.2-vision:11b [vision]  7.9 GB   ○ not pulled  → Pull│
│  qwen2.5:3b-instruct [planning] 1.9 GB  ✓ installed  Select│
│  qwen2.5:7b-instruct-q4_0 [planning] 4.4 GB  Pull →        │
│  gemma2:9b          [planning] 5.4 GB  Pull →              │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

### C7.5 — Export Queue

**Style:** ۹۵٪ Minimal + ۵٪ Cyberpunk (progress bars با gradient)

```
┌─────────────────────────────────────────────────────────────┐
│ Cutting Edge  / Export Queue               + New export ⌘E  │
├─────────────────────────────────────────────────────────────┤
│  EXPORT QUEUE                                               │
│  5 renders in progress                                      │
│  Estimated total: 14m 32s remaining · 5.2 GB output         │
│                                                             │
│  All (8) · YouTube (2) · Reels (3) · TikTok (2) · 4K (1)   │ ← Tabs
│  ────                                                       │
│                                                             │
│  ● 3 active  │  ✓ 2 done  │  ✕ 1 failed  │  2 queued       │ ← Stats
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ [Thumb] alpha_vlog_2025_final.mp4                   │   │ ← Queue row 1
│  │         YouTube · 1920×1080 · 30fps · 8.4 MB        │   │
│  │         [Encoding] ████░░░░ 62%   2m 14s left  ⋯   │   │
│  └─────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ [Thumb] intro_v3.mp4                                │   │
│  │         Reels · 1080×1920 · 30fps · 12.1 MB         │   │
│  │         [Queued]  ░░░░░░░░  0%   waiting       ⋯   │   │
│  └─────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ [Thumb] podcast_clip_1.mp4                          │   │
│  │         TikTok · 1080×1920 · 24fps · 6.8 MB         │   │
│  │         [Done]    ████████ 100%  Open ↗         ⋯   │   │ ← green
│  └─────────────────────────────────────────────────────┘   │
│  ... (5 more rows)                                          │
│                                                             │
│  RECENT OUTPUTS                                             │
│  ───                                                       │
│  [card] [card] [card] [card]                                │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**نکات:**
- Progress bar: linear-gradient از neon cyan به neon pink
- Done: green dot + "Open ↗" link
- Failed: red dot + error message + "Retry"
- Cards: minimal با border

---

### C7.6 — Export Complete (Success Screen)

**Style:** ۷۰٪ Minimal + ۲۹٪ Cyberpunk (success glow) + ۱٪ Glass (share card)

```
┌─────────────────────────────────────────────────────────────┐
│  Cutting Edge                                               │
│                                                             │
│                                                             │
│                                                             │
│              ╭─────────────────╮                            │
│              │       ✓         │                            │ ← Big success circle
│              │  (neon green    │                            │   (40px, 8px glow)
│              │   with glow)    │                            │
│              ╰─────────────────╯                            │
│                                                             │
│              Export complete                                │ ← H1
│         alpha_vlog_2025_final.mp4                           │ ← Subtitle
│         Reels · 1080×1920 · 30fps · 14.2 MB                 │ ← Mono
│                                                             │
│         ┌──────────┬──────────┬──────────┐                 │
│         │ Duration │   Size   │Resolution│                 │ ← Stats
│         │  4m 32s  │ 14.2 MB  │1080×1920 │                 │
│         └──────────┴──────────┴──────────┘                 │
│                                                             │
│         [ Open file ↗ ]  [ Open folder ]  [ Share ]         │ ← Actions
│                                                             │
│         ● YouTube   ● Reels   ○ TikTok                      │ ← Platform status
│                                                             │
│                       ┌─────────────┐                      │
│                       │ Share (glass)│                     │ ← Glass card
│                       │ ───          │                     │   (optional)
│                       │ 📺 YouTube   │                     │
│                       │    Ready ✓   │                     │
│                       │ 📱 Reels     │                     │
│                       │    Ready ✓   │                     │
│                       │ 🎵 TikTok    │                     │
│                       │    Not setup │                     │
│                       │              │                     │
│                       │ Copy link    │                     │
│                       └─────────────┘                      │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ [TaskDock glass: Style ✓ | Export ✓ | Clean  ]      │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**نکات:**
- Success circle با glow سبز (cyberpunk accent، چون لحظه‌ی جشن است)
- Aurora bloom در background (8% opacity، soft)
- Share card با glass
- بقیه minimal

---

### C7.7 — سایر صفحات (خلاصه)

| Page | Style | المان‌های cyberpunk | المان‌های glass |
|------|-------|--------------------|-----------------|
| **ClipReview** | ۱۰۰٪ Minimal | هیچ | هیچ |
| **Dashboard** | ۹۵٪ Minimal + ۵٪ Cyberpunk (status dots) | Status dots | هیچ |
| **Doctor** | ۱۰۰٪ Minimal | هیچ | هیچ |
| **JobDetail** | ۱۰۰٪ Minimal | هیچ | هیچ |
| **Uploads** | ۱۰۰٪ Minimal | هیچ | هیچ |
| **NewJob** | ۹۵٪ Minimal + ۵٪ Cyberpunk (import button) | Import button border | هیچ |
| **Home** | همان Launcher | همان | همان |

---

## C8. State Machine (Empty / Loading / Error / Success / Skeleton)

**هر component که داده دارد باید این ۵ state را handle کند:**

| State | Visual | نمایش | Interaction |
|-------|--------|-------|-------------|
| **Empty** | Icon + title + desc + CTA | مثل "No clips yet" | فقط CTA قابل کلیک |
| **Loading** | Spinner + status + progress | "Analyzing 00:08 · Detecting scenes" | هیچ (disabled) |
| **Error** | Icon + title + desc + error code + 2 buttons | مثل "ERR_FORMAT_UNSUPPORTED" | "Try again" + "Open docs" |
| **Success** | Icon + title + data | مثل "Export complete" | CTA + "Open file" |
| **Skeleton** | ۳-۵ خط gray + pulse | جایگزین متن تا داده بیاید | هیچ |

**نکات:**
- هر state transition: 200ms ease
- skeleton pulse: 1.2s ease-in-out infinite
- error retry: بعد از ۲ ثانیه automatic retry (۱ بار)
- success → next state automatic after 3s (برای notifications)

---

## C9. Accessibility (WCAG 2.1 AA)

### الزامات:

1. **Color contrast:**
   - متن primary روی `--ce-bg`: ≥ 4.5:1 (سفید روی #0A0A0A = 19:1 ✓)
   - متن secondary: ≥ 4.5:1 (rgba(255,255,255,0.7) روی #0A0A0A = 13:1 ✓)
   - متن tertiary: ≥ 4.5:1 (rgba(255,255,255,0.5) روی #0A0A0A = 7:1 ✓)
   - المان‌های بزرگ (>18px): ≥ 3:1
   - رنگ accent تنها نشانه‌ی state نباشد

2. **Keyboard navigation:**
   - Tab order منطقی
   - Focus ring: 2px `--ce-neon-cyan` با 4px offset
   - `prefers-reduced-motion`: همه animation غیرفعال
   - Escape برای بستن modal/panel
   - Enter برای اعمال default action
   - کلیدهای میانبر: همه با `aria-keyshortcuts` مشخص

3. **Screen reader:**
   - هر button با `aria-label` اگر فقط icon دارد
   - هر المان تعاملی با `role` صحیح
   - live region برای progress updates
   - hidden text `.ce-sr-only` برای context اضافی

4. **Touch targets:**
   - حداقل 44x44px (موبایل)
   - حداقل 24x24px (دسکتاپ)

5. **Audio/Video:**
   - Captions برای video player
   - Audio description (اختیاری)
   - Volume control با `aria-valuenow`

6. **Forms:**
   - Label مرتبط با `for`/`id`
   - Error با `aria-invalid` + `aria-describedby`
   - Required با `aria-required`

---

## C10. Internationalization (i18n)

### زبان‌ها:
- **English** (default)
- **فارسی** (RTL)
- **عربی** (RTL، بعداً)
- **چینی** (بعداً)

### استراتژی:

```typescript
// استفاده از helper موجود:
const { t, lang } = useI18n()
t('Brain decision', 'تصمیم مغز')

// یا برای plurals:
t('{n} shots', '{n} برش', { n: shotsCount })

// یا برای تاریخ:
formatDate(date, lang)  // 'fa-IR' یا 'en-US'
```

### RTL نکات:

1. **`dir="auto"`** برای متن‌هایی که ممکن است فارسی باشند (clip label, project name)
2. **`dir="rtl"`** برای containerهای تماماً فارسی
3. **`dir="ltr"`** برای:
   - Timecode‌ها
   - URLs/paths
   - اعداد
   - Time units (00:14:22)
4. **Timeline همیشه LTR** (همان‌طور که الان هست)
5. **Scoreboard** می‌تواند LTR باشد (اعداد و نام مدل‌ها LTR هستند)
6. **font-family** خودکار Vazirmatn می‌گیرد با fallback به Inter

### CSS Logical Properties:

به جای `margin-left` از `margin-inline-start` استفاده کن:
```css
/* قبل: */
.button { margin-left: 8px; }

/* بعد: */
.button { margin-inline-start: 8px; }
```

برای position:
```css
/* قبل: */
.fab { right: 16px; }

/* بعد: */
.fab { inset-inline-end: 16px; }
```

---

## C11. Dark Mode

این app فقط dark mode دارد (desktop editor). اما اگر بخواهیم light mode هم اضافه کنیم:

```css
:root {
  /* dark values (default) */
  --ce-bg: #0A0A0A;
  ...
}

[data-theme="light"] {
  --ce-bg: #FAFAFA;
  --ce-text: #0A0A0A;
  ...
}
```

**اما توصیه نمی‌شود** — video editor‌ها همیشه dark بهترند (تمرکز روی video content).

---

## C12. Performance Budget

### Targets:

| Metric | Target | Hard Limit |
|--------|--------|-----------|
| First Contentful Paint | < 1.0s | 2.0s |
| Time to Interactive | < 2.0s | 4.0s |
| Frame rate (idle) | 60 fps | 30 fps |
| Frame rate (timeline drag) | 60 fps | 30 fps |
| Frame rate (export progress) | 60 fps | 30 fps |
| Memory (idle) | < 200 MB | 400 MB |
| Memory (heavy) | < 800 MB | 1.5 GB |
| CPU (idle) | < 2% | 5% |
| CPU (encoding) | < 80% | 95% |

### Optimization‌ها:

1. **`backdrop-filter` فقط روی overlay** (نه روی container اصلی)
2. **GPU acceleration** برای timeline drag (`will-change: transform`)
3. **Virtualization** برای list‌های بلند (react-window)
4. **Debounce** برای input changes (200ms)
5. **Throttle** برای mousemove (16ms = 60fps)
6. **`requestIdleCallback`** برای work غیرضروری
7. **Web Workers** برای export progress calculation
8. **`React.memo`** برای Clip components
9. **Lazy load** برای heavy components (modals, panels)
10. **Code splitting** برای هر page

---

## C13. Migration Strategy

### مرحله ۱: پایه (هفته ۱)

1. اضافه کردن `design-tokens.css` به global stylesheet
2. تعریف همه‌ی variables (نه اعمال، فقط تعریف)
3. ایجاد `style-guide.html` در dev برای preview

### مرحله ۲: Typography & Color (هفته ۲)

1. جایگزینی hard-coded colors با variables در global.css
2. اضافه کردن JetBrains Mono
3. تست contrast

### مرحله ۳: Components جدید (هفته ۳)

1. ساخت `<Scoreboard variant="minimal|cyberpunk">`
2. ساخت `<TaskDock glass>`
3. ساخت `<AIAssistantFAB glass>`
4. ساخت `<EmptyState/Loading/Error/Success>`

### مرحله ۴: Timeline (هفته ۴)

1. اعمال Playhead cyberpunk
2. اعمال beat grid neon amber
3. تست با ۱۰۰+ clips (performance)

### مرحله ۵: Refactor pages (هفته ۵)

1. StyleMatch → استفاده از `<Scoreboard>`
2. Settings → minimal
3. Export → progress bar cyberpunk
4. ExportComplete → success glow + glass share

### مرحله ۶: Polish (هفته ۶)

1. RTL تست
2. a11y audit
3. Performance audit
4. E2E test

---

## C14. Testing Strategy

### Unit Tests:
- هر component با React Testing Library
- هر utility function با Jest
- coverage target: 80%

### Integration Tests:
- Scoreboard با data‌های مختلف
- Timeline با ۱۰/۱۰۰/۱۰۰۰ clips
- WebSocket reconnect scenarios

### E2E Tests (Puppeteer):
- import media → edit → export flow
- style match → apply → export flow
- تمام keyboard shortcuts

### Visual Regression:
- Storybook + Chromatic برای هر component
- هر variant (minimal/cyberpunk) snapshot

### Accessibility:
- axe-core در CI
- manual screen reader test (NVDA/VoiceOver)

### Performance:
- Lighthouse در CI
- bundle size budget
- render profiling

---

# بخش D — نقشه‌ی راه ۱.۰ → ۲.۰

## فاز ۱: 1.0 (Q4 2026)

**هدف:** اولین انتشار عمومی، production-ready

| Task | Priority | Estimate |
|------|----------|----------|
| A1-A4: Critical bug fixes (WebSocket, pip timeout, SQLite, Path validation) | P0 | 2 weeks |
| Migration به Hybrid theme (C13) | P0 | 6 weeks |
| A5-A8: Important fixes (Ollama warmup, Timeline listener, RAF pause, WS reconnect) | P1 | 1 week |
| Documentation (auto-generated API docs) | P1 | 1 week |
| Clean install video (real machine) | P1 | 2 days |
| Audit step activation in CI | P1 | 1 day |
| B6: GPU Direct Path (NVENC) | P2 | 1 day |
| A9-A11: Improvements (Zustand selectors, logging, axios interceptor) | P2 | 1 week |

## فاز ۲: 1.1-1.5 (Q1-Q2 2027)

| Task | Priority | Estimate |
|------|----------|----------|
| B1: Director Mode (voice) | P1 | 1 week |
| B4: Beat-Synced Text Animations | P1 | 1 week |
| B7: Smart Thumbnail Generator | P1 | 1 week |
| B5: Export Recipe Marketplace | P2 | 2 weeks |
| B9: Performance HUD | P2 | 2 days |
| B10: Director's Cut Review | P2 | 1 week |
| Multi-platform installers (Mac, Linux) | P2 | 4 weeks |
| Cloud sync (optional, E2E encrypted) | P3 | 8 weeks |

## فاز ۳: 2.0 (Q3-Q4 2027)

| Task | Priority | Estimate |
|------|----------|----------|
| B2: Cut on Emotion (vit-fer) | P1 | 2 weeks |
| B3: Multi-Cam Switcher | P1 | 3 weeks |
| B8: Plugin System | P1 | 3 weeks |
| Collaborative editing (real-time) | P2 | 8 weeks |
| Mobile companion app (read-only) | P3 | 12 weeks |
| AI training (from user feedback) | P3 | 16 weeks |

---

# بخش E — اصول طراحی (۱۰ قانون طلایی)

### قانون ۱: Minimal هوایی است که نفس می‌کشی
اگر یک المان Minimal است، باید حداکثر ۱۰٪ المان‌های صفحه را اشغال کند. تراکم در Minimal = شلوغی.

### قانون ۲: Cyberpunk فقط در لحظات ویژه
هر المان Cyberpunk باید یک دلیل داشته باشد: "این لحظه مهم است". ۵ المان در کل اپ. نه بیشتر.

### قانون ۳: Glass فقط شناور
هر المان Glass باید روی چیز دیگری باشد. اگر container اصلی Glass شود، همه چیز «روی چیزی شناور» به نظر می‌رسد.

### قانون ۴: Accent نباید گریه کند
اگر یک المان Cyberpunk است، باید کاملاً Cyberpunk باشد. نیمه‌نیمه = هرج‌ومرج.

### قانون ۵: هر رنگ یک وظیفه
Pink = action. Cyan = info. Green = success. Amber = warning. Red = error. هیچ‌وقت تکرار نشود مگر در یک گروه معنایی.

### قانون ۶: Typography می‌گوید چه چیزی مهم است
Mono = داده (هر عدد). Sans = متن. هر timecode، هر score، هر size باید Mono باشد.

### قانون ۷: Contrast hierarchy با opacity
سفید ۱۰۰٪ > ۷۰٪ > ۵۰٪ > ۳۰٪. از این ۴ سطح استفاده کن. هیچ‌وقت رنگ جدید اضافه نکن.

### قانون ۸: Spacing از 4px شروع می‌شود
همه‌ی padding/margin/gap ضریب ۴. ۸، ۱۲، ۱۶، ۲۴، ۳۲، ۴۸، ۶۴، ۹۶. هیچ عدد دیگر.

### قانون ۹: Motion برای تأیید، نه نمایش
هر animation باید یک feedback بصری بدهد: "این کار شد". نه برای زیبایی.

### قانون ۱۰: RTL یک شهروند درجه‌ی اول است
اگر یک component در فارسی درست کار نکند، انگار نساخته‌اید. هر component با `dir="rtl"` تست شود.

---

# بخش F — ضمیمه‌ها

## F1. فایل‌های ایجادشده (هیچ‌کدام commit نشده)

| File | Type | Status |
|------|------|--------|
| `docs/CuttingEdge/REVIEW_AND_REDESIGN.md` | گزارش آنالیز | ذخیره شده |
| `docs/CuttingEdge/REDESIGN_*.png` (۱۸ تصویر) | طراحی بصری | ذخیره شده |
| `docs/CuttingEdge/REDESIGN_*.md` (۵ سند) | مستندات | ذخیره شده |
| `docs/CuttingEdge/FULL_HYBRID_DESIGN_SYSTEM.md` | این سند (master spec) | ذخیره شده |
| `frontend/src/styles/design-tokens.css` | CSS variables | آماده برای import |
| `frontend/src/editor/Scoreboard.tsx` | Component جدید | آماده برای import |
| `frontend/src/lib/format.ts` | Helper functions | آماده برای import |
| `frontend/src/pages/StyleMatchDemo.tsx` | مثال استفاده | آماده برای import |

## F2. Commit Strategy پیشنهادی

```bash
# مرحله ۱: مستندات (بی‌خطر)
git add docs/CuttingEdge/REDESIGN_*.png docs/CuttingEdge/REDESIGN_*.md
git add docs/CuttingEdge/REVIEW_AND_REDESIGN.md
git add docs/CuttingEdge/FULL_HYBRID_DESIGN_SYSTEM.md
git commit -m "docs(design): add complete Hybrid design system specification (v0.9.34)"

# مرحله ۲: Utilities جدید (بی‌خطر، استفاده نمی‌شود)
git add frontend/src/lib/format.ts
git commit -m "feat(lib): add format helpers (formatNumber, formatDuration, formatBytes)"

# مرحله ۳: Design tokens (تغییر فقط در CSS variables، چیزی نمی‌شکند اگر فقط variables جدید اضافه شوند)
git add frontend/src/styles/design-tokens.css
git commit -m "feat(styles): add Hybrid design tokens (foundation + cyberpunk + glass)"

# مرحله ۴: Scoreboard component (جدا از app)
git add frontend/src/editor/Scoreboard.tsx
git commit -m "feat(editor): add Scoreboard component with minimal/cyberpunk variants"

# مرحله ۵: Integration (با احتیاط، feature flag)
git add frontend/src/pages/StyleMatch.tsx
git commit -m "feat(style-match): integrate new Scoreboard component (behind feature flag)"
```

## F3. منابع اضافی

- [Linear Design](https://linear.app)
- [Vercel Design](https://vercel.com/design)
- [Apple Human Interface Guidelines](https://developer.apple.com/design/human-interface-guidelines/)
- [Material Design 3](https://m3.material.io)
- [Tailwind CSS](https://tailwindcss.com) (برای reference، نه استفاده)
- [Inter Font](https://rsms.me/inter/)
- [JetBrains Mono](https://www.jetbrains.com/lp/mono/)
- [Vazirmatn Font](https://github.com/rastikerdar/vazirmatn)

---

# پایان

این سند یک **نقشه‌ی مهندسی کامل** است. هیچ تغییری در کد فعلی ایجاد نشده. فایل‌های آماده برای import در پوشه‌های مناسب قرار دارند.

**قدم بعدی:** شما این سند را می‌خوانید، تأیید می‌کنید، و در یک جلسه با تیم (یا خودتان) برای اجرای فاز ۱ برنامه‌ریزی می‌کنید.

**سؤال کلیدی برای تصمیم:**
> آیا می‌خواهی فاز ۱ (Migration) را با همین plan شروع کنیم، یا ابتدا Critical Bug Fixes (A1-A4) را انجام دهیم، یا صبر کنیم تا 1.0 release و بعد؟

---

**ساخته شده توسط Arena Agent — تاریخ: 2026-08-27**
**شاخه: `arena/01a04055-chat2db`**
**هیچ فایلی از کد فعلی تغییر نکرده است.**
