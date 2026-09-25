# ERRORPRONE-TRIAGE — разбор находок error-prone

Дата: 2026-09-05. База: main `7529200a` (поверх 1.9.14).

`AGENTS.md` про error-prone говорит: находки остаются warning'ами, сборку не роняют,
«новые стоит разбирать по мере появления в выводе `compile*JavaWithJavac`». За всё
время их никто не разбирал — это и есть закрываемый долг. Здесь разобраны **все 64**.

Итог: **59 осталось, 5 закрыто**; из закрытых один — настоящий латентный дефект.

## Закрыто

### 1. NPE при ограничении цвета не-строкового типа (настоящий дефект)

`latin/settings/Settings.java` — `[EmptyCatch]` рядом, но нашлось хуже:

```java
String color = appRestrictions.getString(key);
if (color.startsWith("#")) {          // ← NPE
```

`Bundle.getString` возвращает null и когда ключа нет, и когда под ключом лежит
значение другого типа. Администратор, задавший `pref_keyboard_color` числом или
булевым, ронял **всю загрузку политик** с NPE, а не только эту настройку.

Починено проверкой на null; ветка, как и раньше, снимает ключ и оставляет дефолт.
Запинено `AppRestrictionsSourceContractTest` (2 теста, красный → зелёный проверен
откатом): загрузка политик принимает `Bundle` и `SharedPreferences.Editor`, ни того
ни другого на чистой JVM нет, поэтому контракт пинится на уровне исходника — как
остальные контракты этого пакета.

### 2. Проглатывание `NumberFormatException` без объяснения

Там же. Поведение верное — нечитаемый цвет не должен ронять политику, — но пустой
`catch` этого не говорил. Добавлен комментарий; `[EmptyCatch]` закрыт.

### 3. Целочисленное деление в float-выражениях

`keyboard/MainKeyboardView.java`, подпись языка на пробеле:

```java
final float baseline = height / 2 + textHeight / 2;   // height — int
canvas.drawText(language, width / 2, baseline - descent, paint);
```

`height / 2` и `width / 2` считались в int и теряли дробную часть, хотя результат
шёл во float. Ошибка до полупикселя в центрировании подписи. Исправлено на `/ 2f`.

### 4. Две ветки с одинаковым телом

`keyboard/KeyboardLayoutSet.java`: `TYPE_TEXT_VARIATION_FILTER` имел собственную
ветку, возвращавшую ровно `MODE_TEXT` — то же, что и общая. Ветка убрана,
поведение прежнее, намерение записано комментарием.

### 5. Приватный помощник, читавшийся как перегрузка `equals`

`keyboard/KeyboardId.java`: `private boolean equals(KeyboardId)` рядом с настоящим
`equals(Object)` — классическая ловушка чтения. Переименован в `equalsId`.

Побочно это поймал `EmojiKeySurfaceContractTest`: он режет исходник по литералу
`private boolean equals(` и покраснел на переименовании. Разделители обновлены —
ровно тот случай, ради которого контрактные тесты и пишутся.

## Оставлено осознанно (59)

| Класс | Шт. | Почему остаётся |
|---|---:|---|
| `EffectivelyPrivate` | 35 | Члены, которые могли бы быть `private`. Наследие форка AOSP; сужение видимости — механическая правка ~80 файлов с риском задеть рефлексию/тесты ради нуля пользы для пользователя |
| `ReferenceEquality` | 13 | **Проверены все 13 поимённо** — везде намеренное сравнение по идентичности: `this == o` в `equals`, `key == aKey` для экземпляров `Key`, `face == Typeface.DEFAULT` для синглтона. Ни одной строки не сравнивается через `==`. Ложные срабатывания для этого кода |
| `StringSplitter` | 2 | `String.split` без явного лимита в разборе внутренних форматов, где вход контролируется нами |
| `NonApiType` | 2 | Сигнатуры наследия форка |
| `MissingSummary` | 2 | Javadoc без summary-фразы |
| `UnusedMethod`, `MutablePublicArray`, `MissingOverride`, `InvalidParam`, `InlineTrivialConstant`, `Incubating` | по 1 | Стилевое наследие форка |

Правило на будущее прежнее и теперь подкреплено разбором: новые находки смотреть
по мере появления; классы `ReferenceEquality` и `EffectivelyPrivate` в этом коде
шумят и требуют проверки по месту, а не массовой правки.

## Гейты

| Гейт | Результат |
|---|---|
| `./gradlew test --rerun-tasks` | **1105 тестов, 0 падений** (1103 → +2) |
| python-конвейер (12 файлов) | OK (1 предсуществующий skip) |
| `./gradlew lintRelease` | BUILD SUCCESSFUL |
| `assembleRelease` + `check-no-internet` | оба уровня OK |
| error-prone | **64 → 59** предупреждений |

## Версия

**Не бампалась** — как в прежних миссиях класса CODE-FIX. Правки должны войти в
следующий релиз; на момент этой миссии не опубликованы ни 1.9.13, ни 1.9.14
(GitHub Release упирается в права токена `gh`), и третья неопубликованная версия
была бы шумом.

