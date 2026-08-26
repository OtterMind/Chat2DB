# سند دفاع — پاسخ مو به مو به دو بررسی استاد (بر پایه‌ی v0.9.21+)

> راهنمای استفاده: هر بند با یک برچسب وضعیت آمده است:
> ✅ انجام شد (در پاسخ به بررسی) · ⚪ از قبل درست بود · ⏸ موکول با دلیل · 🔴 رد با دلیل دفاعی.
> جلوی هر بند، **سند** (فایل/تست) آمده تا اگر استاد پرسید «از کجا می‌گویی؟»، جواب آماده باشد.

---

## استاد اول

### ۱. معماری
| بند | وضعیت | پاسخ دفاعی |
|---|---|---|
| سه‌لایه Electron+FastAPI+React درست است | ⚪ تأیید | همان است؛ Electron برای دسکتاپ cross-platform با فناوری وب، FastAPI برای API پرformance. |
| بسته‌بندی: PyInstaller بهتر است؟ | ⏸ | **Embeddable CPython** عمداً انتخاب شد: آپدیت تفاضلی (blockmap) به payload بایت‌به‌بایت پایدار نیاز دارد که PyInstaller آن را تضمین نمی‌کند؛ باینری PyInstaller هم آنتی‌ویروس‌ها را تحریک می‌کند. PyInstaller مسیر معقول برای mac/Linux آتی است، نه جایگزین الان. سند: `before-pack.js`, STATE §4.41. |
| IPC / contextIsolation | ⚪ درست بود | `contextIsolation:true, nodeIntegration:false` از قبل تنظیم است. سند: `electron/main.ts` (webPreferences). |
| پورت 8742 هاردکد | ⏸ | پذیرفته؛ port-discovery به‌دلیل cross-cutting بودن موکول شد. mitigations فعلی: بنر «بک‌اند پایین است» + `/api/health`. سند: `config.py`, `runtime.ts`. |

### ۲. موتور رندر
| بند | وضعیت | پاسخ دفاعی |
|---|---|---|
| probe واقعی encoder = تصمیم عالی | ⚪ تأیید | بله؛ به‌جای لیست استاتیک، هر encoder واقعاً یک فریم encode می‌کند. سند: `gpu.py`, `test_gpu.py`. |
| filter_complex تک‌thread است؟ | ⏸ | FFmpeg خود داخل هر filter graph را multi-thread می‌کند؛ رندر قطعه‌ای (segment+concat) برای resume در نقشه است (استاد۲ A1). مخلوط CPU/GPU acknowledged. |
| typed-ffmpeg | 🔴 | compose.py دستی است اما **تست‌شده** (کاما escape، geometry، duration)؛ typed-ffmpeg روی PyPI **مجوز اعلام‌نشده** دارد و وابستگی جدید می‌افزاید که با قاعده‌ی «هیچ‌چیز که import نشود ship نمی‌شود» (§4.34) در تضاد است. |

### ۳. UI/UX
| بند | وضعیت | پاسخ دفاعی |
|---|---|---|
| کیبورد نداریم! | ✅ | پایه از قبل بود (Space/Ctrl+Z/S/Delete/Arrow)؛ حالا **J/K/L و `,`/`.`** اضافه شد. سند: `Studio.tsx`. |
| Waveform نمایش نمی‌شود! | ⚪ اشتباه استاد | waveform و film-strip روی timeline از قبل هست. سند: `Timeline.tsx`, STATE §1. |
| Video player UX | ⚪ | پیش‌نمایش «دوقلوی CSS» کامپوزیتور است؛ هر افکت در مانیتور دیده می‌شود. سند: `preview.ts`, `test:playback`. |
| Timeline zoom | ⚪ داشتیم | Ctrl+wheel zoom از قبل؛ پن مرکزی موکول. سند: §4.28. |
| Right-click context menu | ⏸ | نیست؛ پیشنهاد معتبر، برای نسخه‌ی بعد. |

### ۴. AI
| بند | وضعیت | پاسخ دفاعی |
|---|---|---|
| race قوانین+LLM بالغ است | ⚪ تأیید | بله؛ تساوی با قوانین. سند: `race.py`. |
| Ollama latency / timeout | ⚪ | timeout=120 و fallback به قوانین که **همیشه** می‌دوند؛ کاربر هرگز منتظر مدل نمی‌ماند. سند: `race.py`. |
| UI دانلود مدل Whisper | ⚪ داشتیم | کارت AI در Settings دانلود+progress دارد. سند: `AiRuntimeCard`. |

### ۵. Testing
| بند | وضعیت | پاسخ دفاعی |
|---|---|---|
| known-answer fixture حرفه‌ای | ⚪ تأیید | بله. |
| E2E با Playwright | ⏸ | سوئیت مرورگری puppeteer از قبل هست (test:ui / test:playback با Chromium واقعی)؛ Playwright افزونه‌ی اختیاری است نه جایگزین. |
| تست رگرسیون performance | ⏸ | معتبر؛ به‌دلیل ناپایداری زمان در CI موکول (با سقف سخیف). |

