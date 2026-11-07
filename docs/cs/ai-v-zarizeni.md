# AI v zařízení — poznatky (česky)

> **English summary:** [../on-device-ai.md](../on-device-ai.md). This file keeps the original
> engineering notes in Czech, carried over from the Plume app where these modules were born
> (`docs/poznatky/ai-v-zarizeni.md` in Plume). Names like `shared`, `core/ai`, `AiTask`, settings
> screens or `:client:…` modules refer to **Plume**, the first app using the library; the library
> modules are `:ai-engine`, `:ai-core`, `:ai-litert`, `:ai-llamacpp`, `:ai-llamacpp-android` and
> `:ai-llamacpp-desktop` here (dříve `:client:ai-…`, balíčky dříve `eu.tintera.plume.ai.*`, nyní
> `eu.tintera.ai.*`). Sekce o jednotlivých funkcích Plume (shrnutí, návrhy odpovědí, triáž…)
> zůstaly v Plume.
## Modul :client:ai-core

Poskytovatelé modelu v zařízení jsou vyčlenění do vlastního KMP modulu (balíček
`eu.tintera.ai.core`), který **nezávisí na ničem z Plume** — jen na `:client:ai-engine`
a knihovnách (ML Kit GenAI, WorkManager, Play Feature Delivery). Spolu s `ai-engine`, `ai-litert`
a `ai-llamacpp*` se přesune do samostatné open-source knihovny **ai-engine** (Apache-2.0,
github.com/VladimirTintera/ai-engine, balíčky `eu.tintera.ai.*`) sdílené s další aplikací
(nápady na recept ze zásob); Plume ji pak vezme jako git submodul. Balíček `eu.tintera.ai.core`
má proto už teď, ať se při přesunu nepřejmenovává podruhé; ostatní moduly zatím nesou
`eu.tintera.plume.ai.*`. Hranici hlídá Gradle: `ai-core` na `shared` nevidí, takže import z Plume
se nepřeloží (`grep -rn 'eu.tintera.plume\.' client/ai-core/src` najde jen `ai.engine`).

- **V knihovně:** rozhraní `OnDeviceAi` s `ModelVariant`, `AiProvider`, katalogem `LiteRtModel`,
  stavy a průběhem stahování; `RoutingOnDeviceAi`; typovaná chyba `OnDeviceAiException`
  s `AiFailureReason` a `isReportable` (bez textů); `generateAnswer` (strukturovaně, jinak textem);
  `estimatedTokenCount`; `MeteredNetwork`, `DeviceLoad` a `avoidUnderLoad` i s implementacemi pro
  Android. Stahované modely: `LiteRtOnDeviceAi`, `LiteRtPlatform` (`AndroidLiteRtPlatform`,
  `DesktopLiteRtPlatform`), `LiteRtRuntime` (`PlayLiteRtRuntime`, `BundledLiteRtRuntime`),
  `SplitInstallConfirmations`, úloha stahování. Gemini Nano `MlKitOnDeviceAi` a pomůcka
  `generableStringField` pro ruční `GenerableProvider`. Na iOS `AppleOnDeviceAi` s mostem
  `OnDeviceTextModel` / `IosOnDeviceModel` (Swift `AppleTextModel` je v `iosApp`).
- **V Plume (`shared`):** všechny `*Prompt.kt`, `AiTasks.kt` (`AiTask`, `variantFor`,
  `chooseProvider`), `proactiveVariant`, `GemmaIdle.kt` (prodlevy podle úloh), typy odpovědí
  (`StructuredAnswers.kt`) a jejich `GenerableProvider` s řádky v `META-INF/services`, věty
  k chybám (`OnDeviceAiFailureText.kt`: `userMessage()`, `emptyOutputMessage`), nastavení,
  hřiště, Koin a dialog Play `SplitInstallConfirmationHost`.
- **Co knihovna bere od aplikace** (rozhraní, implementace dává Plume v Koinu):
  - `Connectivity` — je síť? Pro „čeká na síť" a navázání po výpadku. Plume:
    `NetworkConnectivity` nad `NetworkMonitor`.
  - `AudioClipDecoder` — zvuk pro přepis hlasovky (WAV 16 kHz mono po 30 s). Plume: `AudioClips`
    z `:client:core` (Android, používají ho i hodinky), `DesktopAudioClips` přes FFmpeg (desktop).
  - `ModelDownloadNotifications` — kanál, ikona a texty notifikace stahování. Plume:
    `PlumeModelDownloadNotifications`.
  - Adresář modelů na desktopu (`DesktopLiteRtPlatform(localDirectory, …)`). Plume:
    `aiModelsDirectory` (`%LOCALAPPDATA%\Plume` na Windows, jinak složka dat).
  - Prodleva uvolnění enginu (`idleUnloadMs`), placená síť (`MeteredNetwork`), runtime pro
    každý `ModelRuntime` — parametry `LiteRtOnDeviceAi` jako dřív.
- **Prompt jde do každého volání.** `transcribe(variant, audioPath, prompt)`
  a `describeImage(variant, imagePath, prompt)` berou hotový pokyn; dřív si přepis skládal
  `transcriptionPrompt` sám uvnitř `LiteRtOnDeviceAi` a popis fotky měl výchozí
  `imageDescriptionPrompt`. Jazyk je jen součást pokynu, proto parametr `languageCode` zmizel.
  **Pozor při podobné změně signatury:** obě pole jsou `String`, takže by staré poziční volání
  s kódem jazyka prošlo překladem a do modelu by šlo „cs" místo pokynu — volání se proto
  měnila ručně a ověřila grepem `\.describeImage(\|\.transcribe(`.
- **Úloha stahování je abstraktní (`AiModelDownloadWorker`), podtřídu dává aplikace.**
  WorkManager si v naplánované úloze pamatuje JMÉNO třídy a po zabití procesu ji tvoří sám,
  bez DI. Podtřída v Plume se proto jmenuje pořád `eu.tintera.plume.core.ai.ModelDownloadWorker`
  — stahování rozběhnuté před aktualizací aplikace na novou verzi naváže — a v `dependencies()`
  si z Koinu (`GlobalContext`) vezme `LiteRtOnDeviceAi` a notifikace. Plánuje
  `ModelDownloadScheduler` s třídou, kterou mu dá `AndroidLiteRtPlatform(context, worker, audio)`.
  Knihovna tak Koin nepotřebuje vůbec.
- **Dialog souhlasu Play je v Plume, `SplitInstallConfirmations` v knihovně.** Composable by
  knihovně přidal Compose, aktivitu a Koin; aplikace místo toho sleduje `pending`, před ukázáním
  zavolá `markShown` a výsledek předá žádosti z `takeShown()`.
- **Texty chyb zůstaly v Plume.** Knihovna hlásí jen důvod (`AiFailureReason`) a poskytovatele;
  věty z `Res` skládá `userMessage()` ve `shared` — další aplikace má vlastní prostředky.
- **iOS framework modul exportuje** (`export(projects.client.aiCore)` v `shared`, závislost
  `api`). Swift implementuje `OnDeviceTextModel` a plní `IosOnDeviceModel`; bez exportu by je
  framework nenesl (nebo jen pod jménem s předponou modulu) a `AppleTextModel.swift` by se
  nepřeložil.
- **Katalog modelů zůstal enum** (`LiteRtModel`) se všemi poskytovateli Plume, stejně jako pole
  `gemma` / `qwen` / `euroLlm` / `aya` v `ModelVariant`. Jména položek se ukládají (nastavení,
  jméno úlohy stahování) a pořadí určuje náhradu za velikost — převod na data, která předá
  aplikace, by sahal do nastavení i UI. **Další krok** před sdílením s jinou aplikací: katalog
  jako seznam od aplikace (repozitář, commit, soubor, velikost, paměť, kontext, runtime,
  licence) a variantu nést jen jako odkaz na položku.
- **Testy:** `./gradlew :client:ai-core:jvmTest` (router, katalog, stahování, GPU záloha,
  runtime). Testy, které potřebují prompty Plume (`StructuredAnswersTest`, `TokenEstimateTest`,
  `OnDeviceAiFailureTest`, `DeviceLoadTest`), zůstaly ve `shared`.

## Dva poskytovatelé: Gemini Nano a Gemma

Zapnuté můžou být **Gemini Nano** v AICore (ML Kit, `MlKitOnDeviceAi`), **Gemma 4** a **Qwen**
přes LiteRT-LM (`LiteRtOnDeviceAi`), které si stáhne aplikace, nebo víc naráz (`AiModels.enabled`).
Každá funkce se ptá na model PRO SVOU ÚLOHU (`ai.variantFor(AiTask.Summary, settings)`,
`core/ai/AiTasks.kt`, testy `AiTasksTest`) a hotové volání pak předá `RoutingOnDeviceAi` (testy
`RoutingOnDeviceAiTest`). Nová funkce s modelem si tedy přidá řádek do `AiTask` a o
poskytovatelích nemusí vědět nic.

- **Poskytovatel je pole varianty, ne druhé rozhraní.** Funkce dostane hotovou variantu pro
  svou úlohu; kdyby řešila poskytovatele sama, jedna by na to zapomněla.
- **Router variantu NORMALIZUJE** (`forGeminiNano`, `forGemma`, `forQwen`). ML Kit drží klienta na
  variantu v mapě, takže by jinak změna modelu Gemmy vyrobila druhého klienta téhož Nano.