## Дополнение 2026-09-24 — +4 находки после фазы 7 (аудит 3.0.0)

База: main `b8de3916` (релиз 3.0.0). Фаза 7 (glide) принесла четыре новых
предупреждения; разобраны все четыре в рамках аудита 3.0.0
(`docs/AUDIT-2026-09-24.md`).

- **+3 × `LongFloatConversion`** — `keyboard/PointerTracker.java:590, 713, 717`:
  неявные расширяющие преобразования long → float в тач-пути глайда. Чинятся
  в волне F1 явными приведениями (explicit casts); поведение не меняется.
- **+1 × `ClassInitializationDeadlock`** — `keyboard/KeyboardActionListener.java:92`:
  `EMPTY_LISTENER` ссылается на собственный класс из инициализатора
  (self-reference). **Задокументировано как недостижимое на практике**: оба
  класса инициализируются только на UI-потоке, конкурентной инициализации
  не бывает; оставлено осознанно.

Итоговый счёт после волны F1: 59 + 1 = **60** предупреждений (три
`LongFloatConversion` сняты правкой).

## Addendum 2026-09-25 — sweep: 67 → 15 warnings

Base: main `a5b69776` (release 3.1.0). Enumerated from the compiler
(`compileDebugJavaWithJavac compileReleaseJavaWithJavac compileDebugUnitTestJavaWithJavac
--rerun-tasks`), not from this document: **67 warnings**. The running total of 60 above
had drifted after the O2 compat fork and the P7-7 glide fade (new: +2 `MissingSummary`,
+2 `NonApiType`, +1 `InlineMeSuggester` from `compat/`; +1 `LongFloatConversion` in
`MainKeyboardView`; `ReferenceEquality` 13 → 14 with code movement; `Incubating` gone
with androidx.customview) — the enumerated table is authoritative.

| Bug pattern | Before | After | Resolution |
|---|---:|---:|---|
| `EffectivelyPrivate` | 35 | 0 | all narrowed to `private` (see below) |
| `ReferenceEquality` | 14 | 14 | **untouched** — verified intentional identity comparisons |
| `ClassInitializationDeadlock` | 1 | 1 | **untouched** — documented unreachable (UI-thread-only init) |
| `MissingSummary` | 4 | 0 | javadoc summary sentences added |
| `NonApiType` | 4 | 0 | concrete types widened to `List`/`Map` |
| `StringSplitter` | 2 | 0 | explicit limit `0` (semantics identical to `split(regex)`) |
| `UnusedMethod` | 1 | 0 | dead method removed |
| `MutablePublicArray` | 1 | 0 | array narrowed to `private` |
| `MissingOverride` | 1 | 0 | `@Override` restored |
| `LongFloatConversion` | 1 | 0 | explicit `(float)` cast |
| `InvalidParam` | 1 | 0 | javadoc `@param` renamed to the real parameter |
| `InlineTrivialConstant` | 1 | 0 | constant inlined |
| `InlineMeSuggester` | 1 | 0 | dead deprecated delegator removed |

**Total: 67 → 15.** What remains is exactly the two consciously-kept classes.

### Closed — one line per finding

- `latin/utils/SubtypePreferenceUtils.java:58,61` (`StringSplitter` ×2): explicit limit —
  `split(sep)` → `split(sep, 0)`; `split(regex, 0)` is precisely what `split(regex)` does,
  so behaviour is unchanged.
- `keyboard/KeyboardActionListener.java:65` (`InvalidParam`): javadoc `@param text` →
  `@param rawText` (the actual parameter name of `onTextInput`).
- `keyboard/MainKeyboardView.java:451` (`MissingOverride`): `showMoreKeysKeyboard` had a
  commented-out `//@Override` next to its `// Implements {...}` note; `DrawingProxy:46`
  declares the method, so the annotation was restored (matches the `onGlideTrailEnd`
  idiom two methods up).
- `keyboard/MainKeyboardView.java:429` (`LongFloatConversion`):
  `startFadeOut(SystemClock.uptimeMillis())` → `(float) SystemClock.uptimeMillis()`,
  same explicit-cast shape as wave F1 in `PointerTracker`.
- `compat/ExploreByTouchHelper.java:285,294` (`MissingSummary` ×2): summary sentences
  added above the `@return` blocks of the two focused-view getters.
- `latin/RichInputConnection.java:750` (`MissingSummary`): summary sentence added to
  `hasSelection()`.
- `latin/RichInputMethodManager.java:124` (`MissingSummary`): summary sentence added to
  `SubtypeChangedListener.onCurrentSubtypeChanged`.
- `latin/utils/SubtypeLocaleUtils.java` (`UnusedMethod`): removed the dead private
  overload `SubtypeBuilder.addLayout(String, int)` — zero callers repo-wide; the
  one-arg `addLayout(String)` used at :209/:213/:216 stays (and is the only one the
  baseline/startup profiles reference).
- `keyboard/internal/KeyboardTextsTable.java:243` (`InlineTrivialConstant`):
  `private static final String EMPTY = ""` inlined as `""` at all 72 uses; the constant
  declaration removed. javac constant-folded it anyway, so bytecode at use sites is
  identical.
