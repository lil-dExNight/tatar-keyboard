# keyboard / compat / event / accessibility — ревизия текстов

Область: `app/src/main/java/rkr/simplekeyboard/inputmethod/{keyboard,keyboard/internal,compat,event,accessibility}` — 54 файла, 14 901 строк (в основном Java-наследие AOSP/Simple Keyboard; новый код — 3 Kotlin-файла `accessibility/`, `keyboard/internal/GlideTrail.kt`).

## Итог

Файлы почти целиком апстримные (AOSP LatinIME / Simple Keyboard). Проектный текст — это тонкий слой комментариев, вставленных поверх апстрима, и он весь опознаётся по одному шаблону: **код миссии + дата аудита + ссылка на `docs/*.md`**, обёрнутые вокруг по сути полезного технического пояснения. Доля комментариев в проектно-правленых файлах высокая (PointerTracker 216/1156 ≈ 19 %, KeyboardView 140/586 ≈ 24 %, KeyboardTextsTable 560/722 — но это генераторные таблицы, MainKeyboardView 138/758, Key 188/929, ExploreByTouchHelper 574/1248), но большая часть — исходный апстримный javadoc, который не трогаем.

Находок (только проектный текст): **[JARGON] ~28** (коды `P7-2/M4/O2/O5/S1/W5/E2a` и ссылки на `docs/*.md`), **[AGENT] ~12** (датированные «2026-09-xx audit, Fнн», «field report», «error-prone LongFloatConversion», «verified on the emulator»), **[STALE] 1** (`KeyboardActionListener.onGlideInput` — «nothing commits until then», хотя приёмник уже подключён), **[SLOP] ~6** («byte-identical», «fail-closed» к косметике, мета-комментарии про TODO), **[LANG] 2** (кириллическая «Р-1», русско-английский дубль в KeyboardTextsTable), **[DUP] 1**, **[WEIRD] 2** (закомментированный код — апстримный). **[DELETE]/[DEAD]: 0** — все файлы и методы используются, glide/offscreen-буфер/accessibility-делегаты подключены (проверено grep'ом).

Топ-3 действия: (1) массово срезать префиксы `Xn (docs/…-2026-…md):` и «(2026-09-xx audit, Fнн)» из комментариев, оставив техническое пояснение — доки планируются в архив, ссылки станут битыми; (2) починить один [STALE] в `KeyboardActionListener`; (3) свести дублирующийся русско-английский заголовок `KeyboardTextsTable` к одному языку.

Контракт-тесты, читающие эти файлы как текст (`AppleUxStageBContractTest`, `AppleUxBatchOneContractTest`, `PointerTrackerRobustnessContractTest`, `KeyboardViewDrawLoopContractTest`, `KeyPreviewDismissAnimatorSourceContractTest`, `SubtypeSwitchAnnouncementSourceContractTest`), пинят **код** (сигнатуры методов, тела, строковые константы), а не прозу комментариев. Значит, чистка комментариев ниже безопасна. Единственная оговорка: `KeyboardViewDrawLoopContractTest` делает `assertFalse(keyboardView.contains("measureText("))` и `…contains("HashSet")` — при переписывании комментариев в `KeyboardView.java` эти токены нельзя вносить в текст.

## Файлы/код на удаление

Кандидатов на удаление в этой области нет. Проверка использования:

| Символ/файл | что проверено | вывод |
|---|---|---|
| glide-ветки в `PointerTracker` / `KeyboardActionListener.onGlideInput` | grep `onGlideInput` → `LatinIME:2367`, `SuggestionsController:1841`, `PointerTracker:893` | подключено, не мёртвое |
| `KeyboardView.mOffscreenBuffer`, `mInvalidatedKeys` | `KeyboardViewDrawLoopContractTest` пинит draw-loop | живой, под тестом |
| `compat/ExploreByTouchHelper`, `FocusStrategy` | используются `accessibility/*Delegate.kt` (форк вместо androidx) | живой |
| `TEXTS_tt` (нет) → `"tt", TEXTS_ru` | grep по `KeyboardTextsTable` | сознательно, tt наследует ru |
| закомментированный код (`//mStartY = y;` PointerTracker, «Uncomment to log» KeyboardTextsTable) | апстримный, не проектный | не трогать в рамках этой ревизии |

## Правки

### keyboard/PointerTracker.java (216/1156 комм.)

- `PointerTracker.java:131` — [JARGON] — «P7-2 glide (docs/GLIDE-PLAN.md): the per-pointer decision machine …» — префикс кода миссии + ссылка на док. Само пояснение (ленивая аллокация буфера, инвариант) полезно. — **Замена:** убрать `P7-2 glide (docs/GLIDE-PLAN.md):`, начать с «Per-pointer glide decision machine (rebuilt on keyboard change; its distance threshold is the key width) plus the lazily-allocated path buffer…». P3.
- `PointerTracker.java:139` — [SLOP] — «below falls through to the legacy code, byte-identical.» — «byte-identical» — самолюбование; достаточно «the touch path is unchanged». P3.
- `PointerTracker.java:315` — [JARGON] — «a quarter of the key width (P7-2's field fix: a resting finger must not start the detection clock — docs/ROADMAP-P7.md).» — код миссии + док. — **Замена:** «…a quarter of the key width, so a resting finger does not start the detection clock.» P2.
- `PointerTracker.java:524` / `:532` — [SLOP][JARGON] — «P7-2 multi-touch rule: a second finger down cancels any armed glide (fail-closed — …)» и «/** P7-2: cancels the armed glide … (fail-closed). */» — «fail-closed» здесь не по делу (это UX-правило мультитача, не защита). — **Замена:** снять `P7-2`/`(fail-closed)`, оставить «A second finger cancels any armed glide (it is never committed); an undecided gesture is left alone so two-finger chording keeps working.» P2.
- `PointerTracker.java:574` — [AGENT][JARGON] — «2026-09-25 audit, F2: mCursorMoved describes THIS gesture's space/delete swipe. It used to reset only on a plain up…» — датированная сноска + код находки; дальше история бага. — **Замена:** «mCursorMoved marks THIS gesture's space/delete swipe; reset here at down (and at cancel) so a cancelled swipe cannot leak into the next touch.» P1. ⚠ не пинится (тест проверяет строку кода `mCursorMoved = false;`, а не комментарий).
- `PointerTracker.java:629` — [AGENT] — «…the implicit long->float narrowing here is what error-prone's LongFloatConversion flags (2026-09-24 audit, F12).» — упоминание инструмента + дата/код. — **Замена:** «The cast is explicit: float timestamps store millisecond deltas, exact within the 24-bit mantissa.» P2.
- `PointerTracker.java:958` — [JARGON] — «P7-2 field fix (docs/ROADMAP-P7.md): the long-press actually FIRES for a still-undecided tracker…» — код+док. Пояснение важное. — **Замена:** убрать `P7-2 field fix (docs/ROADMAP-P7.md):`. P3.
- `PointerTracker.java:1015` — [AGENT][JARGON] — «2026-09-25 audit, F13: the queue's cancel-all now EVICTS the trackers as it cancels them…» — дата+код. — **Замена:** «The queue's cancel-all evicts trackers as it cancels them, so the phantom-up wave rides inside the same call; a releaseAllPointers after it would iterate an empty queue.» P1. ⚠ не пинится (`PointerTrackerRobustnessContractTest` проверяет код-строки).
- `PointerTracker.java:1055` — [JARGON] — «…as reliable as its centre. See docs/SYMBOL-KEY-EDGE-FIX.md.» — ссылка на док (задублирована в `KeyDetector.java:83`). — **Замена:** удалить «See docs/…md» (объяснение выше самодостаточно). P3.

Оставить как есть: `:592` («Upstream AOSP never meets this…») — техническое и верное; `:315-318` инвариант glide; апстримные TODO.

### keyboard/KeyboardView.java (140/586 комм.)

- `KeyboardView.java:117` — [JARGON] — «/** M4: cached icon tint for ACTION keys… */» — код миссии. — **Замена:** «/** Cached icon tint for ACTION keys, see {@link #actionIconFilter(int)}. */». P3.
- `KeyboardView.java:227` — [AGENT] — «…the null mOffscreenBuffer (2026-09-25 audit). At 0×0 there is nothing to draw anyway.» — датированная сноска. — **Замена:** убрать «(2026-09-25 audit)». P1.
- `KeyboardView.java:231` — [JARGON][OVERLONG] — «O2 (docs/OPTIMIZE-2026-09-25.md): the offscreen buffer now serves the hardware-accelerated path too…» — 8-строчный абзац с кодом миссии и ссылкой; объясняет историю смены модели отрисовки. — **Замена:** снять `O2 (docs/…):`, сжать до 2–3 строк по сути («The offscreen buffer serves the HW path too: an invalidation redraws only invalidated keys into a persistent bitmap; the frame is one drawBitmap. The buffer's canvas reports !isHardwareAccelerated(), so onDrawKeyboard's software logic applies verbatim.»). P2. ⚠ при переписывании не вносить токены `measureText(`/`HashSet` (пинятся `KeyboardViewDrawLoopContractTest`).
- `KeyboardView.java:293` — [SLOP] — «…the old \"draw all keys…\" branch is dead … and its TODO is resolved by it.» — мета-комментарий про апстримный TODO. — **Замена:** «This canvas is always the offscreen bitmap's (see onDraw), so the software path always applies.» P3.
- `KeyboardView.java:489` — [JARGON] — «M4 (docs/APPLE-UX-2026-09-25.md): an ACTION key is accent-filled, so its icon…» — код+док. — **Замена:** снять `M4 (docs/…):`, оставить «An ACTION key is accent-filled, so its icon (baked with the functional colour) is tinted to the action colour for this draw only…». P3.
- `KeyboardView.java:506` — [JARGON] — «…the SELECTED alternative's glyph (M3, docs/APPLE-UX-2026-09-25.md); everything else follows the key.» — убрать `(M3, docs/…)`. P3.

### keyboard/internal/KeyboardTextsTable.java (генераторные таблицы)

- `KeyboardTextsTable.java:28` — [LANG][DUP][JARGON][AGENT] — русский блок «ВНИМАНИЕ: файл исторически генерировался … С 2026-08-30 (…docs/RESTRUCTURE.md фаза 3б)…» дублирует по смыслу английский javadoc сразу ниже (`:35`). Смешение языков + дата + ссылка на док. — **Замена:** удалить русский блок целиком, оставить один английский javadoc (или наоборот — по выбору оператора, но не оба). P2.
- `KeyboardTextsTable.java:693` — [AGENT][JARGON] — «Phase 3b (2026-08-30): only the three shipped locales remain…» — код фазы + дата. — **Замена:** «Only the three shipped locales remain; every other locale falls back to TEXTS_DEFAULT.» P2.
- `KeyboardTextsTable.java:698` — [AGENT][JARGON] — «U1+U2 (2026-08-31, docs/AUDIT-2026-08-31.md): Tatar inherits the Russian table wholesale…» — код находки+дата+док; но само пояснение (почему нет TEXTS_tt) ценное. — **Замена:** снять `U1+U2 (2026-08-31, docs/AUDIT-2026-08-31.md):`, оставить «Tatar inherits the Russian table wholesale (АБВ alpha key, ₽, „" quotes); rowkeys_tatar*.xml reference no !text/ names, so no separate TEXTS_tt.» P2. ⚠ проверить `KeyboardTextsTableTatarTest` — пинит поведение `getText`, не комментарий (grep: тест не цитирует этот текст).

### keyboard/KeyboardSwitcher.java (99/640 комм.)

- `KeyboardSwitcher.java:63` — [JARGON][LANG] — «…the surface jumps when the two swap (docs/DEVICE-RESEARCH-GEOMETRY.md, Р-1).» — ссылка на док + кириллическая «Р-1» в англ. тексте. — **Замена:** удалить «(docs/DEVICE-RESEARCH-GEOMETRY.md, Р-1)». P2.
- `KeyboardSwitcher.java:136` — [JARGON][AGENT] — «O5 (docs/OPTIMIZE-SECURITY-PLAN-2026-09-29.md): the per-layout build. A Trace begin/end pair costs ~10 µs…» — код+док. — **Замена:** снять `O5 (docs/…):`, оставить «Trace span for the per-layout build; markers wrap only coarse spans (a begin/end pair costs ~10 µs).» P3.
- `KeyboardSwitcher.java:420` — [JARGON] — «…{@link InputView#showEmojiPanel} (docs/EMOJI-PANEL-SPACE-2026-09-28.md, item B).» — убрать хвост «(docs/…md, item B)». P3.
- `KeyboardSwitcher.java:425` — [JARGON] — «// O5: the panel-open span; same ~10 µs marker-pair discipline as loadKeyboard.» — снять `O5:`. P3.
- `KeyboardSwitcher.java:543` — [JARGON] — «…the emoji-cluster-aware backspace from E2a applies here too.» — код миссии `E2a` без расшифровки. — **Замена:** «…the emoji-cluster-aware backspace applies here too.» P3.

### keyboard/MainKeyboardView.java (138/758 комм.)

- `MainKeyboardView.java:347` — [JARGON][OVERLONG] — «M2 (docs/APPLE-UX-2026-09-25.md): on iOS a LETTER key answers a tap with the balloon ONLY … (P7-5, PointerTracker.updateGlideFeedback).» — код+док, 7 строк. Пояснение полезно. — **Замена:** снять `M2 (docs/…):` и `(P7-5, …)`, оставить суть: «A LETTER key answers a tap with the balloon only and does not darken; functional keys keep the pressed fill. If the balloon cannot appear (popup off / no preview) the fill stays. The glide highlight arrives with withPreview == false and still presses the key.» P2. ⚠ `AppleUxStageBContractTest.b5*` пинит код (`balloonCarriesTheFeedback`, `key.onPressed()`), не комментарий — безопасно.

### keyboard/Key.java (188/929 комм.)

- `Key.java:583` — [JARGON] — «/** M4: the drawing side needs to know an ACTION key to tint its icon. */» — снять `M4:`. P3.
- `Key.java:590` — [JARGON] — «M4 (docs/APPLE-UX-2026-09-25.md): an ACTION key is filled with the accent colour…» — снять `M4 (docs/…):`, оставить «An ACTION key is accent-filled, so its glyph flips to the action colour — checked before the functional colour…». P3. ⚠ `AppleUxStageBContractTest.b2*` пинит код (`mActionKeyTextColor` порядок), не комментарий.

### keyboard/internal/GlideTrail.kt (проектный Kotlin)

- `GlideTrail.kt:20` — [JARGON] — «The visual tail of an armed glide (docs/ROADMAP-P7.md, P7-5): a fixed-capacity ring…» — убрать «(docs/ROADMAP-P7.md, P7-5)». P3.
- `GlideTrail.kt:28` — [SLOP] — «…the decoder's fail-closed argument does not apply to a cosmetic layer.» — верно по сути, но «fail-closed argument» — жаргон; можно «the decoder's safety argument does not apply to a cosmetic layer». P3.
- `GlideTrail.kt:30` — [AGENT][JARGON][OVERLONG] — «P7-7 (2026-09-25): the tail grew (300 ms / 96 points…) and the lift no longer erases it instantly…» — код+дата, историческая правка «tail grew». — **Замена:** снять `P7-7 (2026-09-25):` и «grew», описать текущее поведение: «After the lift, startFadeOut freezes the ring and the draw alpha decays to zero over FADE_OUT_MS (the one wall-clock read, via SystemClock). A new gesture's first point clears the fading ring; isFadeDone tells the view when to stop re-invalidating.» P2.
- `GlideTrail.kt:44` — [JARGON] — «/** P7-7: the lift started the post-gesture fade… */» — снять `P7-7:`. P3.

### keyboard/KeyboardActionListener.java

- `KeyboardActionListener.java:85` — [STALE][JARGON] — «Called when a glide gesture completes (P7-2, docs/GLIDE-PLAN.md). … The default is a no-op: P7-3 wires the real receiver in LatinIME; nothing commits until then.» — приёмник уже подключён (`LatinIME.onGlideInput:2367` → `SuggestionsController.onGlideInput:1841`), так что «P7-3 wires the real receiver … nothing commits until then» — устаревшее описание переходного состояния. — **Замена:** «Called when a glide gesture completes. [path] is the owning PointerTracker's live buffer, valid only during this call — a forwarding receiver must snapshot it (GlidePath#copyInto). The default is a no-op.» P1.

### keyboard/internal/ — точечные ссылки на доки

- `KeyPreviewBalloonDrawable.java:31` — [JARGON][AGENT] — «S1 of {@code docs/APPLE-UX-2026-09-25.md}: the key preview balloon as an iOS DROPLET…» и ниже «…a 122dp white bar across two key rows; verified on the emulator before this change.» — код+док + агентная фраза «verified on the emulator before this change». — **Замена:** снять `S1 of docs/…md:` (оставить «The key preview balloon as an iOS droplet instead of a rectangle.») и убрать хвост «verified on the emulator before this change». P2.
- `KeyPreviewChoreographer.java:75` — [JARGON] — «S1 (docs/APPLE-UX-2026-09-25.md): the Tatar theme's rectangular preview background is replaced by the path-drawn droplet.» — снять `S1 (docs/…):`. P3. ⚠ `KeyPreviewDismissAnimatorSourceContractTest`/`AppleUxStageBContractTest` пинят код (`new KeyPreviewBalloonDrawable(context)`), не комментарий.
- `KeyboardIconsSet.java:38` — [JARGON] — «/** M1 (docs/APPLE-UX-2026-09-25.md): one-shot shift — filled arrow, no caps bar. */» — снять `M1 (docs/…):`. P3.
- `KeyDetector.java:83` — [JARGON] — «…where inside the key the press landed. See {@code docs/SYMBOL-KEY-EDGE-FIX.md}.» — удалить «See docs/…md» (дубль ссылки из PointerTracker:1055; пояснение выше самодостаточно). P3.
- `TimerHandler.java:35` — [JARGON] — «…does not resurrect the defect (docs/SUGGEST-DIES.md).» — убрать «(docs/SUGGEST-DIES.md)»; пояснение про MSG id полезно, оставить. P3. ⚠ `HandlerMessageIdSourceContractTest` пинит константы `MSG_*` (код), не комментарий.
- `MoreKeysKeyboardView.java:52` — [JARGON] — «M3 (docs/APPLE-UX-2026-09-25.md): the glyph colour of the SELECTED alternative…» — снять `M3 (docs/…):`. P3.

### compat/ (форк androidx — пояснение ценное, префикс — нет)

- `compat/ExploreByTouchHelper.java:14` — [JARGON][AGENT] — «O2 (2026-09-25, docs/OPTIMIZE-2026-09-25.md): forked from androidx.customview:customview:1.1.0 …» — код+дата+док. Само описание форка (зачем файл, таблица маппинга) — полезно, оставить. — **Замена:** заменить префикс на «Forked from androidx.customview:customview:1.1.0 (AOSP) and ported to framework APIs to drop the androidx dependency.» P2.
- `compat/ExploreByTouchHelper.java:845` — [JARGON] — «// PORT NOTE (O2): the androidx original walked the virtual-parent chain here…» — снять `(O2)`, оставить «PORT NOTE:». P3.
- `compat/FocusStrategy.java:14` — [JARGON][AGENT] — «O2 (2026-09-25, docs/OPTIMIZE-2026-09-25.md): forked from androidx.customview…» — как выше: заменить на «Forked from androidx.customview:customview:1.1.0 (AOSP); androidx annotations dropped with the dependency. Framework FocusStrategy needs API 26; minSdk is 24.» P2.

### accessibility/ (проектный Kotlin — практически чисто)

Три Kotlin-файла (`KeyDescriptionMapper.kt`, `KeyboardAccessibilityDelegate.kt`, `MoreKeysKeyboardAccessibilityDelegate.kt`) написаны хорошо: комментарии по делу, без кодов миссий и дат. Мелочи:

- `KeyboardAccessibilityDelegate.kt:150` — [SLOP] (низкий) — «…Matches Gboard/LatinIME. Do not \"fix\" to letters-only.» — императив в комментарии; обоснование рядом достаточное, но фраза допустима (защищает от регрессии). Оставить или сжать до «Applies to every key, not just letters — restricting it would break lift-to-type on delete/shift.» P3.
- `event/`, `compat/EditorInfoCompatUtils.java`, `compat/PreferenceManagerCompat.java` — чистый апстрим/Simple Keyboard, проектных вставок нет. Правок не требуется.

## Системные паттерны

1. **Префикс «код миссии + (дата, docs/*.md)»** — доминирующий шум (~28 мест). grep-маркеры для массовой чистки в области:
   - `rg -n '\b(P7-[0-9]|O[0-9]|M[0-9]|S[0-9]|W[0-9]|U[0-9]|E[0-9][a-z]?)\b.*docs/' app/src/main/java/rkr/simplekeyboard/inputmethod/{keyboard,compat,event,accessibility}`
   - `rg -n 'docs/[A-Z0-9-]+\.md' <область>`
   **Правило:** удалить `Xn (docs/…-2026-…md):`-префикс и хвост `(docs/…md, item N)`, оставить одну строку о назначении кода. Доки уходят в архив — ссылки станут битыми.

2. **Датированные сноски аудита** (~12 мест): `rg -n '20[0-9]{2}-[01][0-9](-[0-9]{2})?\s*(audit|field report)?,?\s*F?[0-9]' <область>`.
   **Правило:** убрать «2026-09-xx audit, Fнн»/«field fix»/«field report»; если за сноской стоит реальное «почему», перефразировать в настоящем времени без даты и номера находки.

3. **Агентные/инструментальные упоминания**: «verified on the emulator before this change» (KeyPreviewBalloonDrawable), «error-prone's LongFloatConversion flags» (PointerTracker) — grep: `rg -n 'verified on the emulator|error-prone|LongFloatConversion|POCO'`. **Правило:** удалить — это следы процесса, не свойство кода.

4. **«fail-closed» / «byte-identical» как штамп**: `rg -n 'fail-closed|byte-identical'` в области (PointerTracker:139/524/532, GlideTrail:28). **Правило:** оставлять только там, где это буквально про безопасное поведение при ошибке; в UX-правилах и косметике — заменять на обычные слова.

5. **Смешение языков и кириллица в англ. тексте**: `KeyboardTextsTable.java:28` (русский блок-дубль англ. javadoc), `KeyboardSwitcher.java:63` («Р-1» кириллицей). **Правило:** один язык на комментарий; технические комментарии в этих файлах вести по-английски в тон апстриму (либо по-русски, но не оба сразу).

6. **Дублирующиеся ссылки на один док**: `docs/SYMBOL-KEY-EDGE-FIX.md` продублирован в `PointerTracker.java:1055` и `KeyDetector.java:83`. **Правило:** оставлять пояснение по месту, ссылку на док — убирать в обоих.

Общий приоритет: сперва P1 (`KeyboardActionListener` STALE; датированные сноски `KeyboardView:227`, `PointerTracker:574/1015`), затем P2 (сжать длинные исторические абзацы `KeyboardView:231`, `MainKeyboardView:347`, `GlideTrail:30`, дубль-заголовок `KeyboardTextsTable`, префиксы форк-нот в `compat/`), в конце P3 (косметика — срезка кодов миссий).
