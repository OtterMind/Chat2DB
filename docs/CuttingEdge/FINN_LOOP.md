# Finn-Loop — حلقه‌ی بازسازی خودکار Cutting Edge

> **قانون طلایی:** هیچ مرحله‌ای روی سندباک نمونه گم نمی‌شود — هر مرحله که لوپ تمام
> می‌شود، خروجی‌اش توسط node گیت‌هاب به `arena/01a032fb-chat2db` کامیت می‌شود.
> یک بار ورک‌فلو را می‌سازی و اجرا می‌کنی؛ دفعات بعد هر مرحله توسط **n8n-workflows**
> ساخته می‌شود. هیچ کدی روی خود برنامه تا فرمان تو اجرا نمی‌شود.

## حلقه (هر ردیف = یک subworkflow که با «کامیت به گیت‌هاب» تمام می‌شود)

| # | مرحله | ابزارِ الگو | خروجی که کامیت می‌شود |
|---|---|---|---|
| 1 | Plan | Find Skills · Awesome Claude Design | `docs/plans/<stage>.md` |
| 2 | Design | Design.md (رنگ/تایپو) · 21st.dev (کامپوننت) · Premium Design System | `design-tokens.css` + spec |
| 3 | Motion | Framer Motion · Design Motion Principles (فیزیک فنر/easing) | `motion.spec.json` |
| 4 | Build | Codex (استاندارد کد) | کد مرحله |
| 5 | Auto Debug Engine | اجرای `pytest`+`verify`، تشخیص و اصلاح خودکار | لاگ + fix |
| 6 | Security Scanner | ۱۰ چک امنیتی (path gate, licence gate, …) | `security.report.json` |
| 7 | Performance Watchdog | ۴۰۰+ متریک + Core Web Vitals | `perf.report.json` |
| 8 | Visual Quality Gate | اسکرین‌شات vs مرجع (SSIM) | `visual.report.json` |
| 9 | Animation Quality Gate | ۱۸ چک‌پوینت انیمیشن | `animation.report.json` |
| 10 | Taste Skill | quality-gate جلوگیری از رجعت سلیقه | gate pass/fail |
| 11 | Self-Healing | در خطا: تشخیص → retry با patch | fix commit |
| 12 | Publish | آپدیت تفاضلی (blockmap) → **یک پچ** | رلیز |

## قابلیت‌های «کلاس جهانی» که به‌صورت مرحله وارد لوپ می‌شوند
Landing سینمایی (gradient mesh + particles + cursor spotlight) · Celebration
(confetti/level-up/streak) · Gesture (swipe/pinch/long-press/drag) · Command Palette
(Cmd+K مثل Linear) · Ambient Animations.

## مدل اجرا (OmniRoute)
مسیریابی تسک‌ها: هر مرحله به سبک‌ترین ارائه‌دهنده‌ی موجود سپرده می‌شود
(نردبان gateway→ollama→… که در 1.0 ساخته شد)؛ یعنی OmniRoute نقش **scheduler/Router**
لوپ را دارد و n8n نقش **ارکستراتور مرحله‌ها**.

## تضمین‌ها
- هر مرحله قبل از کامیت، گیت‌های 5–10 را پاس کند وگرنه Self-Healing فعال می‌شود.
- خروجی نهایی همیشه **یک پچ تفاضلی** است (electron-updater + blockmap).

ورکفلوی قابل‌واردکردن: `ce-app/ci/finn-loop.n8n.json`.

---

# Finn-Loop v2 — سر‌تیترهای نهایی (شامل پیشنهادهای خودم)

فازها: ۰ ممیزی خط‌به‌خط (Inventory/Dead-code/Cohesion/Baseline) · ۱ Landing(D) ·
۲ موشن Style Match · ۳ موشن Editor · ۴ یکپارچگی+سرعت (event-bus/کش/prefetch/تزریق MIT) ·
۵ دیباگ خودکار (per-file/AutoDebug/Mutation) · ۶ Security(۱۰) · ۷ Performance(CWV+60fps+bundle) ·
۸ دکمه‌به‌دکمه (Inventory/E2E/گزارش) · ۹ Visual(SSIM) · ۱۰ Animation(۱۸) · ۱۱ Taste ·
۱۲ Self-Healing · ۱۳ پیشنهادهای من (Contract tests/Snapshot bank/Feature-flag/i18n-RTL/a11y/bundle-gate/افزایشی/Changelog) · ۱۴ Publish(پچ تفاضلی).

منبع حقیقت: `ce-app/ci/finn-loop.manifest.json` (۴۵ مرحله با gate و done:false) و
`ce-app/ci/finn-loop.n8n.json` (۹۱ node؛ هر مرحله run + یک github.createFile که
`docs/loop/done/<id>.done` می‌سازد).

# تضمینِ «هیچ مرحله فراموش نمی‌شود»
1. **لوپ داده است، نه حافظه:** فهرست مرحله‌ها در manifest داخل مخزن است؛ هر اجرا آن را
   می‌خواند، نه یادِ من را.
2. **ادامه از اولین done-نشده:** هر اجرا اولین مرحله‌ای که `docs/loop/done/<id>.done`
   ندارد را برمی‌دارد؛ پس حتی پس از wipe سندباکس یا قطع جلسه، از همان‌جا ادامه می‌شود.
3. **هر مرحله = gate + کامیت:** مرحله فقط وقتی done می‌شود که gate پاس شود و کامیت
   `*.done` به گیت‌هاب برسد؛ یعنی ردِ هر مرحله روی گیت‌هاب ماندگار است.
4. **n8n همان manifest را اجرا می‌کند:** ترتیب در ورکفلو hard-coded است؛ پرش ممکن نیست.
5. **گزارش پایانی:** پس از هر دور، خلاصه‌ی done/pending از manifest تولید و کامیت می‌شود
   تا همیشه ببینی چه مانده.