- `keyboard/Key.java:884` (`MutablePublicArray`): `KeyBackgroundState.STATES` narrowed
  `public static final` → `private static final`; the nested class and the array are
  used only inside `Key.java` (verified repo-wide, no reflection anywhere in the tree).
- `compat/FocusStrategy.java:60,75` (`NonApiType` ×2): parameters of the private static
  helpers `getNextFocusable`/`getPreviousFocusable` widened `ArrayList<T>` → `List<T>`
  (bodies use only `List` methods; sole caller passes an `ArrayList`).
- `keyboard/internal/KeyStylesSet.java:83` (`NonApiType`): `DeclaredKeyStyle` field and
  constructor parameter `HashMap<String, KeyStyle>` → `Map<String, KeyStyle>` (only
  `get`/`containsKey` are used).
- `latin/common/CollectionUtils.java:37` (`NonApiType`): `arrayAsList` return type
  `ArrayList<E>` → `List<E>`; the two consuming locals in
  `keyboard/internal/MoreKeySpec.java` (`filterOutEmptyString`,
  `insertAdditionalMoreKeys`) widened to match — all call sites use only `List` methods.
- `compat/ExploreByTouchHelper.java:589` (`InlineMeSuggester`): the deprecated
  `getFocusedVirtualView()` delegator deleted — zero usages repo-wide; the suggested
  `@InlineMe` needs androidx.annotation, which the zero-runtime-dependency rule bars.
  The fork already diverges from androidx by porting (see the file header).
- `EffectivelyPrivate` ×35 — every finding was a `public` member of a `private` nested
  class or of file-local use, so narrowing is compile-verified safe (outer-class access
  to nested privates is legal Java; no reflection, no XML, no test usage repo-wide):
  - `keyboard/Key.java` ×3 — `OptionalAttributes.mOutputText`, `.mAltCode`,
    `.newInstance`;
  - `keyboard/KeyboardLayoutSet.java` ×1 — `ElementParams()`;
  - `keyboard/internal/KeyPreviewChoreographer.java` ×2 — `KeyPreviewAnimators()`,
    `startDismiss`;
  - `keyboard/internal/KeyStylesSet.java` ×2 — `DeclaredKeyStyle()`, `readKeyAttributes`;
  - `keyboard/internal/KeyboardRow.java` ×5 — `RowAttributes` three fields + two
    constructors;
  - `latin/utils/SubtypeLocaleUtils.java` ×3 — `SubtypeBuilder` two constructors +
    `getSubtypes`;
  - `latin/RichInputMethodManager.java` ×19 — `SubtypeList` constructor + 13 methods,
    `SubtypeInfo` five fields.

### Skipped (kept intentionally)

- `ReferenceEquality` ×14 (`Key.java:446`, `Keyboard.java:142`, `KeyboardId.java:119`,
  `MoreKeysKeyboardView.java:214`, `PointerTracker.java:362,375,394,407,660,1043`,
  `internal/NonDistinctMultitouchHelper.java:87`, `utils/TypefaceUtils.java:77,79,81`) —
  the 2026-09-05 by-name verdict (intentional identity comparisons, no string `==`)
  stands; the count drifted 13 → 14 with code movement since.
- `ClassInitializationDeadlock` ×1 (`KeyboardActionListener.java:92`) — documented
  unreachable: both classes initialise on the UI thread only.

### Contract tests re-pinned

Source-contract tests slice sources by literals; intentional source-shape changes
require separator updates (precedent: item 5 above). Two tests were re-pinned to the
new source text, assertion bodies untouched:
`SubtypeSwitchAnnouncementSourceContractTest` (five `public` → `private` SubtypeList
signatures) and `GlideTrailContractTest:127` (the `(float)`-casted `startFadeOut` call).
Red-before/green-after verified.

### Environment note (not error-prone)

`gradle/verification-metadata.xml` had macOS/host gaps that failed every build here
fail-closed: the hand-pinned aapt2 entry covered only the linux jar, and a few
BOM/parent metadata artifacts were missing. Added: `aapt2-9.2.1-15009934-osx.jar`
(SHA-256 re-verified against a fresh dl.google.com download), kotlinx-coroutines-bom
1.6.4/1.7.1 poms and junit-bom 5.9.2/5.10.2 modules (recorded by Gradle's own
`--write-verification-metadata sha256`; existing hand-written entries and origins
preserved). The wrapper jar is deliberately uncommitted, so builds ran through the
pinned, already-cached gradle-9.6.0 distribution directly.

### Gates

| Gate | Result |
|---|---|
| `./gradlew test` (+ `compileDebugAndroidTestJavaWithJavac`) | **1670 tests, 0 failures** |
| python-конвейер (16 файлов `tests/*/test_*.py`) | all OK |
| `./gradlew lintRelease` | BUILD SUCCESSFUL |
| `scripts/check-no-internet.sh` (debug APK) | both levels OK |
| error-prone | **67 → 15** (only `ReferenceEquality` ×14 + `ClassInitializationDeadlock` ×1) |