### ۶. Packaging/CI
| بند | وضعیت | پاسخ دفاعی |
|---|---|---|
| macOS/Linux | ⏸ | NSIS فقط Windows؛ electron-builder برای mac/Linux وقتی بازار ایجاب کند. |
| امضای auto-update | ⚪ | electron-updater **SHA-512** کل installer را قبل از اجرا verify می‌کند و در عدم تطابق full download می‌کند. سند: STATE §4.65. |

### ۷. Security
| بند | وضعیت | پاسخ دفاعی |
|---|---|---|
| CORS wildcard | ✅ | به allowlist (dev originها + origin مبهم بسته) بدون credentials تغییر کرد + تست origin خارجی. سند: `main.py`, `test_security.py`. |
| path traversal | ✅ | نگهبان: null-byte/نسبی → 400. سند: `media.py`, `test_security.py`. |

### ۸. edit model
| بند | وضعیت | پاسخ دفاعی |
|---|---|---|
| اعتبارسنجی overlap در commit | ⚪ | overlap در مدل **اعمال** می‌شود نه فقط ادعا: `resolvePlacement` کلیپ را به شکاف آزاد clamp می‌کند. سند: `model.ts`. |
| opacity غایب → tooltip | ⏸ | حذف opacity منطقی است؛ tooltip توضیحی افزودنیِ کوچک است. |

---

## استاد دوم (بندهای A–J)

**A) معماری/موتور:** ۱ رندر قطعه‌ای ⏸ · ۲ dump فیلتر در Doctor ⏸ (ارزشمند) · ۳ LUT3D ⏸ · ۴ randomUUID ⏸ (در حال حاضر `Math.random`؛ بهبود کوچک) · ۵ undo diff-based ⏸ (فعلاً snapshot کامل، درست ولی پرمصرف) · ۶ شکستن model.ts ⏸ · ۷ CSS-twin حفظ + WebGPU فقط برای افکت‌های غیرممکن ⚪ · ۸ تخمین زمان رندر ⏸ · ۹ media hash + find-missing ⏸ · ۱۰ MLT فقط fallback 🔴(LGPL).

**B) AI:** P0: TransNetV2 ⏸(torch) · silero پیش‌فرض پس از Measure ⚪/⏸ · حذف filler ✅ **انجام شد**. P1: DeepFilterNet 🔴 تا شفاف‌سازی مجوز(NOASSERTION) · pyloudnorm ⏸ · noisereduce ⏸ · whisperX ⏸(torch) · CLIP ⏸. P2: demucs/RIFE/rembg/Real-ESRGAN/ffsubsync ⏸ on-demand · OTIO 🟢 (Apache، برای تبادل).

**C) UI/UX:** tour اولیه ⏸(برای ۱.۰) · design tokens ⏸ · timeline مغناطیسی ⏸ · keyframe drawer ⚪(هست) · empty states ⏸ · proxy badge ⚪ · Style-DNA share ⏸ · scoreboard همیشه ⚪(هست) · honesty badges ⚪ · a11y ⏸ · مطالعه OpenCut/FableCut/nugget/openreel ⚪(مطالعه، نه import).

**D) دستیار/MCP:** MCP read-only ⏸ (ایده‌ی معتبر برای پایان‌نامه/آینده) · dry-run+undo+نام provider ⚪(هست) · intent در chat ⚪.

**E) Debug/CI/۱.۰:** sentry ⏸ · Playwright بسته‌بندی‌شده ⏸ · golden frame hashes ⏸ · chaos tests ⏸ · act ⏸ · OTel ⏸ · ratchet نسخه ⚪ · smoke ⚪ · نصب فیلم‌گرفته ⏸ · crash dialog ⏸.

**F) توزیع:** جداکردن repo ⏸(استراتژیک) · هرگز release قدیمی پاک نشود ⚪(رعایت می‌شود) · gallery/brand kit ⏸ · batch queue ⏸ · OTIO-Resolve ⏸ · preset packs ⚪(sports pass تعمیم‌پذیر).

**G) ترتیب:** با ترتیب پیشنهادی موافقیم؛ هفته۱-۲ = TransNetV2+silero+filler(✅)+dump — filler انجام شد.

**H) تغییرناپذیرها:** همه رعایت می‌شود (۵ کانال، CSS-twin، task over /ws، degrade، ratchet، برچسب تولیدی، تساوی=قوانین، export از اصل، مجوز از METADATA). ✅

**I) لینک‌ها:** همه استعلام شد (جدول مجوز در نشست قبل).

**J) استراتژی یک‌خطی:** کاملاً هم‌راستا: برنده‌شدن با AI محلیِ اندازه‌گیری‌شده + Style Match + ادیتور صادقِ فارسی؛ GPL وارد فرآیند نشود.

