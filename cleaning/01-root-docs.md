# Корневые документы и витрина — ревизия текстов

Область: `AGENTS.md`, `BRIEF.md`, `README.md`, `SECURITY.md`, `PRIVACY.md`,
`.gitignore`, `metadata/` (тексты стора + changelogs en-US/ru-RU/tt), `images/`,
`icons/`. Прочитаны все файлы области; факты сверены с кодом
(`app/build.gradle`, `SuggestionStripState`, `DictionaryStorageContracts.kt`,
`BigramStorageContracts.kt`, CI `ci.yml`, `docs/PERF-BUDGETS.md`).

## Итог

Витрина (README, метадата, PRIVACY) в целом честная и аккуратная, крупной лжи
нет: версия 3.6.0/43, полоса из 3 ячеек (CELL_COUNT=3), размер ~1,8 МБ — всё
подтверждено кодом. Главные болезни — **нейрослоп-риторика** («это не обещание,
а проверяемое свойство», «executable rather than aspirational», «stated rather
than left unsaid», «earns its place» ×3) и **утечка dev-жаргона в
пользовательские тексты** (в changelog'ах стора «p95 5.19 → 1.72 ms»,
«de-texted debug tracers», «unchanged to the byte», «APK 2,111,775 bytes»).
`AGENTS.md` и `.gitignore` перегружены кодами миссий (T7, L3, O2, DEV-*, SIZE-3)
и датированными сносками; `AGENTS.md` сам себе противоречит по числу тестов
(1920 против 1927). Найдено одно фактическое **устаревание в PRIVACY.md**:
размер таблицы предсказаний 135 889 Б не совпадает с текущим пином кода 170 471 Б.
Две картинки — побайтные дубли стора.

Находки по категориям (≈): SLOP 9, OVERLONG 7, JARGON 8, AGENT 5, STALE 4,
DUP 3, LANG 3, DELETE 2.

Топ-3 действия:
1. Убрать пафосные формулы-самооправдания в README:39, SECURITY:42/62–63,
   PRIVACY:47/124 (это дословно те штампы, что просил вычистить оператор).
2. Переписать changelog'и стора человеческим языком без p95/байтов/«tracers»
   (систематический паттерн, минимум 8 файлов).
3. Исправить устаревшее число 135 889 → 170 471 в PRIVACY.md (стр. 30 и 107)
   и удалить два дубля-изображения.

## Файлы на удаление

| путь | размер | почему | кто ссылается (grep) | риск |
|---|---|---|---|---|
| `images/screenshot-0.png` | 144 041 Б | Побайтный дубль `metadata/en-US/images/phoneScreenshots/1.png` (sha256 `84c8df65…` совпадает). Каталог `images/` содержит только этот файл. | Нет активных ссылок: README не встраивает его, CI (`ci.yml`) не трогает, `PUBLISH-CHECKLIST.md` работает с `metadata/`. Grep по `screenshot-0`/`images/` — только сам файл. | Низкий |
| `icons/play_feature.png` | 66 199 Б | Побайтный дубль `metadata/en-US/images/featureGraphic.png` (sha256 `a2161156…`). `docs/FINAL-AUDIT-2026-09-02.md:47` (B5) фиксирует: старый play_feature заменён на featureGraphic — эта копия избыточна. | Активных ссылок нет; упомянут только в архивном аудите `FINAL-AUDIT-2026-09-02.md`. | Низкий/средний (если `icons/` держат как «мастер-исходники», оставить один экземпляр в `metadata/`). |

Замечания по инвентарю витрины (НЕ удалять):
- `icons/icon.svg` (9,9 КБ) — векторный исходник, используется генерацией
  (`docs/OPTIMIZE-2026-09-25.md:332`). Оставить.
- `icons/icon.png` (722 КБ, sha `be12e32b…`), `icons/ic_launcher-playstore.png`
  (19 КБ), `metadata/en-US/images/icon.png` (150 КБ, sha `750bddb6…`) — три
  РАЗНЫХ файла (не дубли), мастер-иконка и стор-иконки. Оставить.