- **Katalog Gemmy (`LiteRtModel`) je převzatý z Google AI Edge Gallery**
  (`model_allowlists/1_0_19.json`): soubor, commit na Hugging Face, velikost a doporučená paměť.
  **Commit je součást adresy**, takže se stahuje pořád tentýž soubor; nová verze = vědomá změna
  katalogu. **Tagy na GitHubu LiteRT-LM s verzemi na Google Maven nesedí**; platné verze jsou
  v `maven-metadata.xml` artefaktu. Knihovna byla napřed 0.11.0 z Gallery, kvůli Qwenu je 0.19.1
  (viz „Qwen a další modely přes LiteRT-LM").
- **Gemma se nenabízí pod Androidem 12, bez arm64 a pod `minMemoryGb`** (8 GB pro E2B, 12 GB
  pro E4B; `totalMem` s 15% rezervou, výrobce udává víc, než jádro nechá). Pod tím by systém
  aplikaci zabil při načítání modelu. Kde model nejde, je `Unsupported` a volba se vůbec neukáže.
- **Uložená volba, kterou zařízení nesplní, se opraví** (`supportedModels`, testy
  `SupportedModelsTest`): Nano na telefonu bez AICore → Gemma, výchozí Nano na iOS → Apple,
  E4B s málo pamětí → E2B. Z víc zapnutých se jen vypustí ten, který tu není.
- **Stahuje se do `.part` a přejmenovává až hotový**, takže přítomnost souboru = kompletní model.
  Přerušené stahování navazuje (`Range`). Před stahováním se kontroluje volné místo (velikost
  + 500 MB) a nedostatek má vlastní hlášku (`NotEnoughSpaceException`) — u gigabajtů je to
  nejčastější důvod selhání a jediný, se kterým uživatel může něco udělat.
- **Po výpadku sítě stahování naváže SAMO** (`LiteRtOnDeviceAi.fetch` čeká na `Connectivity`, v Plume
  nad `NetworkMonitor`).
  Stahuje se minuty a přechod z Wi-Fi na data je za tu dobu běžný; dokud výpadek končil
  „nepodařilo se stáhnout", musel se uživatel vracet do Nastavení kvůli něčemu, co se vlastně
  nepokazilo. Mezitím nastavení říká „Čekám na připojení" (`ModelDownload.waitingForNetwork`).
  Opakuje se jen chyba spojení: odpověď serveru (`HttpStatusException`) ani plný disk čekáním
  nespraví, a po pěti pokusech bez jediného nového bajtu se stahování vzdá. Po vypnutí
  letového režimu přišel na emulátoru ještě DRUHÝ výpadek, jak se síť usazovala — i ten se
  navázal, proto se čeká napřed na síť a teprve pak s prodlevou.
- **Nastavení u Gemmy říká, CO a KOLIK se stáhne** („Gemma 4 E2B je potřeba nejdřív stáhnout
  (2,4 GB)") a během stahování „164 MB z 2,4 GB". Samotné „zabere gigabajty" stačí u Nano, jehož
  velikost nikdo neřekne; tady ji víme. Velikost jde přes `fileSizeText` jako u souborů
  v chatu, takže je v GiB — katalog Gallery uvádí 2,6 GB v desítkových. Po selhání tlačítko
  slibuje „Pokračovat ve stahování": stažený kus se nezahazuje.
- **Stahovaný model jde smazat, systémový ne** (`ModelVariant.appManaged`, `OnDeviceAi.delete`).
  **Se souborem modelu jde i jeho zkompilovaná keš** (`LiteRtModel.ownsCacheFile`, testy
  `ModelCacheFilesTest`, `LiteRtDeleteTest`). LiteRT-LM si při prvním načtení uloží model
  přeložený pro procesor do `cacheDir` enginu (`<soubor>_<čas>_<velikost>.xnnpack_cache`,
  u Gemmy i zvukový kodér a adaptér) — u Gemmy 4 E4B 2,3 GB, u Qwen3 4B taky. Dokud se mazal
  jen model, zůstávala: na desktopu po smazání E4B a Qwenu 4,5 GB v `ai-cache`, které nikdo
  neuklidí; na Androidu ji časem smaže systém, do té doby se počítá do místa aplikace.
  Engine bez `cacheDir` (sondy) ji ukládá vedle modelu, proto se hledá v obou adresářích.
- **Gemmu stahuje úloha WorkManageru, ne korutina v procesu** (`AiModelDownloadWorker`
  v `:client:ai-core`, podtřída `ModelDownloadWorker` v `core/ai`, viz „Modul :client:ai-core"). Dokud stahovala korutina, skončilo stahování se zabitým procesem
  a navázalo až dalším ťuknutím v Nastavení. Stahuje pořád `LiteRtOnDeviceAi` (i s čekáním na
  síť), úloha jen drží proces a ukazuje notifikaci průběhu se „Zastavit"; stav pro obrazovku je
  v téže instanci (proto je `LiteRtOnDeviceAi` v Koinu samostatný `single`). WorkManager je kvůli
  tomu i v `:client:shared` — v aplikaci byl už dřív, APK se nezměnilo.
  - **Služba v popředí `dataSync`, jen když ji systém pustí.** Po zabití procesu spustí
    WorkManager úlohu z pozadí a Android 12+ tam službu v popředí odmítne
    (`ForegroundServiceStartNotAllowedException`). Úloha pak stahuje jako běžná a notifikaci
    ukazuje sama; po limitu běžné úlohy ji systém zastaví, WorkManager spustí znovu a `.part`
    naváže. Ověřeno: po `kill -9` do 5 s nový proces bez služby v popředí, stahování pokračovalo
    od 751 MB. Když se aplikace mezitím otevře, rozběhne se úloha i se službou.
  - **`JobCancellationException` JE `IllegalStateException`.** `catch (IllegalStateException)`
    kolem `setForeground` chytal i zrušení úlohy a dokončené stahování vypadalo jako odepřená
    služba v popředí. Zrušení se proto chytá a posílá dál dřív.
  - **Podmínka sítě (`NetworkType.CONNECTED`), ne jen smyčka v `fetch`.** Bez sítě by úloha
    držela službu v popředí hodiny naprázdno. Při ztrátě sítě ji WorkManager zastaví
    (`STOP_REASON_CONSTRAINT_CONNECTIVITY` → obrazovka „Čekám na připojení") a s návratem
    spustí znovu. Krátký výpadek, o kterém systém ještě neví, přečká smyčka jako dřív.
  - **„Stahovat modely jen přes Wi-Fi"** (Nastavení → AI v zařízení, pod modely; výchozí
    zapnuté, `AppSettings.modelDownloadWifiOnly`) je táž podmínka, jen `NetworkType.UNMETERED`.
    Dřív stačilo jedno ťuknutí na mobilních datech a stahovaly se gigabajty.
    - **Placenost, ne druh sítě** (`MeteredNetwork`, na Androidu `isActiveNetworkMetered`).
      `NetworkMonitor` by Wi-Fi z hotspotu telefonu vedl jako Wi-Fi, systém i podmínka
      `UNMETERED` ji vedou jako placenou. Dotaz a skutečné chování úlohy se tak shodnou. Bez
      sítě vrací `false` (systém `true`): v letovém režimu se nemá na co ptát.
    - **Mimo Wi-Fi se Stáhnout zeptá** (`ConfirmDialog`: „Stáhnout přes data" / „Počkat na
      Wi-Fi", s velikostí modelu). Zavření dialogu mimo tlačítka = počkat: o model uživatel řekl
      a čekání nic nestojí. „Přes data" platí jen pro to jedno stažení, Nastavení se nemění.
      Připojení obrazovky k běžícímu stahování (`refresh` → `startDownload`) se neptá.
    - **Stav „Čekám na Wi-Fi"** (`ModelDownload.wifiOnly` s `waitingForNetwork`). Úloha se na
      datech vůbec nespustí, takže stav musí čekání říct sám hned při plánování
      (`download`), jinak by tvrdil, že se stahuje.
    - **Přepnutí volby za běhu úlohu neruší** (`setDownloadNetwork` → `WorkManager.updateWork`).
      Zrušení by se ohlásilo jako zastavení uživatelem (`CANCELLED_BY_APP`) a obrazovka by
      ukázala „Pokračovat". Čekající úloha se po povolení dat rozběhne. **Běžící úlohu nová
      podmínka nezastaví** (platí až pro příští běh), proto se při zákazu dat na placené síti
      zruší jen korutina stahování v `runDownload` a úloha skončí s `Result.retry()`
      (`WifiRequiredException`). Opakování má lineární prodlevu 10 s: výchozí exponenciální
      by návrat na Wi-Fi zdržela.
    - **Runtime z Play volba neřídí.** Je to desítka MB a velké stahování přes data si Play
      potvrdí sám (`SplitInstallConfirmations`). Gemini Nano stahuje AICore po svém.
    - **Na desktopu se volba nenabízí** (`NoMeteredNetwork`): JVM druh sítě nehlásí
      (`NetworkMonitor` tam vrací vždycky `Other`) a volba by slibovala něco, co se nestane.
    - **Zrušení stahování zavře spojení** (`invokeOnCompletion` v `fetchFile`). Blokující `read`
      zrušení korutiny nepozná: na síti, která po změně přestala posílat data, čekal do
      `readTimeout` a zákaz dat za běhu se na emulátoru projevil až po 23 s. Se zavřením
      spojení za 0,1 s. `IOException` ze zavřeného spojení se hlásí jako zrušení
      (`ensureActive`), ne jako výpadek sítě. Totéž zrychlilo „Zastavit".
    - **Ověřeno na emulátoru** (Android 17, Qwen3 0,6B, síť zpomalená `adb emu network speed
      hsdpa`, ať stahování nedoběhne hned):
      - Na LTE dialog a „Počkat na Wi-Fi": úloha čeká v plánovači s `NOT_METERED`
        (`dumpsys jobscheduler`), stav „Čekám na Wi-Fi".
      - Zapnutí Wi-Fi úlohu rozběhne, ztráta Wi-Fi uprostřed ji zastaví (`.part` dál neroste).
      - Vypnutí volby za čekání úlohu pustí bez „Pokračovat" (`updateWork`).
      - Zapnutí volby za stahování na placené síti dá `RETRY` a čekání.
      - Po zabití procesu (reinstalace) se stav čekání obnoví.
      - „Pokračovat" na placené síti se zeptá a „Stáhnout přes data" model stáhne celý (velikost
        sedí na bajt).
    - **LTE emulátoru ven nevede**, i když ho systém hlásí jako ověřené (`ping` 100 % ztrát, se
      zapnutou Wi-Fi i bez ní). Stahování „přes data" tam skončí na timeoutu. Placená síť se
      proto simuluje Wi-Fi: `adb shell cmd netpolicy set metered-network '"AndroidWifi"' true`
      (zpátky `undefined`, výchozí stav emulátoru). Změna placenosti Wi-Fi na chvíli shodí
      (`onLost`) a WorkManager běžící úlohu zastaví a hned pustí znovu.
    - **Snímek obrazovky Plume je na emulátoru bílý** (`screencap`), stav se čte z `uiautomator
      dump`. Fyzický telefon ověřený není. Testy `LiteRtDownloadNetworkTest`.
  - **Důvod zastavení rozhoduje, co ukázat** (`getStopReason`): `CANCELLED_BY_APP` = uživatel
    zastavil („Zastavit" v notifikaci je `createCancelPendingIntent`), cokoli jiného = systém
    a úloha se vrátí, takže pro obrazovku se pořád stahuje.
  - **Spuštěná úloha zapíše stav NAČISTO**, i když už nějaký byl: jinak by po návratu sítě visel
    starý příznak čekání, než přijdou první 4 MB.
  - **Zastavit jde z notifikace i z Nastavení** a zčásti stažený model má vlastní stav
    (`OnDeviceAiStatus.Paused`: `.part` je, úloha není) s „Pokračovat ve stahování" a „Zahodit
    staženou část". Stahování, které přežije zabití aplikace, by jinak nešlo zastavit vůbec,
    a gigabajt rozstahovaného modelu by nešel smazat.
  - **Play chce typ `dataSync` vysvětlit** v deklaraci služeb v popředí (Play Console → Obsah
    aplikace), stejně jako `mediaPlayback` u hudby. Bez ní neprojde další release.
  - **Ověřeno na emulátoru sondou** (Pixel_9 se 4 GB, kontrola paměti v `deviceCanRun` dočasně
    vypnutá; necommituje se — stahování na paměti nezávisí): stažení celého E2B (velikost sedí
    na bajt) s notifikací „Model … je stažený", zabití procesu za běhu, „Zastavit" z notifikace
    i z Nastavení, letový režim (čekání, zastavení úlohy, navázání), zahození části a smazání.
    **Release build (R8) s úlohou ověřený není** — workery drží keep pravidla WorkManageru,
    na kterých stojí i notifikační úlohy.
- **Engine je v paměti nejvýš jeden a po chvíli nečinnosti se uvolní** — drží gigabajty RAM.
  Načtení trvá až deset sekund, proto ne hned po odpovědi (návrhy chodí po sobě): **dvě minuty,
  když Gemma počítá nějaký text, 30 s, když dělá jen zvuk a obraz** a text má Nano
  (`gemmaIdleUnloadMs`, testy `GemmaIdleTest`). Přepisy a popisy fotek se pouštějí ťuknutím,
  ne s každou zprávou; 30 s pokryje pár hlasovek za sebou. Prodleva se čte po každém použití
  (zapnutí Nano nebo jiné přiřazení ji mění za běhu) a rozhoduje stejně jako funkce samy
  (`variantFor`): Gemma, která převzala text, protože se Nano stahuje, drží dvě minuty.
  **Kolik stojí opětovné načtení na GPU, změřené není** — na emulátoru (CPU, 8 GB) trvalo
  načtení s přepisem ~11 s; jestli je 30 s na telefonu málo, ukáže až Fold.
  **Generování je jedno po druhém** (zámek): dvě konverzace nad jedním enginem LiteRT-LM neslibuje.
- **GPU, a když nejde, CPU.** GPU backend potřebuje `libOpenCL.so` — `uses-native-library`
  v manifestu aplikace (`required=false`). Na zařízení ověřené není, jestli se GPU chytí.
- **Bez OpenCL se GPU engine NAČTE BEZ CHYBY a selže až u první zprávy.** Na emulátoru x86_64
  s LiteRT-LM 0.19.1 prošlo `Engine.initialize()` s `Backend.GPU()` a teprve generování skončilo
  `LiteRtLmJniException: … UNKNOWN: Can not find OpenCL library on this device` z
  `Conversation$JniMessageCallbackImpl.onError`. Záloha jen kolem `initialize()` (tak to bylo do
  té doby) tedy CPU nikdy nezkusila a uživatel viděl „Model Qwen selhal". (Se starší verzí
  selhala už inicializace, na OpenGL v `delegate_opengl.cc`, a CPU naskočilo.) Týká se nejspíš
  i telefonů bez OpenCL.
  - **Selže-li generování na GPU dřív, než přišel první token, engine se zavře, načte na CPU
    a dotaz se zopakuje jednou** (`withEngine` v `LiteRtOnDeviceAi`, testy
    `LiteRtGpuFallbackTest` s falešným `LlmEngineFactory`). Platí pro shrnutí, přepis i popis
    fotky; zvuk a obraz si engine přenese.
  - **Po prvním tokenu ani po zrušení se neopakuje**: obrazovka už text ukazuje a odpověď by
    začala znovu.
  - **GPU se pak do konce procesu nezkouší** (`gpuFailed`, jen v paměti): každé další načtení
    by jinak trvalo o neúspěšné načtení na GPU déle. Pamatuje se, jen když CPU uspělo (vydalo
    token nebo doběhlo) — selže-li CPU stejně brzy, chyba na backendu nejspíš nezávisí a GPU
    dostane příště šanci znovu. Totéž platí pro GPU, které selže už při načtení. **Pamatuje se
    pro každý runtime zvlášť**: Metal v llama.cpp a GPU v LiteRT-LM jsou různé backendy
    a selhání jednoho nesmí vzít GPU druhému (`LiteRtGpuFallbackTest`).
  - **Test jede nad rozhraním enginu z `:client:ai-engine`** (`LlmEngineFactory`), falešný
    engine dodá `FakeLiteRtRuntime(engines = …)`.
  - **Ověřeno na emulátoru x86_64** (Qwen3 0.6B, sonda v `AndroidLiteRtPlatform.canRun`, jinak
    se tam model nenabízí): shrnutí chatu napoprvé hlásí v logu „GPU selhalo při generování,
    zkouším CPU" a doběhne na CPU. **Nezdařený pokus na GPU ale stojí ~24 s** — LiteRT-LM bez
    OpenCL nejdřív sáhne po WebGPU („OpenCL not supported on this platform. Using WebGPU
    instead.") a chybu ohlásí až po přípravě. Platí se to jednou za proces, právě proto
    `gpuFailed`. Na telefonu neověřené.
- **LiteRT-LM v Kotlinu neumí tokeny spočítat předem** (`Conversation.getTokenCount` zná až
  rozběhnutou konverzaci), proto `countTokens` vrací HORNÍ odhad z bajtů UTF-8
  (`estimatedTokenCount`, 1,8 bajtu na token; testy `TokenEstimateTest`) a shrnutí podle něj
  přepis zkrátí nebo rozdělí na části. **Dřív vracel `null` a jelo se s odhadem tří znaků na
  token — a ten u češtiny neplatí**: změřeno na desktopu `getTokenCount` nad českým chatem
  s odkazy Gemma 2,2 znaku na token, Qwen 2,0. Přepis, který aplikace pro Gemmu posílala
  (16 330 znaků), měl 7 547 tokenů z keše 8 192, a na odpověď místo 1 536 tokenů zbylo ~650.
  Platí i pro Android; na Foldu se to neukázalo, protože se zkoušely kratší chaty. Limit je
  velikost KV keše (8 192 u Gemmy, 4 096 u Qwenu) minus místo na odpověď — vstup i výstup se do
  keše musí vejít SPOLU. **Strop výstupu je dvojí**: `maxOutputToken` pro knihovnu (od 0.19)
  a `MAX_OUTPUT_CHARS` pro obrazovku (pak `Generated.truncated`).
- **Hugging Face model vydá bez přihlášení** (ověřeno `curl -IL`: 302 na CDN, 200, velikost
  sedí na bajt, `accept-ranges: bytes`). Licenční brána by se projevila 401/403 a tu prototyp
  hlásí jako selhání stahování.
- **Nativní knihovny přidají ~25 MB na ABI** (debug arm64 49,6 → 74,3 MB) a dostane je každý,
  i na telefonu, kde se Gemma nikdy nenabídne. Pro prototyp únosné; před vydáním Play Feature
  Delivery. Knihovna má minSdk 23 (projekt 26), `tools:overrideLibrary` potřeba není.
- **Na emulátoru se Gemma vyzkoušet DÁ — na rozdíl od Gemini Nano.** Stačí AVD s 8 GB RAM
  (`hw.ramSize=8192` v `config.ini`, jinak se E2B kvůli `minMemoryGb` nenabídne); hostitel
  paměť bere až podle skutečné spotřeby, na Macu s 18 GB to po zastavení Gradle daemonů šlo.
  Běží na CPU: shrnutí 32 zpráv zhruba za 40 s, proces drží ~3 GB a po dvou minutách
  nečinnosti spadne na ~300 MB. E4B (12 GB) se na takový Mac nevejde. **Čeština proti Nano
  a GPU se ověřují až na telefonu.**
- **Kvalita E2B je podobná Nano, ne lepší**: fakta drží (data, ceny, číslo účtu), ale
  propsala „v češtině" z instrukce, „Notifikator změnily" a v návrzích se objevil nesmysl
  („Ne, chyba, nebudu přijímat."). Kvůli kvalitě je tu E4B.

- **`core/ai` je hranice** jako `core/telegram`: `com.google.mlkit.genai.*`
  a `com.google.ai.edge.litertlm.*` smí být jen v `androidMain/core/ai`. Poskytovatelé jdou
  přidávat (Apple Foundation Models ze Swiftu) bez zásahu do obrazovek. iOS je zatím `NoopOnDeviceAi`.
- **AICore není API, je to systémová služba** s Gemini Nano; aplikace na ni sahá přes ML Kit
  GenAI Prompt API (`com.google.mlkit:genai-prompt`, beta). Verze se bere z oficiální ukázky
  `googlesamples/mlkit` (`android/genai`) — Google Maven se z cloudového sandboxu stáhnout nedá,
  ukázka na GitHubu ano, a je to jediný spolehlivý zdroj přesného API.
- **Hotová API ML Kitu (Summarization, Proofreading, Rewriting) češtinu NEUMÍ**, jazyk se u nich
  vybírá ze seznamu. Prompt API jazyk nevolí, proto prototyp stojí na něm.
- **Instrukce promptu jsou anglicky, jazyk odpovědi výslovně podle aplikace** (`summaryPrompt`,
  testy `SummaryPromptTest`). Přepis se ořezává ZEZAČÁTKU — shrnutí má říct, co se dělo
  naposledy.
- **Stream Prompt API vydává PŘÍRŮSTKY**, `OnDeviceAi.generate` celý dosavadní text.
- **Varianta modelu je `ModelConfig`** (`ModelReleaseStage` STABLE / PREVIEW, `ModelPreference`
  FULL / FAST; dokumentace „Select a model"). Pro AICore je to JINÝ model: vlastní stav, vlastní
  stažení, vlastní limit. Proto každé volání `OnDeviceAi` bere `ModelVariant` a klient je jeden
  na variantu. Volba je přání — co opravdu běží, říká `getBaseModelName()`.
- **Aplikace nevidí seznam modelů v telefonu ani je nespravuje** — to AICore nedovoluje. Kdo chce
  vybírat konkrétní soubor modelu, potřebuje vlastní runtime (LiteRT-LM).
- **Přepis se přizpůsobuje skutečnému limitu** (`getTokenLimit`, `countTokens`): napřed odhad
  podle limitu (`transcriptCharsFor`, tři znaky na token), pak nejvýš třikrát zkrátit úměrně
  přesahu (`shrunkTranscriptChars`). Pod shrnutím je model, tokeny a kolik zpráv se vešlo.
- **Inferenci AICore pustí jen aplikaci v popředí** — z notifikace nebo úlohy to nepůjde.
- **Sledování stahování běží v `appScope`**, odchod z Nastavení ho neruší; nová obrazovka se ve
  stavu `Downloading` k němu připojí voláním `download()`. Samotné stahování Nano řídí AICore,
  Gemmu úloha WorkManageru (viz výš).
- **Zprávy ke shrnutí dává obrazovka** (`summarySource` nad `state.messages`, testy
  `ChatSummaryTest`), ale historie se kvůli němu nejdřív DOTÁHNE
  (`ConversationViewModel.loadForSummary`). Hned po otevření chatu je načtená jediná stránka,
  takže „posledních 60 zpráv" bralo 30 — a kolik jich bude, záviselo na tom, kolik uživatel
  odscrolloval. Kdy přestat dotahovat, je čistá funkce `summaryHasEnough`.
- **Useknutý výstup se POZNÁ, neodhaduje se z délky** (`Candidate.FinishReason.MAX_TOKENS` →
  `Generated.truncated`). Model přestane psát uprostřed a text vypadá jako hotový; patička
  přitom hlásí VSTUP („583 z 8 192"), takže uživatele naopak uklidní, že se všechno vešlo.
  Ověřeno na Pixelu 11 Pro Foldu: shrnutí dvanácti zpráv skončilo u šesté a podruhé u sedmé —
  strop je v TOKENECH, takže kam text dosáhne, je pokaždé jinde. Kus streamu bez textu nese jen
  `finishReason`, takže se nesmí přeskočit — jinak se useknutí ztratí právě na té zprávě, která
  ho hlásí.
- **Shrnutí po částech** (`summaryParts`, `partSummaryPrompt`, `combinedSummaryPrompt`, testy
  `SummaryPromptTest`). Když se přepis nevejde do jednoho promptu (`keptLineCount` < počet
  zpráv), rozdělí se na části o délce, která se do limitu vešla, plněné od nejnovějších zpráv;
  každá dostane krátké poznámky a z nich se složí shrnutí celku. Dřív se u chatu s tisíci
  nepřečtenými shrnul jen konec a zbytek tiše odpadl — patička sice napsala „vešlo se 80
  z 2 000", ale text se tvářil jako shrnutí všeho.
  - **Nejvýš tři části** (`MAX_SUMMARY_PARTS`): každá je celé generování navíc. Co se nevejde
    ani do nich, odpadne jako dřív (nejstarší) a patička to řekne.
  - **Části se znovu nepočítají na tokeny** — mají délku přepisu, který se do limitu ověřeně
    vešel, a instrukce jsou podobně dlouhé. Poznámky jsou krátké, složený prompt je menší než
    kterákoli část.
  - **Poznámky jsou už v jazyce odpovědi**, jména a fakta se tak nepřekládají dvakrát.
  - **Sheet ukazuje průběh** („Čtu část 1 z 2…", `ChatSummaryState.ReadingParts`) a patička
    „Shrnuto po 2 částech" — jinak by dlouho jen „připravoval".
  - **U Gemmy na emulátoru nastanou části až nad ~24 000 znaků** (strop přepisu
    `MAX_TRANSCRIPT_CHARS_CAP`, Gemma hlásí velký limit a tokeny nepočítá). **Platilo před
    odhadem tokenů** (`estimatedTokenCount`): teď se Gemma rozdělí zhruba nad 11 000 znaků
    českého přepisu. 48 zpráv
    (~12 000 znaků) se shrnulo jedním promptem, 108 zpráv (~27 000) ve dvou částech: „Čtu
    část 1 z 2…" ~25 s, část 2 ~55 s, složení ~30 s, pět celých odrážek.
  - **Mechanika funguje, kvalita E2B ne zcela** (ověřeno na emulátoru Fold, zkušební plánování
    výletu od testovacího bota): fakta ze starší části drží, ale spletla si den („posunuli na
    pátek") a novější změny (jiná chata, jiný sraz) skoro vynechala, přestože instrukce říká,
    že novější události mají přednost. Části se plní od nejnovějších, takže starší část bývá
    malá a nová velká — model se v ní ztrácí stejně jako v jednom dlouhém promptu. Jestli to
    E4B nebo Nano zvládnou líp, se ukáže až na telefonu.
- **Shrnutí bez úvodu a o uživateli ve druhé osobě** (`NO_INTRO_INSTRUCTION`,
  `readerInstruction`, `withoutIntro`; testy `SummaryPromptTest`). Porovnání na Foldu
  (Nano fast i Gemma 4 E4B) ukázalo dvě chyby, které měly OBA modely: úvodní „Zde je shrnutí
  konverzace:" a podpis uživatelových zpráv „Já" opsaný jako jméno v uvozovkách („Martin
  Glasnák a "Já" se…"). Prompt shrnutí na rozdíl od vysvětlení pokyn „začni rovnou odrážkou"
  neměl; přibyl do celku, částí i složení. O „Já" teď model ví, že je to čtenář, a píše
  o něm ve druhé osobě, česky výslovně tykáním (aplikace tyká). **Úvod navíc odstraní kód**
  (první řádek, který není odrážka a končí dvojtečkou, když po něm odrážky jsou) — i během
  psaní a i u poznámek k částem. Vysvětlení zprávy pokyn mělo a podpis „Já" v něm není.
  **Na Foldu po opravě neověřené.**
- **Odrážka má nejvýš 20 slov a useknutý text končí poslední celou odrážkou**
  (`completeLines`). Bez délky v instrukci psal model u delšího přepisu odrážku skoro ke každé
  zprávě a narazil na strop uprostřed věty; useknutá věta vypadala jako chyba modelu. Že je
  shrnutí neúplné, říká sheet dál zvlášť (`truncated`).
- **Čeština spotřebuje víc tokenů než angličtina**, na kterou jsou výchozí hodnoty laděné; proto
  `MAX_OUTPUT_TOKENS = 800`, ne 400. Není to „čím víc, tím líp" — platí se delším generováním
  (na Foldu 12–35 s).
- **Ověřeno na zařízení** (Pixel 11 Pro Fold, Android 17, AICore `thirdpartyexperimental`):
  `nano-v4-full`, limit vstupu 8 192 tokenů (6. 11. už 32 768), rychlejší varianta je opravdu jiný model
  (`nano-v4-fast`) a je připravená hned. **Shrnutí funguje i v letovém režimu** — model počítá
  v telefonu, nic se neposílá.
- **Preview varianta JEDE preview modelem — `getBaseModelName()` to jen neřekne.** S `preview`
  vrací jméno dál `nano-v4-full` / `nano-v4-fast` (jméno určuje jen `fast`), `checkStatus` dá
  `AVAILABLE` a limit je stejný jako u stabilní. Podle jména se proto preview poznat NEDÁ.
  Rozhoduje plánovač úloh AICore, který do logcatu píše číslo i název featury každého požadavku:
  ```
  InferenceTaskScheduler: submit (… 636/1 - SAPI Open Prompt (Regenerative Mode))
  InferenceTaskScheduler: submit (… 645/1 - SAPI Open Prompt Stable Fast Mode)
  InferenceTaskScheduler: submit (… 647/1 - SAPI Open Prompt Preview Full Mode)
  InferenceTaskScheduler: submit (… 646/1 - SAPI Open Prompt Preview Fast Mode)
  ```
  U 647 AICore načte jiný checkpoint (`ulm1b_ggml.cc: CreateModelFromCheckpoint`, delegát
  EdgeTPU) a paměť modelů (`dmabuf_rss_kb_at_inference_start`) vyskočí z 1,30 na 2,44 GB;
  první odpověď trvá ~2,4 s místo ~0,3 s. Ověřeno sondou 6. 11. 2026 (Pixel 11 Pro Fold,
  AICore `26.10.15…thirdpartyexperimental`): samostatná aplikace s jiným balíčkem, která projde
  čtyři varianty (`checkStatus`, `getBaseModelName`, `getTokenLimit`, jedna odpověď) — vedle
  nainstalovaného Plume z Play, bez odinstalace. Limit vstupu tentokrát 32 768 u všech variant
  (15. 10. bylo 8 192), mezitím se tedy změnil AICore nebo jeho model.
- **Dřívější závěr „preview tiše jede stabilním modelem" byl MYLNÝ.** Opíral se o shodné jméno
  a o tuhle chybu v logu s `genai-prompt` 1.0.0-beta4:
  ```
  GenAiProcessorKt: AICore failed with error type 3-PREPARATION_ERROR
    and error code 606-FEATURE_NOT_FOUND: Feature -1 is not available.
  ```
  Kontrolní běh sondy s beta4 ale ukázal, že generování i tak šlo na 647 (Preview Full Mode).
  `-1` patří jiným typům požadavků: ML Kit si překládá (varianta, typ požadavku) na číslo
  featury statickou funkcí (v beta4 `zzyt.zzb`, v beta5 `zzaaq.zzb`; `javap -c` ji najde jako
  jedinou s konstantami `sipush 636…655`). Beta4 znala pět typů (bez mezipaměti, vytvoření
  mezipaměti, s mezipamětí, strukturovaný bez a s mezipamětí) a preview měla číslo jen pro dva
  bez mezipaměti (plná 647 / 652, rychlá 646 / 653), pro ostatní `-1` — to jsou kontroly, které
  ML Kit spolkne. Beta5 má typy jen dva (`NORMAL_INFERENCE`, `STRUCTURED_OUTPUT_INFERENCE`)
  a číslo pro všechny kombinace, takže chyba z logu zmizela. **Poučení:** jméno modelu ani
  spolknutá chyba neříkají, čím AICore počítal — říká to `InferenceTaskScheduler`.
- **Proto zmizelo `previewSameAsStable`** (porovnání jmen preview a stabilní varianty) i hláška
  „Preview verze se tu nepoužije" pod přepínačem. Tvrdila opak pravdy přesně na zařízení, kde
  preview je. Spolehlivý příznak, že zařízení preview nemá, API nedává; přepínač to říká
  v popisku („pro tohle zařízení nemusí být").
- **Bump na `genai-prompt` 1.0.0-beta5 a `genai-schema` 1.0.0-alpha2:** veřejné API se jen
  rozšířilo (volání funkcí, role v `Content`, `sessionId`, `getAvailableFeatures()`
  s `Feature.THINKING_MODE` aj.), `isStructuredOutputFeatureAvailable` je zastaralé ve prospěch
  `getAvailableFeatures()`, `Candidate.text` už není nullable. `genai-schema` alpha2 přidal
  `GuideDetail.pattern` s výchozí hodnotou a `GenerableProvider` se hledá dál přes
  `ServiceLoader`, ruční providery se měnit nemusely. `ModelReleaseStage` (STABLE 0, PREVIEW 1)
  a `ModelPreference` (FAST 1, FULL 2) beze změny.
- **Labs nabízí čtyři modely, „Správa AI modelů" ukazuje jediný balík „Fast + Full [TPU]".**
  Proto je rychlá varianta připravená okamžitě a nic se nestahuje: full i fast jsou TENTÝŽ
  balík.
- **Preview Full je postavený na Gemma 4 E4B**, zatímco stabilní na E2B — tedy větší model
  a nejnadějnější směr pro kvalitu češtiny. **Přes ML Kit k němu cesta vede** (přepínač preview,
  viz výš); seznam modelů ale API nemá: `GenerativeModel` zná jen `getBaseModelName()` pro právě
  nakonfigurovanou variantu, a to jméno preview neodliší.
- **Na emulátoru se to vyzkoušet NEDÁ, ani v nejnovějším obrazu.** Android 17 (API 37,
  `google_apis_playstore_ps16k`) balíček `com.google.android.aicore` nemá; starší obrazy taky ne.
  **Řetězce „aicore" v `system.img` k ničemu neslouží jako důkaz** — `grep` jich najde desítky,
  ale jsou to klientská rozhraní `com.google.android.apps.aicore.aidl.*`, která si nese Dialer,
  autofill a detekce spamu. Rozhodne až `pm list packages` na běžícím systému.
- **Doinstalovat AICore z telefonu JDE a stejně to nepomůže.** Je v `/data/app` a není privileged
  (`flags=0x0`), takže `adb pull` tří split APK z Pixelu (128 MB) a `install-multiple` na emulátor
  projde, **služba nastartuje a ML Kit se k ní připojí** (v logu `Start proc … for bound-service
  AiCoreMultiUserService`). Pak ale přijde `606-FEATURE_NOT_FOUND: Feature 636 is not available`
  a v logu `Failed to fetch manifest config from source`: AICore v emulátoru nemá model ani
  hardware, na kterém by počítal. Sonda vrátila `status=Unsupported`, `countTokens=null`,
  `generate` selhalo — tedy **aplikace se zachová správně a nic nespadne**, což je to jediné, co
  se tím ověří.
- **`Feature 636` versus `Feature -1` je rozdíl, který něco říká.** Tady si ML Kit číslo featury
  odvodil správně a AICore ji nemá; `-1` u preview v beta4 je naopak mezera v převodu
  v `genai-prompt` (jen u typů požadavků s mezipamětí, generování samo šlo na 647, viz výš).
- **Detail k `info()`:** s doinstalovaným AICore vrátila `ModelInfo(name=null, tokenLimit=8192)`,
  tedy NENULOVÝ objekt bez jména. Na UI se to neprojeví (rozhoduje `status`), ale kdo by se ptal
  jen na `info() != null`, dostal by na nepodporovaném zařízení „model je".
- **Co na emulátoru ověřit JDE:** chování bez modelu (že se sekce v Nastavení nenabídne a nic
  nespadne — viz „Návrhy odpovědí"), vzhled podstrčeným stavem a čisté funkce testy. Na zařízení
  zbývá kvalita výstupu, rychlost, limit tokenů a useknutí.


## Qwen a další modely přes LiteRT-LM

LiteRT-LM spustí jakýkoli soubor `.litertlm`, ne jen Gemmu. Druhý stahovaný model je **Qwen**
(`AiProvider.Qwen`), jen text, Apache 2.0, ve třech velikostech jako volba u modelu (jako E2B / E4B
u Gemmy): **Qwen3 4B** (2,1 GB, 12 GB paměti) kvůli češtině — jestli ji umí líp než Gemma, má
ukázat „Porovnat s dalšími modely" na skutečné konverzaci —, **Qwen2.5 1,5B** (1,5 GB, 6 GB)
a **Qwen3 0,6B** (328 MB, 4 GB) pro telefony, na které Gemma nestačí.

- **Slabší telefon dostane sekci AI, i když se nevejde výchozí velikost.** Poskytovatel je
  dostupný, když zařízení unese aspoň JEDNU jeho velikost (`availability`); dřív se ptalo na
  výchozí variantu, a ta by Qwen na telefonu s 6 GB schovala celý. Uloženou velikost, na kterou
  paměť nestačí, `supportedModels` vymění za první dostupnou téhož modelu — Qweny jsou v katalogu
  od největšího, takže telefon dostane největší, který unese (test `SupportedModelsTest`).
- **Kontext je vlastnost souboru** (`LiteRtModel.contextTokens`): Qweny mají 4 096 tokenů, Gemma
  8 192. Engine dostane menší z obou (`maxNumTokens`) a podle toho i limit vstupu (`info`), takže
  shrnutí u Qwenu dřív přejde na části. Soubor Qwen2.5 má KV keš pevně na 4 096 (`ekv4096`).
- **Proč tyhle tři a ne jiné.** Qwen2.5 1,5B je z katalogu Gallery (`1_0_23.json`, 6 GB paměti),
  Qwen3 0,6B týž export jako Qwen3 4B a v oficiální tabulce LiteRT-LM. **Gemma 3 1B** (z Gallery,
  6 GB) je na Hugging Face za licenčním souhlasem — bez přihlášení vrací 401 a stahování ho neumí.
  **Qwen3.5 0,8B** (litert-community) má vrstvy s rekurentním stavem a podle karty modelu chce
  NOVÝ engine pro každou konverzaci, jinak navazuje na stav předchozí (LiteRT-LM#3165); aplikace
  engine mezi dotazy drží, takže by odpovídal nesmysly.

- **Katalog je jeden pro všechny stahované modely** (`LiteRtModel`, dřív `GemmaModel`): soubor,
  commit, velikost, paměť, komu patří (`provider`) a jestli bere zvuk a obraz (`multimodal`).
  Runtime pracuje se souborem (`ModelVariant.model`), poskytovatel jen vybírá, který to je.
  **Jména položek se ukládají** (volba velikosti Gemmy, jméno úlohy stahování), proto zůstalo
  `E2B` / `E4B`.
- **Engine je pořád jeden.** Gemma a Qwen se v paměti střídají; přechod znamená zavřít jeden
  a načíst druhý (až desítky sekund na CPU). Proto Qwen v automatice stojí PŘED Gemmou: kdo si
  ho vedle Gemmy zapne, chce ho na text, a Gemma zůstane hlasovkám a fotkám, které se pouštějí
  ťuknutím. Systémový model (Nano, Apple) má přednost před oběma.
- **Qwen3 4B potřebuje LiteRT-LM 0.19.** Soubor `qwen3_4b_mixed_int4.litertlm` je od 31. 10. 2026
  nový export (dynamický INT4, GPU kompozity, váhy v oddílu na konci souboru); GPU engine verzí
  0.18.x na něm na Androidu spadne (SIGSEGV, LiteRT-LM#3986), CPU chce aspoň 0.18. Dřívější soubor
  téhož jména (revize `a45912eb`) měl kontext jen 2 048 tokenů, na shrnutí málo. Všechno podle
  karty modelu na Hugging Face; commit v katalogu je ten po výměně souboru.
- **Bump 0.11 → 0.19.1 prošel bez jediné změny v kódu** (API `Engine`, `ConversationConfig`,
  `sendMessageAsync` s `extraContext` zůstalo). Od 0.18 je v AAR jen `liblitertlm_jni.so`
  (22,7 MB na arm64) bez samostatného `libLiteRtClGlAccelerator.so`; knihovna jména akcelerátorů
  zná a OpenCL kód nese, Gallery (0.18.1) přibaluje taky jen ji. **Jestli se na telefonu chytí
  GPU, ověřené není** — bez něj jede CPU (Qwen na Galaxy S26 ~10 tok/s místo ~20).
  AAR nově nese i `x86_64`; model se přesto mimo arm64 nenabízí (`deviceCanRun`).
- **Přemýšlení je vypnuté** (`enable_thinking = false` v `extraContext`, posílalo se už kvůli
  Gemmě). Qwen3 by jinak psal úvahy do zvláštního kanálu a odpověď by přišla mnohem později.
- **Paměť 12 GB jako E4B.** Na 8GB Pixelu 8a podle karty modelu při prvním výpočtu na GPU
  zbylo 243 MB volné paměti a běh se zastavil.
- **Pod zátěží čeká jako Gemma** (`avoidUnderLoad` podle `appManaged`, ne podle poskytovatele)
  a selhání jmenuje Qwen (`ai_failure_qwen`) — výjimka nese poskytovatele z varianty.
- **Úložiště a mazání jsou společné** (`LiteRtModel.variant`), Qwen se tam ukáže jako Gemma.
- **Co dalšího jde** (stav 11/2026): oficiálně testované Gemma 3n, Gemma 3 1B, Phi-4-mini,
  Qwen2.5 0,5B a 1,5B, Qwen3 0,6B; na litert-community Qwen3.5 0,8B a LFM3 od Liquid AI (vlastní
  licence s omezením podle obratu). Malé modely česky slabě, Phi-4-mini pomalý. Llama ani Mistral
  ve formátu LiteRT-LM nejsou — na ně je llama.cpp (viz „EuroLLM přes llama.cpp"). Nový model = řádek
  v `LiteRtModel`, a má-li vlastního poskytovatele, i v `AiProvider` (texty, pořadí automatiky).
- **Ověřeno**: testy a vzhled Nastavení na emulátoru se sondou (x86_64 emulátor model nespustí,
  kontrola zařízení dočasně podstrčená): Qwen v bloku Modely, „Automaticky (Qwen)" u textových
  úloh vedle Gemmy, věta o Qwenu pod úlohami; jako telefon se 6 GB jen Qwen bez přepínače,
  velikost sama na 1,5B, přepnutí na 0,6B („328 MB"). **Na telefonu neověřené nic: stažení
  Qwenu, jeho výstup a čeština, ani Gemma na nové verzi knihovny.**

## Desktop

Gemma a Qwen běží i na desktopu (Windows x64, macOS arm64, Linux), stejným runtime jako na
Androidu (`liteRtMain` v `:client:ai-core`, `LiteRtPlatform`); co je jinak (Java 21, nativní knihovna jen pro systém
balíčku, modely mimo Roaming, stahování v procesu), je v `docs/poznatky/desktop.md`, „Model
v zařízení (LiteRT-LM)". Gemini Nano a Apple tam nejsou.

## Runtime jako součást z Google Play

Knihovna LiteRT-LM (nativní `liblitertlm_jni.so`, 23 MB na arm64, k tomu Gson a kotlin-reflect)
není v základu aplikace. Na Androidu je to **součást z Google Play stahovaná až na požádání**
(Play Feature Delivery, modul :client:litertFeature; do publikace aplikace dočasně instalovaná
s aplikací, viz níž), na desktopu je přibalená. Model v zařízení
si zapne málokdo, a každý další runtime (llama.cpp a spol.) by jinak zvětšoval aplikaci všem.
Naměřeno `bundletool get-size` nad debug AAB pro arm64: základ 63,5 MB, se součástí 74,8 MB ke
stažení.

- **Tři moduly**: :client:ai-engine (rozhraní `LlmEngineFactory` / `LlmEngine` / `LlmConversation`,
  bez závislostí), :client:ai-litert (implementace nad LiteRT-LM, jediné místo s
  `com.google.ai.edge.litertlm`) a :client:litertFeature (dynamic-feature bez kódu, jen balí
  ai-litert). `shared` vidí jen ai-engine: stahování modelu, stav a výběr modelu
  (`LiteRtOnDeviceAi`) zůstaly, kde byly, vyčleněný je jen engine.
- **Implementace se hledá REFLEXÍ podle jména** (`LITERT_ENGINE_FACTORY`, `loadEngineFactory`).
  Základ na třídy součásti nevidí ani při překladu; na desktopu by ai-litert nad `shared` dělal
  kruh. Keep pravidlo je v `androidApp/proguard-rules.pro` — R8 zpracovává základ i součást
  dohromady. Bez něj by model po stažení hlásil chybu jen v release buildu.
- **Runtime se stahuje SPOLU s modelem a NAPŘED** (`LiteRtOnDeviceAi.download`,
  `installRuntimeThenDownload`, testy `LiteRtRuntimeTest`). Desítky MB proti gigabajtům: když
  instalace selže (aplikace není z Play), uživatel to má vědět hned. Stav pro obrazovku je pořád
  `Downloading`, průběh napřed v bajtech runtime, pak modelu. Instalace běží v `scope` runtime,
  ne v obrazovce, a „Zastavit" ji zruší i v Play (`cancelInstall`).
- **`Ready` = soubor modelu A runtime.** Stažený model bez runtime (aktualizace z verze, která
  runtime nesla v sobě, nebo součást odinstalovaná) si runtime **doinstaluje sám**, jakmile se na
  model zeptá funkce nebo Nastavení (`restoreRuntime` ve `status`): uživatel si model už vybral
  a stáhl, ťukat kvůli aktualizaci aplikace by nedávalo smysl. Stav je mezitím `Downloading`.
  Kdo má AI vypnutou, na model se nikdo neptá a nic se nestahuje. **Jen jednou za proces** —
  selhávající instalace (aplikace mimo Play) by se jinak pouštěla s každým dotazem na stav; pak
  zůstává `Paused` a „Pokračovat ve stahování" doinstaluje jen runtime.
- **Instalaci dělá Play, aplikace poslouchá** (`PlayLiteRtRuntime`). Na instalaci, která běží
  z minulého ťuknutí, se napojí přes `sessionStates` — druhou by Play odmítl. Po instalaci za
  běhu je součást vidět až po `SplitCompat.install` (volá ho i `MainApplication`
  v `attachBaseContext`) a nativní knihovnu napřed načte `SplitInstallHelper.loadLibrary`;
  LiteRT-LM si ji bere `System.loadLibrary`, který v čerstvém splitu cestu najít nemusí, a už
  načtenou knihovnu linker najde podle jména. Po restartu procesu je součást běžný split APK.
- **Souhlas Play není chyba.** Play si před instalací součásti může říct o souhlas uživatele
  (`REQUIRES_USER_CONFIRMATION`; dokumentace jmenuje aplikaci, kterou nenainstaloval Play,
  a větší stahování přes mobilní data). Aplikace to brala jako selhání, a „Stáhnout model" tak
  hned ukázalo „Model se nepodařilo stáhnout" — uživatel to hlásil z telefonu na mobilních
  datech (log z telefonu ověřený není, kód chyby tedy taky ne; pozdější log z Foldu ukázal -15,
  viz níž). Teď runtime žádost předá
  `SplitInstallConfirmations` a dialog Play ukáže `SplitInstallConfirmationHost` v kořeni
  `MainActivity` (`startConfirmationDialogForResult` s launcherem, jako nabídka aktualizace).
  Souhlas instalaci rozběhne, odmítnutí stahování zastaví (`Paused`), dialog, který nejde
  ukázat, je chyba. Hostitel sedí v kořeni, ne v Nastavení: instalaci spouští i doinstalace
  u už staženého modelu (`restoreRuntime`) a dialog má naskočit, kde uživatel zrovna je; ukáže
  se, až je aktivita na obrazovce (`collectAsStateWithLifecycle`).
- **Selhání součásti má vlastní hlášku, ne „Model se nepodařilo stáhnout"**
  (`RuntimeInstallException` ve společném kódu, stav `OnDeviceModelState.RuntimeInstallFailed`):
  „Model se nestáhl: Google Play nedodal část aplikace, na které běží (chyba -15)". Model se
  v tu chvíli ještě ani nezačal stahovat a kdo chybu hlásí, má ze snímku poznat, která z nich to
  je — kód od Play je to, podle čeho se dá dohledat (`SplitInstallErrorCode`). Ověřeno na
  emulátoru s debug APK bez součásti (-15 jako mimo Play).
- **-15 (`APP_NOT_OWNED`) přichází i u aplikace z Play, dokud Plume v Play není publikovaná.**
  Fold s 0.3.125 z interního testování (`installerPackageName=com.android.vending`, účet je
  tester a aplikaci v Play vlastní) dostal na `startInstall([litertFeature])` -15 za 14 ms —
  Play rozhoduje z dat v telefonu, na server se neptá, a důvod nezaloguje ani s `setprop
  log.tag.Finsky VERBOSE`. Součásti byly v Play Console v balíčku (on demand), knihovna je
  aktuální (`feature-delivery` 2.1.0). **Nepomohlo vymazání mezipaměti Play Store ani
  odinstalace a nová instalace z Play.** Klíčová stopa přišla z Play Console: Internal app
  sharing odmítl s tím, že aplikaci je „potřeba publikovat". Plume je zatím jen koncept
  s interním testováním — to instalaci z Play dovolí, ale publikací není, a „vlastnictví",
  které Play před doinstalováním součásti ověřuje, u konceptu nejspíš nevzniká. **Výslovně to
  Google nikde nepíše** (návod k doručování na požádání interní test jako způsob testování
  uvádí); je to závěr z vyloučení ostatních příčin. Ověří se, až bude aplikace publikovaná
  aspoň v uzavřeném testu (úkol v `docs/roadmap.md`, „Než se dá vydat na veřejnost").
- **Do publikace jsou proto obě součásti DOČASNĚ „s instalací"** (`<dist:install-time />`
  místo `<dist:on-demand />` v manifestech `litertFeature` a `llamacppFeature`, komentář u nich).
  Moduly zůstávají samostatné a kód se nemění: Play i bundletool nevyjímatelné součásti
  s instalací **slučují do základního APK** (žádné `split_litertFeature.apk`, knihovny leží
  v `split_config.arm64_v8a.apk`) a Play Core je hlásí v `installedModules` podle metadat
  sloučených modulů, takže `installed()` vrátí `true` a doinstalace se vůbec nespustí. Stahování
  z Play vzroste na arm64 z 63,2 na 76,9 MB (debug AAB, `bundletool get-size total`), na
  armeabi-v7a jen o 1,3 MB (prázdné knihovny a dex). **Před produkcí vrátit `on-demand`** —
  tím se vrátí i ověřování z odstavce výš. Ověřeno bundletoolem (`build-apks --connected-device`
  a `install-apks`) na emulátoru s dříve staženou Gemmou: bez doinstalace „Model připravený",
  shrnutí načetlo `liblitertlm_jni.so` ze základu (`nativeloader … ok`) a počítalo.
- **Jméno modulu je jméno součásti** (`PlayLiteRtRuntime.MODULE` = `litertFeature`).
  Přejmenování projektu by instalaci tiše rozbilo.
- **bundletool chce stejnou sadu ABI ve všech modulech s nativními knihovnami** („All modules
  with native libraries must support the same set of ABIs"). Základ má kvůli TDLib čtyři,
  LiteRT-LM jen arm64-v8a a x86_64. Součást proto nese pro armeabi-v7a a x86 prázdný
  `libplume_no_<součást>.so` (plugin `plume.feature-abi-placeholders` v build-logic, sdílí ho
  obě součásti); tam se model nenabízí (`canRun` chce
  arm64), takže se součást nikdy nestáhne. AGP při buildu hlásí, že ho nejde ořezat — to je
  v pořádku. Jestli prázdný soubor projde kontrolami Play Console, **ověřené není**.
- **Rozdělení APK podle ABI (`splits.abi`) muselo pryč**: s `dynamicFeatures` AGP součást
  nesestaví („Cannot find PROCESSED_RES output"). Debug APK je teď jedno univerzální,
  `androidApp-debug.apk`.
- **Mimo Play se součást nestáhne.** `assembleDebug` a `adb install` dají aplikaci bez runtime
  a stažení modelu skončí chybou — Play vrátí `-15` (`APP_NOT_OWNED`, aplikaci nenainstaloval
  on), v logcatu `SplitInstallService : onError(-15)`. Ověřeno na emulátoru s EuroLLM: hned
  „Model se nepodařilo stáhnout", se součástí nainstalovanou spolu s aplikací
  (`adb install-multiple`) se model začal stahovat. Pro vývoj buď obojí naráz: `./gradlew
  :client:androidApp:assembleDebug :client:litertFeature:assembleDebug` a `adb install-multiple
  androidApp-debug.apk litertFeature-debug.apk` (součást je pak nainstalovaná rovnou), nebo
  instalaci z Play napodobit: `bundleDebug`, `bundletool build-apks --local-testing` a
  `install-apks` — aplikace dostane jen základ a součást „stáhne" `FakeSplitInstallManager`
  z `/sdcard/Android/data/eu.tintera.plume/files/local_testing`. bundletool chce `--aapt2`
  z build-tools a na classpath i kotlin-stdlib (jar z Maven není spustitelný).
- **Ověřeno**: testy, obsah APK (`dexdump`: v základu žádné `com.google.ai.edge`, Gson ani
  kotlin-reflect, v součásti všechno), R8 release (`LiteRtEngineFactory` pod svým jménem, jen
  v dexu součásti), AAB a bundletool `--local-testing` na emulátoru se sondou (x86_64 v `canRun`):
  aplikace se základem bez součásti nastartuje, „Stáhnout model" napřed doinstaluje součást
  (`SplitCompat: Splits installed`, nativní knihovna přidaná do classloaderu), pak stáhne
  Qwen3 0,6B, a shrnutí v témže procesu načte `liblitertlm_jni.so` ze splitu (`nativeloader …
  ok`), otevře engine a pošle zprávu. Výstup modelu na emulátoru nevyšel kvůli GPU backendu bez
  OpenCL, ne kvůli součásti — to od té doby řeší náhrada CPU při generování (viz „Bez OpenCL
  se GPU engine NAČTE BEZ CHYBY"). **Neověřené: skutečná instalace z Play (interní testování) a jestli
  Play Console vezme prázdné knihovny pro armeabi-v7a a x86.**

## EuroLLM přes llama.cpp

Kvůli češtině je třetí stahovaný model **EuroLLM** (`AiProvider.EuroLlm`) od utter-project,
trénovaný na všech jazycích EU, Apache 2.0. Ve formátu LiteRT-LM není, takže běží přes
**llama.cpp** (soubory GGUF) — druhý runtime vedle LiteRT-LM, na Androidu i desktopu; GPU jen
přes Metal na Macu s Apple Silicon.
Velikosti: **EuroLLM 9B** (verze 2512, Q4_K_M, 5,2 GiB, 16 GB paměti) a **EuroMoE 2,6B**
(směs expertů, aktivních 0,6B, Q4_K_M, 1,5 GiB, 6 GB). Kvantizace od mradermacher — originál 9B
je na Hugging Face za souhlasem, GGUF kopie ne.

- **Runtime je vlastnost souboru** (`LiteRtModel.runtime`, `ModelRuntime`), ne poskytovatele.
  `LiteRtOnDeviceAi` dostává runtime pro každý druh (`runtimes`), stahování, stav, uvolňování
  i porovnání jsou společné; jméno třídy zůstalo z doby, kdy byl jen LiteRT-LM.
- **llama.cpp je git submodul** (`client/ai-llamacpp/llama.cpp`, připnutý na vydání `b12170`,
  mělký). Oba release workflow proto dělají checkout se `submodules: true`. Hotové wrappery
  z Maven jsou od jednotlivců — stejné riziko jako wrapper TDLib, jen bez hranice kolem.
  Bump = `git -C client/ai-llamacpp/llama.cpp fetch --depth 1 origin tag bNNNNN`, checkout
  a commit ukazatele submodulu.
- **Tři moduly: kód, knihovna pro Android, knihovna pro desktop.** :client:ai-llamacpp je čistě
  JVM modul s kódem v Kotlinu (`LlamaCppEngineFactory`), JNI mostem a CMakeLists; knihovnu
  z téhož CMakeLists staví :client:ai-llamacpp-android (NDK r29 kvůli 16KB stránkám, do součásti
  z Play) a :client:ai-llamacpp-desktop (CMake systému, do jaru), viz „EuroLLM na desktopu"
  v `docs/poznatky/desktop.md`. Kód nesmí stavět nic nativního sám: knihovna pro Mac by skončila
  i v součásti pro Android. KMP to není, protože KMP plugin pro Android nativní build z CMake
  neumí. Kód rozezná platformu jen podle toho, jestli knihovna leží v jaru
  (`LlamaNative.loadLibrary`), a podle VM (počet vláken).
- **Jedna knihovna `libplume_llama.so` (6,6 MB na arm64)**: llama.cpp a ggml staticky, k tomu
  vlastní JNI (`plume_llama.cpp`) jen nad C API z `llama.h`. Příklad `examples/llama.android`
  staví backendy zvlášť (`GGML_BACKEND_DL`) a CPU variantu načítá z ADRESÁŘE knihoven — split
  APK z Play je má uvnitř APK, takže by nebylo odkud. Knihovna `common` z příkladů se mění
  s každým vydáním, proto jen `llama.h` (šablona chatu `llama_chat_apply_template`, vzorkování
  řetězem `llama_sampler_*`).
- **Bez OpenMP a KleidiAI.** OpenMP přidá sdílenou `libomp.so`, kterou by linker v součásti
  doinstalované za běhu hledat nemusel; KleidiAI si zdrojáky stahuje z GitHubu při konfiguraci.
  arm64 se staví pro `armv8.2-a+dotprod+fp16` (všechny telefony, na kterých se model nabízí);
  i8mm a SVE by na starších jádrech skončily SIGILL.
- **Text přes JNI jako bajty UTF-8**, ne `jstring`: `NewStringUTF` a `GetStringUTFChars` jedou
  v „modified UTF-8", ve kterém emoji vypadají jinak a CheckJNI na ně aplikaci shodí. Token, který
  nedotvoří znak, se drží, dokud znak nedokončí další (`completeUtf8Prefix`). Proto na desktopu
  NEPLATÍ obcházka LiteRT-LM, která emoji ze vstupu vypouští (`DesktopLiteRtPlatform.modelText`):
  llama.cpp dostane standardní UTF-8 a „Ahoj 😊" projde. **Totéž cesta k modelu**: na Windows ji
  ggml otevírá jako UTF-8 (`ggml_fopen` → `_wfopen`) a jméno uživatele v `%LOCALAPPDATA%`
  bývá s diakritikou.
- **GPU jen Metal na Macu, jinde CPU.** Jestli runtime GPU má, říká platforma
  (`LiteRtPlatform.gpu`, výchozí jen LiteRT-LM; desktop přidá llama.cpp na macOS arm64), ne
  `ModelRuntime`. Na Androidu se o GPU nežádá: Vulkan ani OpenCL backend llama.cpp se nepřekládá.
  Přijde-li přesto `LlmBackend.Gpu` a build GPU nemá (`LlamaNative.hasGpu`), factory vyhodí dřív,
  než začne načítat. Podrobnosti v `docs/poznatky/desktop.md`, „EuroLLM na desktopu".
- **Ořez knihovny dělá modul, který ji balí** (:client:llamacppFeature), a jeho NDK: bez
  `ndkVersion` v součásti AGP ořez přeskočil („Unable to strip") a do AAB šlo 55 MB místo 6,6 MB.
- **Na desktopu EuroLLM je taky**, podrobnosti v `docs/poznatky/desktop.md`,
  „EuroLLM na desktopu (llama.cpp)".
- **V automatice stojí EuroLLM před Qwenem** (`AUTOMATIC_ORDER`): kdo si ho zapne, chce ho na
  text kvůli češtině. Systémové modely mají přednost dál.
- **Ověřeno na emulátoru x86_64** (8 GB RAM, `bundletool --local-testing`, sonda v `canRun`
  pouštějící x86_64 a EuroMoE bez kontroly paměti, necommitovaná): zapnutí EuroLLM nabídne
  EuroMoE 2,6B, „Stáhnout model" doinstaluje součást `llamacppFeature` a stáhne 1,5 GiB, shrnutí
  testovacího chatu načte `libplume_llama.so` ze splitu a odpoví česky za 15 s; „Porovnat
  s dalšími modely" pak v témže procesu přepne na Qwen přes LiteRT-LM. **Se 4 GB RAM emulátor
  model jen stránkoval do swapu a nedopočítal** — pod `minMemoryGb` se model nenabízí právě
  proto. **Na telefonu neověřené nic**: rychlost na arm64, EuroLLM 9B (emulátor na něj nemá
  paměť) ani jestli je čeština opravdu lepší než u Gemmy a Qwenu — to má ukázat porovnání na
  skutečné konverzaci.

## Aya Expanse (Cohere Labs)

Čtvrtý stahovaný model, **Aya Expanse 8B** (`AiProvider.Aya`), kvůli češtině: Cohere Labs ho
trénovali na 23 jazyků a čeština je mezi nimi výslovně. Jde stejnou cestou jako EuroLLM
(GGUF přes llama.cpp, `ModelRuntime.LlamaCpp`), takže runtime, stahování i Metal na Macu jsou
společné; nová je jen položka `LiteRtModel.AyaExpanse8B` a poskytovatel v Nastavení.

- **Licence CC BY-NC 4.0 a pravidla použití Cohere Labs** — jen nekomerčně, s uvedením autora.
  Plume nic nevydělává (sponzorované zprávy v kanálech jsou výnos Telegramu), ale **licenci
  přijímá ten, kdo model stahuje**, tedy uživatel: aplikace soubor nebalí, stahuje ho na
  požádání. Proto věta o licenci stojí v Nastavení přímo nad „Stáhnout model"
  (`settings_aya_license`) a jméno Cohere Labs je v popisu poskytovatele. Kdo by Plume (MIT)
  forkl komerčně, Ayu tak používat nesmí.
- **Originál je za souhlasem** (`CohereLabs/aya-expanse-8b`, `gated: auto` — přihlášení
  a sdílení kontaktu), aplikace to neumí. Stahuje se kvantizace od bartowski, která za souhlasem
  není; licence nekomerční šíření kopií s uvedením autora dovoluje. Stejně jako u EuroLLM 9B.
- **Jediná velikost.** Druhá je 32B (na telefon ani běžný počítač). 8B v Q4_K_M má 5,1 GB, paměť
  16 GB a kontext 4 096 jako EuroLLM 9B; na menších zařízeních se Aya nenabídne vůbec
  (`availability`). Volba velikosti se proto neukazuje (`SizeChoices` u jedné nic nekreslí).
- **Architektura Command R** (`cohere` v llama.cpp) a šablona chatu s `<|START_OF_TURN_TOKEN|>`
  — `llama_chat_apply_template` ji pozná ze šablony v souboru, nic vlastního není potřeba.
- **V automatice za EuroLLM, před Qwenem** (`AUTOMATIC_ORDER`), jen proto, že EuroLLM přibyl
  dřív; kdo zapne oba, rozdělí si úlohy v „Co čím počítat".
- **Ověřeno na Macu (M5, Metal)** dočasným testem nad `LlamaCppEngineFactory`: soubor sedí
  (SHA-256 `9592bad9…`), načte se za 3,4 s. Shrnutí krátkého českého chatu (chata, auto, pivo,
  odjezd v 9, předpověď) za 6,1 s **věcně správně a česky bez chyb**, jen pokyn „dvě až tři
  věty ve druhé osobě" nedodržela a napsala odrážky. EuroMoE 2,6B na tomtéž za 0,45 s, ale
  s chybami (pivo přisoudil čtenáři, počasí vynechal). llama.cpp při načtení hlásí
  „special_eos_id is not in special_eog_ids" — odpověď přesto skončila sama, ne na stropu tokenů.
  S EuroLLM 9B srovnané není; na telefonu a v přihlášené aplikaci neověřené.

## Víc modelů naráz a co čím počítat

Nano zvuk ani obraz nebere, Gemma je umí, jenže drží gigabajty RAM. Kdo měl na všechno Gemmu
kvůli přepisu hlasovek, platil pamětí i za každé shrnutí. Proto se modely ZAPÍNAJÍ (i oba)
a úlohy se jim přiřazují.

- **Úloha nese, co modelu dává** (`AiTask.input`: text, zvuk, obraz) a kdo ji zvládne, je
  statické (`AiProvider.handles`): zvuk a obraz jen Gemma. Co runtime UMÍ, se neptá runtime —
  jestli je připravený, říká `status`.
- **„Automaticky" (výchozí) bere první PŘIPRAVENÝ model v pořadí Nano → Apple → Gemma**, jinak
  první, který zařízení umí (funkce pak nabídne stažení). Systémový model nic nestojí, Gemma se
  načte až kvůli úloze, kterou dostala, a s Nano na textu se uvolní už po 30 s — gigabajty
  v paměti jen chvíli po přepisu hlasovky nebo popisu fotky.
- **Záloha jen podle stavu modelu, ne podle chyby jednoho volání.** `Busy` u Nano nemá kvůli
  jedné odpovědi načíst Gemmu; stahující se Nano naopak text Gemmě předá (je připravená).
- **Ruční přiřazení platí, i když model není připravený**: funkce řekne, že model chybí,
  a řádek v Nastavení „Gemma · model zatím není připravený". Tiše vzít jiný model by udělalo
  z volby dekoraci. **Přiřazení k VYPNUTÉMU modelu se nepočítá** (`AiModels.assignment`) — úloha
  jede automaticky, ne nikam — a nemaže se, takže po opětovném zapnutí platí zase.
- **Úloha bez kandidáta** (zvuk, Gemma vypnutá) dostane první zapnutý model. Ten úlohu neumí
  (`canTranscribe` = `false`), takže se funkce nenabídne; volající nemusí řešit `null`.
- **„Automaticky" se neukládá** (`on_device_ai_tasks` nese jen ruční výjimky jako
  `Summary=Gemma`), aby šlo pravidlo zlepšit bez migrace.
- **Dřívější jediný poskytovatel je výchozí seznam**: bez `on_device_ai_models` platí
  `on_device_ai_provider`, bez něj Nano. Kdo měl Gemmu, nepřijde o ni; kdo měl Nano, nezačne
  stahovat gigabajty. Druhý model se sám nezapne nikdy.
- **Nastavení**: blok Modely má u každého modelu přepínač (jen když jich zařízení umí víc;
  poslední zapnutý vypnout nejde, na to je hlavní vypínač), volby modelu a stav stažení. Blok
  „Co čím počítat" je jen s víc zapnutými modely a má řádek jen pro úlohu, kterou zvládne víc
  z nich — přepis a popis fotek umí jen Gemma, to říká věta pod řádky. „Automaticky (Gemini
  Nano)" ukazuje, kam automatika úlohu poslala (`OnDeviceAiSettingsViewModel.automatic`).
- **Stav se vede pro každý model zvlášť a i pro vypnutý**: stažená Gemma zabírá místo, i když
  ji nic nepoužívá, takže vypnutá stažená (nebo zčásti stažená) má dál „Smazat model". Vypnutí
  Gemmy během stahování stahování zastaví — úloha WorkManageru by jinak stahovala model, o který
  uživatel právě řekl, že nestojí.
- **Rada u hlasovky „přepni na Gemmu" je teď „zapni si k němu Gemmu"** (`OnDeviceFix.SwitchToGemma`
  se nepřejmenoval: posílá se hodinkám jako `switch_to_gemma`).
- **Na zařízení neověřené**: Nano a Gemma vedle sebe jdou jen na telefonu s AICore (Fold),
  emulátor Nano nemá. Na emulátoru se ověří Gemma sama a vzhled Nastavení.

## Úsporný režim a přehřátí

Pod úsporným režimem nebo od `THERMAL_STATUS_MODERATE` Gemma **sama od sebe** nic nepočítá
(`proactiveVariant`, `DeviceLoad`, `AndroidDeviceLoad`; testy `DeviceLoadTest`). Týká se úloh,
které běží bez ťuknutí: návrhy odpovědí, varování před podvodem, triáž, termíny ve zprávách
a popisy fotek na pozadí.

- **Úlohy na ťuknutí jedou dál** (shrnutí, vysvětlení, přepis hlasovky, přepis konceptu,
  hledání, popis fotky v prohlížeči): o výsledek si uživatel právě řekl.
- **Nano a Apple pod zátěží jedou** — počítá je systém, který si úsporu řídí sám, a aplikace
  kvůli nim nic nenačítá. S oběma modely tak pod zátěží běží dál všechno, co dostalo Nano.
- **I ručně přiřazená Gemma počká.** Úsporný režim je taky volba uživatele; úloha, kterou si
  nevyžádal ťuknutím, se projeví s další změnou (nová zpráva, otevřený chat), až zátěž pomine.
- **Proč ne ve `chooseProvider`**: ten vrací poskytovatele vždycky, a „pod zátěží jen Nano"
  by u uživatele, který Nano nezapnul, Nano potichu pustilo. `null` z `proactiveVariant` znamená
  „teď ne" a volající to obslouží stejnou větví jako nepřipravený model.
- **Čte se při volání** (`isPowerSaveMode`, `currentThermalStatus`), bez naslouchání — úloha se
  ptá těsně před tím, než by model pustila.
- **Nastavení u Gemmy to říká větou** (`settings_gemma_under_load`): uživatel s jedinou Gemmou by
  jinak nepoznal, proč návrhy a varování zmizely.
- **Emulátor**: úsporný režim zapne `adb shell cmd power set-mode 1` (zpět `0`;
  `settings put global low_power 1` na Androidu 17 nezabral), teplotu podstrčí
  `adb shell cmd thermalservice override-status 2` (zpět `reset`). Ověřeno sondou, která
  logovala `strained`: úspora i `MODERATE` → `true`, vypnuto i `LIGHT` (1) → `false`.
  Samotné vynechání Gemmy v triáži na emulátoru vidět nebylo (žádný kandidát); pravidlo mají
  testy (`DeviceLoadTest`, `ReplySuggestionsViewModelTest`).

## Apple Intelligence na iOS (Foundation Models)

Na iOS 26+ počítá model v zařízení **Apple Intelligence** (`AiProvider.Apple`,
`AppleOnDeviceAi` v `iosMain`). Je to systémový model jako Gemini Nano: stahuje a aktualizuje ho
iOS, aplikace ho jen používá.

- **Kotlin/Native na `FoundationModels` nedosáhne** (čistě Swiftové API bez Objective-C). Kotlin
  proto vystavuje rozhraní `OnDeviceTextModel` (do frameworku jde jako Objective-C protokol),
  Swift ho implementuje (`iosApp/iosApp/AppleTextModel.swift`, `LanguageModelSession.streamResponse`)
  a v `iOSApp.init` ho předá `IosOnDeviceModel.model` **před** `initKoin` — Koin podle něj
  vybere `AppleOnDeviceAi`, jinak `NoopOnDeviceAi`. Stejný vzor má i hlášení pádů.
- **Deployment target je iOS 18.2**, takže `@available(iOS 26, *)` a `#if canImport` a framework
  **slabě linkovaný** — Swift to udělá sám (`otool -l` nad `Plume.debug.dylib`: `LC_LOAD_WEAK_DYLIB`
  pro FoundationModels). Starší iOS aplikaci spustí, jen bez AI.
- **Snímek, ne přírůstek**: stream Foundation Models dává celý dosavadní text (`snapshot.content`),
  přesně jak `OnDeviceAi.generate` slibuje; Kotlin ho bere `callbackFlow` a `conflate`.
  Zrušení sběru zruší Swiftový `Task`.
- **Chyby podle JMÉNA případu** (`String(describing:)`): iOS 27 je přejmenoval
  (`GenerationError` → `LanguageModelError` a spol.), takže `switch` nad typem by na jedné
  verzi nepoznal nic. Kontext → `TooLarge`, jazyk → `UnsupportedLanguage`, ochranné filtry →
  `Refused` (oba nové důvody nejsou „reportable"), vytížení → `Busy`, chybějící soubory modelu
  → `Unavailable`.
- **Kontext 4 096 tokenů** (dotaz na něj je až od iOS 27), počítání tokenů až od 26.4 a jen
  asynchronně — délka přepisu se odhaduje z limitu jako u Gemmy.
- **Vypnutá Apple Intelligence nebo model, který systém teprve stahuje, = `Unsupported`**:
  sekce AI v Nastavení se pak nenabídne. Stav „zapni si Apple Intelligence v Nastavení" zatím
  nastavení neumí.
- **Strukturovaný výstup, přepis řeči a popis obrázků na iOS zatím ne** — Foundation Models
  je umí (`@Generable`, obrázky), ale každý chce vlastní Swift most.
- **Simulátor používá model HOSTITELE**: na Macu s vypnutou Apple Intelligence hlásí
  simulátor model jako dostupný, ale generování spadne na `ModelManagerError` 1026 (chybí
  soubory modelu) — proto ji `AppleTextModel` řadí jako `Unavailable`. Ověřit generování jde
  jen na Macu se zapnutou Apple Intelligence, nebo na iPhonu s iOS 26+.
- **Ověřeno v simulátoru (iOS 26.2)**: Nastavení → On-device AI ukáže „Model ready: Apple
  Intelligence, Input limit: 4 096 tokens", bez volby poskytovatele (jediný dostupný).
  Generování ověřené samostatným Swift programem spuštěným v simulátoru (`simctl spawn`):
  `availability: available`, ale `ModelManagerError` 1026, protože Apple Intelligence je na
  Macu vypnutá. **Skutečné generování (a čeština) neověřené** — Apple Intelligence češtinu
  v seznamu jazyků nemá a česká konverzace může skončit `UnsupportedLanguage`.
## Přepis řeči (zvukový vstup Gemmy)

Gemma 4 umí zvuk (`OnDeviceAi.transcribe`); podrobně v `prohlizec-medii.md`, „Přepis v telefonu".
Pro tuhle oblast podstatné: **engine se zvukem je jiný engine** (`EngineConfig.audioBackend`),
`withEngine(audio = true)` ho načte a pak slouží všemu; bez zvuku se kvůli přepisu přenačte.
Na emulátoru (CPU) 7,5 s řeči za 11 s i s načtením.

## Popis fotky (obrazový vstup Gemmy)

Gemma 4 umí i obraz (`OnDeviceAi.describeImage`, prompt `ImagePrompt.kt`); v aplikaci ho
používá prohlížeč (`prohlizec-medii.md`, „Popis fotky"). LiteRT-LM 0.11.0 bere
`Content.ImageBytes` / `Content.ImageFile` vedle textu v jedné zprávě, stejně jako zvuk.

- **Engine s obrazem je zase jiný engine** (`EngineConfig.visionBackend`, `maxNumImages = 1`).
  Obraz počítá týž backend jako text (Gallery: GPU, když jde) a s CPU zálohou spadne spolu
  s ním. **Přenačtený engine si nechá, co uměl** (`withEngine`: zvuk i obraz se sčítají), jinak
  by se přepis hlasovky a popis fotky střídaly přenačítáním po deseti vteřinách.
- **Obrázek dekóduje systém a zmenší na 1024 px** (`ImageInput`, PNG jako v Gallery). Kodér
  si ho zmenší sám (v logu `vision_280` — signatura kodéru), plný originál by jen držel desítky MB.
- **E2B píše česky s chybami** („malownicý kraj", „limevé barvy") — obsah sedí, sloh ne.
  U snímku obrazovky s pokynem „řekni, o čem text je, neopisuj ho celý" vyjmenovala všechny
  popisky v uvozovkách; „jen druh textu (účtenka, cedule, obrazovka aplikace), necituj" výčet
  zkrátila na pár položek. Úplně se ho nezbaví.
- **Čas na emulátoru (CPU)**: první popis i s přenačtením enginu ~13 s.

## Strukturovaný výstup

Gemini Nano umí vrátit odpověď rovnou jako typ (`OnDeviceAi.generateStructured`, ML Kit
`GenerateTypedContentRequest`): tvar drží model, ne náš parser. Dnes ho používají **návrhy
odpovědí** (`ReplySuggestionsOutput`); kde ho model nemá, jde všechno dál textem.

- **Jen Gemini Nano.** Gemma přes LiteRT-LM 0.11.0 typovaný výstup nemá
  (`supportsStructuredOutput` = `false`) a AICore ho hlásí zvlášť
  (`getAvailableFeatures()` s `Feature.STRUCTURED_OUTPUT`, do beta4 `isStructuredOutputFeatureAvailable`) — ani každé Nano ho mít nemusí. **Textová cesta proto
  zůstává plnohodnotná**, ne jako náhradní řešení na odchodu.
- **Popis tvaru je `GenerableProvider` a ML Kit ho hledá přes `ServiceLoader`** — ověřeno
  v bajtkódu `genai-prompt` 1.0.0-beta4 (`ServiceLoader.load(GenerableProvider…)` → mapa
  `targetClass` → provider). Anotace a rozhraní jsou ve zvláštním artefaktu `genai-schema`
  (1.0.0-alpha1); KSP procesor `genai-schema-compiler` by je generoval z `@Generable`/`@Guide`.
- **Providery jsou psané RUČNĚ, KSP v projektu není** (`StructuredOutputProviders.kt`
  v `androidMain`). Typy odpovědí leží v `commonMain`, protože je čte ViewModel, a anotace ML Kitu
  tam být nemůžou. Provider je pár řádků podle vzoru, který procesor generuje (šablona je čitelná
  přímo v jeho jaru); seznam na `List::class` s `isList = true` a `listItemType`.
- **Nový typ = tři místa**: provider, řádek v `src/androidMain/resources/META-INF/services/
  com.google.mlkit.genai.schema.guided.GenerableProvider` a keep pravidlo na pole. Bez řádku ve
  services ML Kit typ nezná a generování selže; soubor se do APK dostane (ověřeno `unzip -l`).
- **Objekt staví Gson (přibalený v ML Kitu) reflexí podle JMEN polí**, bez konstruktoru. Pole,
  které model vynechá, je proto `null` i v nenullable typu — `ReplySuggestionsOutput.replies` je
  vědomě nullable. **Pole drží keep pravidlo s konstruktorem**
  (`proguard-rules.pro`): s pouhým `-keepclassmembers … { <fields>; }` R8 v plném režimu pole
  ZAHODIL (ověřeno v dexu release buildu a v `usage.txt`) — instanci staví jen Gson, takže třídu
  bral jako nikdy nevytvořenou a getter z ní udělal abstraktní. **Provider pravidlo nepotřebuje:**
  R8 sloučil rozhraní `GenerableProvider` s jedinou implementací a soubor ve `services`
  přejmenoval souhlasně (`META-INF/services/pkb` → `pkb`), takže ho `ServiceLoader` najde.
- **Gson v aplikaci musí zůstat pod 2.14, dokud ML Kit nevyjde opravený.** Release 0.3.122
  padal při prvním strukturovaném dotazu (`ExceptionInInitializerError` ← `ClassCastException`
  v `com.google.android.gms.internal.mlkit_genai_prompt.zzvk.zzc`). Gson přibalený v `genai-prompt`
  1.0.0-beta4 je přejmenovaný, ale v metodě, která přidává adaptéry `java.time`, zůstal řetězec
  `Class.forName("com.google.gson.internal.bind.JavaTimeTypeAdapters")` s PŮVODNÍM jménem.
  Chytá jen `ReflectiveOperationException` a `LinkageError` — počítá s tím, že třída není.
  Jenže LiteRT-LM 0.19.1 přitáhl skutečný Gson 2.14.0, který ji má: najde se cizí třída
  a přetypování na kopii z ML Kitu vyhodí `ClassCastException`. Zjištěno `javap -c` nad
  `classes.jar` z `genai-prompt` v keši Gradlu. Gson je proto připnutý `strictly` na 2.13.2
  (tu měl LiteRT-LM 0.11.0; LiteRT-LM bere jen stromové API) a hlídá to `GsonForMlKitTest`
  v `androidHostTest` modulu :client:ai-litert — s 2.14.0 padá. Připnutí i test jsou tam, kde
  je LiteRT-LM: Gson přichází se součástí z Google Play (viz „Runtime jako součást z Google
  Play"), základ aplikace žádný nemá. Kdo bumpuje ML Kit, ověří tím, že v `zzvk`/nástupci
  řetězec zmizel nebo je přejmenovaný, a připnutí smaže.
- **`generateStructured` chytá i `LinkageError`.** Rozbitá inicializace třídy v ML Kitu je
  `Error`, `catch (Exception)` ji minul a padla celá aplikace. Teď je to `StructuredOutputException`
  (volající jde textem, ten tuhle část ML Kitu nepotřebuje), `logger.e` pro Crashlytics
  a strukturovaný výstup se do konce procesu přestane nabízet — třída zůstane rozbitá
  a každý další pokus by skončil `NoClassDefFoundError`.
- **Špatný tvar není rozbitý model.** ML Kit ho hlásí kódy `STRUCTURED_OUTPUT_REQUEST_ERROR`,
  `…_RESPONSE_ERROR` a `…_MAX_TOKENS_ERROR`; ty se převádějí na `StructuredOutputException`
  a návrhy pak zkusí text. Do Crashlytics jako problém nejdou — tam patří jen `OnDeviceAiException`
  rozbitého modelu, a ten se textem nezkouší podruhé (selhal by stejně). Na text se jde i tehdy,
  když po úklidu (`cleanReplySuggestions`: délka, prázdné, zdvojené) nezbyde nic.
- **Prompt o formátu mlčí** (`replyPrompt(structured = true)`): odrážky a závěrečné „Replies:"
  by se praly se schématem, které ML Kit do promptu přidá sám (`includeSchemaInPrompt`).
  Obsahové pokyny (celá věta, jazyk konverzace, tykání) zůstávají — schéma říká, jak odpověď
  vypadá, ne co v ní má být.
- **Klasifikace a výtahy jdou přes `generateAnswer`** (`StructuredAnswers.kt`): podvod
  (`ScamAnswer`), tón (`ToneAnswer`), termín připomínky (`ReminderAnswer`), termín do kalendáře
  (`EventAnswer`) a hledání přirozeným jazykem (`SearchAnswer`). **Typ zrcadlí řádky textového
  promptu a typovaná odpověď jde přes TENTÝŽ parser jako text** (`scamVerdictOf` →
  `parseScamVerdict`, `eventDetailsOf` → společné jádro `eventDetailsFrom` …). Ověření obsahu
  (místo musí být ve zprávě, termín v budoucnu, verdikt jen jednoznačný) tak zůstává na jednom
  místě a strukturovaný výstup jistí jen tvar. Výčty (PHISHING/SAFE/UNSURE, PAST/FUTURE…) dostane
  model jako `enumValues` ve schématu.
- **„Nevím" není důvod zkoušet text.** U klasifikace je `null` platná odpověď; na text se jde
  jen při `StructuredOutputException`. Druhý pokus by stál dvojnásob a přebil by opatrnost, kvůli
  které klasifikace raději mlčí. (U návrhů odpovědí je to jinak: tam prázdno po úklidu text zkusí,
  protože prázdný seznam nic neznamená.)
- **Ověřeno na Foldu všech pět** (15. 10. 2026, featura 648 „Fast Mode (Structured Output)",
  bez jediného pádu na text): podvodná zpráva `ScamAnswer(verdict=PHISHING)` → karta „Možný
  podvod"; hrubý koncept `ToneAnswer(verdict=RUDE)` → „Může vyznít příkře."; „dáš si zítra kafe?"
  `EventAnswer(when=FUTURE, event=YES, title=kafe zítra odpoledne, place=NONE)`; „odkazy z kanalu
  za posledni tyden" `SearchAnswer(text=odkazy, chats=CHANNELS, content=LINKS, from=…, to=…)` →
  čipy Kanály a Odkazy (filtrové slovo v `text` odstraní `refineNaturalSearch`, jako u textu).
  **Obsah zůstává strop modelu**: „den před Silvestrem" vyšel `ReminderAnswer(moment=2026-12-24
  08:00)` místo 30. 12. — tvar je správně, datum ne; uživatel ho v sheetu vidí a může zvolit jiné.
  Svátky s pevným datem teď poznají pravidla (`holidayDate`, `konverzace-akce.md`, „Připomenutí
  zprávy"), takže k modelu se tenhle případ už nedostane.
- **Rozhraní `GenerableProvider` se s víc implementacemi nesloučí**, takže v release buildu je
  soubor `META-INF/services/<rozhraní>` se všemi šesti providery (ověřeno `unzip -p` nad APK),
  a pole všech typů drží jedno keep pravidlo se seznamem tříd (ověřeno `dexdump`).
- **Ověřit jde jen na telefonu s Gemini Nano** (emulátor AICore nemá a Gemma typy neumí). Testy
  (`ReplySuggestionsViewModelTest`) hlídají volbu cesty a náhradu textem s podstrčeným modelem.
- **Ověřeno na Pixelu 11 Pro Foldu** (Android 17, AICore `thirdpartyexperimental`, 15. 10. 2026):
  AICore strukturovaný výstup hlásí a návrhy chodí z něj, bez pádu na text, za ~2 s.
- **Strukturovaný výstup počítá JINÁ featura AICore než text.** V logu AICore
  (`InferenceTask: run`) je typovaný dotaz `648 - SAPI Open Prompt Stable Fast Mode (Structured
  Output)`, textový `636 - SAPI Open Prompt (Regenerative Mode)` — obojí při tomtéž `ModelConfig`
  (STABLE, FULL). Strukturovaný výstup tedy jede na RYCHLÉ variantě a volba „Rychlejší varianta"
  ho nijak neovlivní.
- **Kvalita přesto nevyšla horší, spíš naopak.** Na stejných dvou zprávách (dočasný build se
  strukturovaným výstupem vypnutým, necommitnutý): u „dáš si zítra kafe?" typy „Zítra odpoledne mi
  vyhovuje." / „Kde bys chtěl si sednout?", text „Jsem rád, pak se dojde." / „Žádně, zítra mi
  nebudí."; u faktury typy tři návrhy (jeden zkomolený „Jasně, poslať ti dam."), text jen dva. Vzorek
  je malý a strop modelu (sloh) platí dál — ale tvar je jistý a návrhy jsou vždy tři.