---

## اگر استاد چالش کرد، جواب‌های کلیدی

۱. **«چرا PyInstaller نه؟»** → آپدیت تفاضلی به payload بایت‌پایدار نیاز دارد؛ PyInstaller باینری غیرقطعی و AV-حساس می‌سازد.
۲. **«waveform نداشتی؟»** → داشتیم؛ `Timeline.tsx` + `test:playback` آن را چک می‌کند.
۳. **«چرا typed-ffmpeg نه؟»** → compose تست‌شده است و typed-ffmpeg مجوز اعلام‌نشده + وابستگی جدید دارد.
۴. **«DeepFilterNet چرا نه؟»** → روی GitHub **NOASSERTION** است؛ تا شفاف‌سازی مجوز وارد نمی‌شود (دقیقاً انضباط مجوز ما).
۵. **«پورت هاردکد؟»** → پذیرفته؛ port-discovery موکول، ولی خرابی بک‌اند همیشه به کاربر اعلام می‌شود.

---

## پیوست: کل دریافتی‌ها از هر دو استاد، مرتب‌شده بر اساس ارزش

> وضعیت: ✅ انجام شد · ⚪ از قبل داشتیم · ⏸ موکول · 🔴 رد.
> ترتیب از بیشترین ارزش به کمترین است؛ موارد تکراریِ دو لیست یکی شده‌اند.

### رده‌ی ۱ — ارزش بحرانی (امنیت/اعتمادپذیری/هسته‌ی UX)
1. ✅ CORS lockdown — wildcard+credentials → allowlist + تست.
2. ✅ path-injection guard — null-byte/نسبی → 400.
3. ✅ میان‌برهای کیبورد J/K/L + `,`/`.` (پایه از قبل).
4. ⏸ Port discovery به‌جای 8742 هاردکد — جلوگیری از crash خاموش.
5. ⏸ Crash reporting / sentry-electron — ستون ۱.۰.
6. ⚪ Ollama timeout + fallback به قوانین (از قبل).
7. ⚪ Waveform + film-strip روی timeline (از قبل؛ استاد۱ ندیده بود).
8. ✅ حذف کلمات پرکننده EN+FA.

### رده‌ی ۲ — ارزش بالا (UX حرفه‌ای / کیفیت)
9. ⏸ Right-click context menu روی کلیپ.
10. ⏸ تست رگرسیون performance (با سقف سخیف).
11. ⏸ OTIO export/import — تبادل حرفه‌ای (Apache).
12. ⏸ First-run tour — آنبوردینگ ۱.۰.
13. ⏸ Playwright E2E کنار سوئیت puppeteer فعلی.
14. ⏸ Color scopes (waveform/vectorscope) برای grading.
15. ⏸ TransNetV2 — تشخیص ترنزیشن واقعی (torch، on-demand).
16. ⏸ Practical-RIFE — اسلوموی واقعی برای ورزش.

### رده‌ی ۳ — ارزش متوسط (نگهداری/توسعه)
17. ⏸ typed-ffmpeg — فقط اگر compose از تست خارج شد (الان نه).
18. ⏸ whisperX — word-alignment کارائوکه (torch).
19. ⏸ CLIP — تطبیق معنایی نما برای Style Match.
20. ⏸ demucs — stems و بِد تمیز.
21. ⏸ Real-ESRGAN / rembg — upscale و حذف پس‌زمینه (on-demand).
22. ⏸ ffsubsync — سینک زیرنویس خارجی.
23. ⏸ undo diff-based / شکستن model.ts / randomUUID — بهداشت کد.
24. ⏸ LUT3D + تخمین زمان رندر + media-hash/find-missing.
25. ⏸ MCP read-only برای دستیار — ایده‌ی آینده.
26. ⏸ a11y + design tokens + empty states.
27. ⏸ macOS/Linux packaging — استراتژیک.

### رده‌ی ۴ — مطالعه/الهام (نه import)
28. ⚪ OpenCut / FableCut / nugget / openreel / movielite — مطالعه‌ی UX/الگو.
29. ⚪ wavesurfer.js / peaks.js — ایده‌ی waveform غنی‌تر.
30. ⚪ shadcn/Radix/Zustand/Immer — Zustand داریم؛ بقیه اختیاری.
31. ⚪ PyInstaller — مسیر mac/Linux آتی، نه جایگزین الان.

### رده‌ی ۵ — رد (مجوز/تضاد با انضباط)
32. 🔴 aubio (GPL) · peaks.js درون‌فرآیند (LGPL) · mlt درون‌فرآیند (LGPL).
33. 🔴 video-timeline-editor (بدون مجوز).
34. 🔴 DeepFilterNet تا شفاف‌سازی (NOASSERTION).
35. 🔴 YOLO/ByteTrack (AGPL).
36. 🔴 librosa — closure ~94MB؛ beat detection خودمان داریم.