- `metadata/**` — раскладка fastlane, потребляется `PUBLISH-CHECKLIST.md`
  (34 ссылки). Не трогать структуру; changelogs — история релизов, не удалять.
- Скриншоты `phoneScreenshots/2..5.png` уникальны (разные sha). Оставить.

## Правки

### README.md

- `README.md:39` — [SLOP] P1 — «Это не обещание, а проверяемое свойство: CI
  проверяет манифест и собранный APK на каждом коммите.» — дословно тот штамп,
  что просил убрать оператор; вдобавок весь абзац — одно гигантское
  предложение. Замена: разбить на 2–3 коротких предложения и убрать формулу,
  например: «В манифесте нет разрешения INTERNET — приложение не отправляет
  ничего никуда. CI проверяет это на каждом коммите: и исходный манифест, и
  собранный APK. Тем же гейтом проверяется `allowBackup=false` — данные не
  уходят в бэкап и не переносятся на новое устройство (настройки задаются
  заново).»
- `README.md:39` — [OVERLONG] P2 — один абзац на ~90 слов пересказывает весь
  PRIVACY.md (эмодзи, личный словарь, шифрование папки). Дублирует
  `PRIVACY.md`. Замена: оставить 2 предложения о главном (нет INTERNET, ничего
  не уходит с устройства) и ссылку «подробности — PRIVACY.md» (она уже есть
  строкой ниже, 41 — тогда строка 41 становится лишней, [DUP]).
- `README.md:41` — [DUP] P3 — «Подробнее: [PRIVACY.md](PRIVACY.md).» повторяет
  ссылку из конца строки 39. Замена: удалить строку 41 (ссылка уже в 39) либо
  наоборот — убрать ссылку из 39.
- `README.md:11` — [SLOP] P3 — «Такого нет ни в Gboard, ни в SwiftKey.» —
  маркетинговый выпад в описании проекта. Допустимо, но можно смягчить/удалить.

### SECURITY.md

- `SECURITY.md:42` — [SLOP] P1 — «This project keeps its security posture
  executable rather than aspirational» — пафос-слоган. Замена: «Security checks
  run automatically on every release:».
- `SECURITY.md:62-63` — [SLOP][DUP] P1 — «the offline claim is a build-time
  gate, not a promise.» — та же риторика «не обещание», что и README:39.
  Замена: «The app has no `INTERNET` permission; CI verifies this on the
  manifest and on the built APK.»
- `SECURITY.md:44-53` — [AGENT][OVERLONG] P2 — публичная security-политика
  перечисляет внутренние имена тестов (`PersonalLearningGatesTest`,
  `EditorTextCachePrivacySourceContractTest`, `BackupWhitelistSourceContractTest`,
  `*ValidatorTest`, `*PrivacyTest`) и пути отчётов. Внешнему репортеру это не
  нужно, а список тестов дрейфует. Замена: «Every release runs the no-internet
  gate, permission and signature checks, and the privacy/parser test suites,
  plus a manual review of changes to the input pipeline and stored data.» —
  без имён классов.
- `SECURITY.md:40,57` — [JARGON] P3 — «Re-audit ritual» и перечень дат-отчётов
  `FINAL-AUDIT-2026-09-02.md`/`AUDIT-2026-09-24.md`/`SECURITY-AUDIT-2026-09-25.md`
  в публичном файле. «ritual» — внутренний сленг; ссылки на docs могут
  протухать. Замена: заголовок «Audit cadence»; убрать перечень конкретных
  внутренних отчётов или заменить общей фразой «past audit notes live in the
  repository».

### PRIVACY.md

- `PRIVACY.md:30` и `PRIVACY.md:107` — [STALE] P2 — «two prediction tables
  (135,889 and 131,662 bytes)» / «(135 889 и 131 662 байта)». Код-пин в
  `BigramStorageContracts.kt:157,203` = `170_471` и `131_662`: число 135 889
  устарело (должно быть 170 471). Словари 1 276 289 / 1 151 323 совпадают
  (`DictionaryStorageContracts.kt:152,197`) — верны. Замена: заменить 135 889 →
  170 471; итог «около 2,7 МБ» остаётся корректным. Лучше — вообще убрать точные
  байты (см. системный паттерн ниже) и оставить «около 2,7 МБ суммарно».
- `PRIVACY.md:47` и `PRIVACY.md:124` — [SLOP] P2 — «This is a deliberate
  trade-off, stated rather than left unsaid.» / «Это осознанный компромисс, и он
  назван, а не умолчан.» — самопохвала за честность. Факт (экран не под
  паролем, FLAG_SECURE) полезен; хвостовая фраза — слоп. Замена: удалить
  последнее предложение, оставив описание поведения.
- `PRIVACY.md:40,54,66` (и рус. аналоги ~114,126,138) — [SLOP] P3 — троекратный
  рефрен «Until a word/pair earns its place, it does not exist in plaintext
  anywhere» / «Пока … не заслужило своё место, его нигде нет в открытом виде».
  Антропоморфный штамп «earns its place», повторён дословно 3 раза. Замена:
  нейтрально — «Before the third clean use, only a salted hash is stored, never
  the word itself» (и аналогично для пар/эмодзи).
- `PRIVACY.md:122` — [LANG] P3 — «карточка называет число слов и честно
  предупреждает» — «честно предупреждает» лишнее оценочное слово. Замена:
  «карточка показывает число слов и предупреждает, если конец копии повреждён».
- `PRIVACY.md:3` — [STALE-risk] P3 — «Версия / Version: 1.7 — 2026-09-28»:
  версия политики поддерживается вручную и легко разъезжается с релизом
  приложения (3.6.0). Не ошибка, но требует ручной синхронизации — отметить в
  чек-листе публикации.

### BRIEF.md

Примечание: `BRIEF.md` — «зафиксированный» бриф-ресёрч, на него ссылаются
`RESTRUCTURE-PLAN.md`, `CLEANUP.md` и десяток архивных аудитов; переписывать
осторожно. Но две вещи — чистый мусор внутри «брифа»:

- `BRIEF.md:24` — [AGENT][OVERLONG] P2 — «…цель „после D1 ≤ 1,7 МБ“ жила в
  `docs/archive/PROPOSALS.md`… Уточнение внесено 2026-08-23, потому что шесть
  отчётов приёмки приписывали планку 1,7 MiB этому файлу — см. `docs/CLEANUP.md`.»
  Абзац объясняет ИСТОРИЮ документационной ошибки (кто кому что приписал), а не
  требование. Замена одной строкой: «APK ≤ 3 МБ (3 145 728 Б). Отдельной
  целевой планки размера нет.»
- `BRIEF.md:17` — [STALE] P3 — «targetSdk/compileSdk 36. *(Актуально на 1.9.5…:
  …37.)*» — основной текст говорит 36, сноска исправляет на 37; в коде реально
  `compileSdk 37`, `targetSdkVersion 37`. Замена: «minSdk 24, targetSdk/compileSdk
  37» без сноски-исправления.

### AGENTS.md

Внутренний файл-руководство: часть кодов миссий здесь уместна, но перегруз и
дрейфующие числа мешают.

- `AGENTS.md:11` vs `AGENTS.md:110` — [STALE][AGENT] P1 — «JVM-тесты (1920 шт.)»
  против «`app/src/test` — 1927 JVM-тестов» в одном файле. Захардкоженный
  счётчик тестов и внутреннее противоречие. Замена: убрать точное число из
  обоих мест — «JVM-тесты» / «набор JVM-тестов (JUnit 4, Robolectric нет)».
- `AGENTS.md:12` — [STALE][AGENT] P2 — «Python-тесты конвейера (507 шт.)» —
  та же захардкоженная цифра. Замена: убрать «(507 шт.)».
- `AGENTS.md:16` — [OVERLONG][AGENT] P2 — ячейка «Линт» пересказывает историю
  дрейфа предупреждений: «32 → 29 в SAFE-волне 2026-09-25… 29 → 28 в O2-волне…
  28 → 29 2026-09-28…». Замена: «`./gradlew lintRelease` (baseline
  `app/lint-baseline.xml`: 0 errors, ~29 осознанных warning; классификация в
  `app/lint.xml`; `abortOnError=true`)».
- `AGENTS.md:13,14,15` — [OVERLONG][JARGON] P2 — ячейки инструментальных тестов
  и «перф-ритуала» на 900+ символов каждая, с датами, кодами (E3b, DEV-3, O2,
  Phase B/C), моделью телефона и координатами. Замена: сжать до команды + одной
  строки назначения; детали калибровки/POCO C71 вынести в
  `docs/DEVICE-RESEARCH-GEOMETRY.md`, на который уже есть ссылки.
- `AGENTS.md:14` — [LANG] P3 — внутри русской ячейки вставлен английский фрагмент
  «two word-form probes — type татар/сәләм + space on the tt layout, tap the
  given suggestion cell (three cells again since 2026-09-29 …)». Замена:
  перевести на русский и убрать дату-историю «four-cell wave reverted».
- `AGENTS.md:15` — [JARGON] P3 — «Девайсный перф-ритуал» — «ритуал» как термин;
  плюс код «O2-2», «framestats = 17-я не 14-я». Уместно в отдельном doc, не в
  обзорной таблице.

### .gitignore

Комментарии в целом полезны (объясняют, почему нужны `!`-исключения), но
перегружены кодами миссий и датами:

- `.gitignore:36` — [JARGON] P3 — «# T7 (stage D, docs/ROADMAP-P8-PLAN.md): the
  dependency checksums must be versioned…». Замена: «# Контрольные суммы
  зависимостей должны быть в репозитории — это и есть supply-chain пин.»
- `.gitignore:39` — [JARGON] P3 — «# L3 (docs/LEFTOVERS-PLAN-2026-09-28.md): the
  exported PGP keyring…». Замена: убрать код «L3 (…)», оставить суть.
- `.gitignore:89-94` — [OVERLONG][AGENT] P3 — две датированные сноски «Уточнение
  2026-08-30 …» и «Уточнение 2026-08-30 (фаза 2) …» про переезд JSON. Замена:
  одна строка «docs/*.generated.json и docs/archive/bigrams/*.generated.json —
  сырые отчёты не коммитим (закоммиченные пины lang-priority это правило не
  затрагивает)».
- `.gitignore:76` — [AGENT] P3 — «# Рабочий каталог оператора (…не коммитится —
  ROADMAP-P1 T6)». Замена: «# Локальный рабочий каталог (черновики, скринкасты)».

### metadata/ — changelogs (стор)

Систематическая утечка dev-жаргона в пользовательские заметки релиза:

- `metadata/en-US/changelogs/42.txt:2` — [JARGON] P2 — «Faster dictionary reads
  — worst path p95 5.19 → 1.72 ms». Пользователю «p95» и «worst path» ничего не
  говорят. Замена: «Faster word suggestions.»
- `metadata/en-US/changelogs/42.txt:3` — [JARGON][SLOP] P2 — «Hardening: editor
  connection vs hostile host apps, de-texted debug tracers, zero network traffic
  proven on-device». «de-texted debug tracers» — бессмысленно для юзера. Замена:
  «Stability and privacy hardening; verified no network traffic on-device.»
- `metadata/ru-RU/changelogs/42.txt:2` и `metadata/tt/changelogs/42.txt:2` —
  [JARGON] P2 — «худший путь p95 5,19 → 1,72 мс» / «иң авыр юл p95 5,19 → 1,72
  мс». Тот же жаргон. Замена: «Словари читаются быстрее» / «Сүзлек тизрәк».
- [SLOP] P3 — паттерн «unchanged to the byte / byte-identical / byte-for-byte» в
  `en-US/changelogs/18.txt:5`, `19.txt:4`, `20.txt:4`, `22.txt:4`, `23.txt:3`,
  `33.txt:2`. Например 33:2 «Code-only release: dictionaries … are byte-identical;
  one permission — vibration; no network». Для privacy-messaging идея хорошая,
  но «byte-identical/unchanged to the byte» — dev-фраза. Замена: «No changes to
  the dictionaries or data; still one permission (vibration) and no network.»
- `metadata/en-US/changelogs/21.txt:1` — [JARGON] P3 — «The keyboard is 16.8%
  lighter: APK 2,111,775 bytes». Точный размер в байтах — dev-деталь и число
  дрейфует. Замена: «The app is noticeably smaller.»
- `metadata/en-US/changelogs/23.txt:3` — [JARGON] P3 — «The table is 7.5 KB
  lighter…». Аналогично — убрать точные КБ.

### metadata/ — описания стора

- `metadata/en-US/full_description.txt`, `ru-RU`, `tt` — [OK, замечание] — «about
  1.8 MB / около 1,8 МБ / якынча 1,8 МБ» ПОДТВЕРЖДЕНО (`docs/PERF-BUDGETS.md:17`:
  1 804 874 Б для 3.4.0). Не стале. Оставить, но при росте APK — сверять.
- `metadata/*/full_description.txt` — [STALE-risk] P3 — «100,000 word forms per
  language / по 100 000 словоформ на язык». Отсечка словаря в коде —
  `TATAR_DICTIONARY_TOP` = 110 000 (AGENTS.md:78). Округление вниз безопасно, но
  число живёт в трёх файлах вручную. Низкий приоритет.
- Тексты описаний в целом чистые: структура, факты и тон адекватны, крупного
  слопа в них нет.

## Системные паттерны

1. **Риторика самооправдания** («не обещание, а проверяемое свойство»,
   «executable rather than aspirational», «stated rather than left unsaid»,
   «earns its place»). Правило: описывать поведение и проверку фактами, без
   мета-комментария о собственной честности. Места: README:39, SECURITY:42/62,
   PRIVACY:40/47/54/66/124.

2. **Dev-жаргон и точные числа в пользовательских текстах стора** (p95, ms,
   «byte-identical», «X bytes», «KB lighter», «de-texted tracers»). Правило:
   changelog стора = что изменилось для пользователя, одной фразой; ни метрик
   производительности, ни размеров в байтах. Места: changelogs 18–23, 33, 42
   (все три локали, где применимо).

3. **Захардкоженные дрейфующие счётчики/размеры** в рукописных доках. Правило:
   не дублировать числа из кода в прозу; либо ссылаться на источник, либо
   округлять. Места: AGENTS.md:11/12/16 (число тестов, warning-дрейф),
   PRIVACY.md:30/107 (уже разъехалось: 135 889 ≠ 170 471), changelogs с байтами.

4. **Коды миссий и датированные сноски-история** вместо сути (T7, L3, O2,
   DEV-*, SIZE-3, E3b; «Уточнение 2026-08-30…», «SAFE-волна», «O2-волна»).
   Правило: в живых/публичных файлах — что делает правило сейчас; историю
   держать в `docs/archive`, а не в `.gitignore`/`BRIEF.md`/`SECURITY.md`.
   Места: .gitignore:36/39/76/89-94, BRIEF.md:24, AGENTS.md:13-16, SECURITY.md:44-53.

5. **Дубли ассетов витрины**: одни и те же PNG лежат и в `icons/`/`images/`, и в
   `metadata/`. Правило: единственный источник истины для публикуемых
   картинок — `metadata/**`; рабочие мастера (svg, крупный png) — в `icons/`, но
   без побайтных копий уже опубликованных файлов. Места: images/screenshot-0.png,
   icons/play_feature.png.
