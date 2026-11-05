# FA-DO — karaoke dla Spotify i Android Auto

Tekst piosenki z wypełnianiem liter i odbijającą się piłką, zsynchronizowany ze Spotify,
na ekranie telefonu i w dużym kafelku Android Auto (Coolwalk).

## Jak zbudować APK

### A) Android Studio (najprościej)
1. Zainstaluj [Android Studio](https://developer.android.com/studio) (zawiera JDK i Android SDK).
2. `File → Open…` → wskaż folder `FA-DO` → poczekaj na „Gradle Sync” (pierwszy raz pobiera kilka minut).
   Jeśli Studio zaproponuje aktualizację wtyczki Gradle — możesz odmówić, projekt działa na obecnych wersjach.
3. `Build → Build App Bundle(s) / APK(s) → Build APK(s)`.
4. Plik: `app/build/outputs/apk/debug/app-debug.apk`.

### B) Wiersz poleceń
Wymaga JDK 17+ i Android SDK (zmienna `ANDROID_HOME` albo plik `local.properties` z `sdk.dir=…`).
```
./gradlew assembleDebug        # Windows: gradlew.bat assembleDebug
```

### C) Bez instalowania czegokolwiek — GitHub Actions
Wrzuć folder do (prywatnego) repozytorium na GitHub. Workflow `.github/workflows/build-apk.yml`
zbuduje APK automatycznie: zakładka **Actions → Build APK →** ostatni przebieg **→ Artifacts → FA-DO-apk**.

> `app-release.apk` jest podpisany kluczem debug (do instalacji z pliku). Do Sklepu Play podmień `signingConfig` na własny keystore.

## Pierwsze uruchomienie (telefon)
1. Zainstaluj APK (zezwól przeglądarce/menedżerowi plików na instalację nieznanych aplikacji).
2. Otwórz FA-DO → **Nadaj dostęp do powiadomień** → włącz FA-DO.
   - **Android 13+:** jeśli przełącznik jest wyszarzony („ustawienie ograniczone”): przycisk
     **Informacje o aplikacji** → menu ⋮ → **Zezwól na ustawienia ograniczone**, potem wróć i włącz dostęp.
3. Puść muzykę w Spotify — tekst pojawi się w podglądzie. Przycisk **Uruchom demo** testuje piłkę bez Spotify i bez internetu.
4. Opcjonalnie w Spotify: Ustawienia → Urządzenia → **Device Broadcast Status** (drugie, niezależne źródło stanu odtwarzania).

## Android Auto
Najpierw (raz): ustawienia Android Auto na telefonie → stuknij 10× w **Wersja** → menu ⋮ →
**Ustawienia programisty** → zaznacz **Nieznane źródła**. Bez tego Android Auto ukrywa aplikacje spoza Sklepu Play.

FA-DO ma w aucie dwa wejścia:

### „FA-DO Tekst” — tekst obok Map Google (nawigacja działa)
1. Uruchom nawigację w Mapach Google — zostaje w dużym kafelku.
2. Menu aplikacji Android Auto → **FA-DO Tekst** (aplikacja muzyczna) → **Tekst bieżącego utworu** → wróć do Map.
3. Kafelek multimediów obok mapy pokazuje: **tytuł = fragment śpiewany teraz**, **podtytuł = następny fragment**,
   okładkę ze Spotify. Długie linie są dzielone na krótsze fragmenty, w przerwach pojawia się „♪ ♪ ♪” i zapowiedź.
4. Play / pauza / następny / poprzedni w kafelku sterują Spotify (dźwięk dalej idzie ze Spotify).

Jak to działa: FA-DO wystawia własną sesję multimediów, która odzwierciedla Spotify i przekazuje mu przyciski.
Ograniczenia, których nie da się obejść publicznym API Android Auto:
- kafelek pokazuje **tylko tekst** (bez animacji i piłki) — Android Auto nie pozwala innym aplikacjom rysować obok Map,
- jeśli Android Auto sam przełączy kafelek z powrotem na Spotify (np. po wznowieniu muzyki z telefonu), wybierz ponownie **FA-DO Tekst**;
  to zachowanie hosta trzeba sprawdzić w Twoim aucie.

### „FA-DO Karaoke” — pełne karaoke z piłką
Rysuje animację w dużym kafelku, **zastępując** w nim Mapy Google (jedna aplikacja nawigacyjna naraz).
Pasek akcji: **−** / **+** (przesunięcie tekstu o 100 ms), **Słowa/Sylaby** (tryb piłki), **▶** (demo).

### Co dalej: „Car Widgets”
Google zapowiedział (I/O, maj 2026) widżety telefonu wyświetlane w Android Auto obok Map. Według doniesień z 4 listopada 2026
funkcja nie jest jeszcze wydana (beta spodziewana pod koniec 2026, szerokie wdrożenie w I kw. 2027) i nie wiadomo,
czy od startu obejmie widżety innych aplikacji. Gdy będzie dostępna, widżet FA-DO pozwoli pokazać obok mapy
więcej niż tytuł kafelka multimediów.

Test bez auta: [Desktop Head Unit (DHU)](https://developer.android.com/training/cars/testing/dhu) z Android SDK.

### Dlaczego nic nie blokuje animacji w aucie
Zwykłe aplikacje multimedialne w Android Auto dostają tylko gotowe szablony (listy, kafelki) i nie mogą rysować.
FA-DO deklaruje kategorię `NAVIGATION` + uprawnienie `ACCESS_SURFACE`, więc host przekazuje jej własną
powierzchnię wideo (`SurfaceCallback`). Rysuje na niej ten sam silnik co w telefonie, na GPU, w tempie vsync.
Układ tekstu opiera się na „stable area”, więc chowanie/pokazywanie paska przycisków nie przesuwa linii.

## Synchronizacja i przesunięcie
- Źródło 1: `MediaSessionManager` (pozycja + czas aktualizacji + prędkość) — blokuje się na Spotify, a gdy Spotify nie gra, na innym aktywnym odtwarzaczu.
- Źródło 2: broadcasty Spotify (działają nawet bez dostępu do powiadomień).
- Między aktualizacjami pozycja jest interpolowana co klatkę od znacznika vsync, a drobne korekty od Spotify są wygładzane (bez skoków piłki).
- **Przesunięcie**: tekst się spieszy → „−”, spóźnia się → „+”. Przez Bluetooth/Android Auto zwykle ok. −150…−300 ms.

## Struktura projektu
```
app/src/main/java/com/fado/karaoke/
├── FadoApp.kt                    start aplikacji
├── core/   FadoCore, FadoState, FadoSettings      stan wspólny telefonu i auta
├── sync/   SpotifySyncService (NotificationListener), SpotifySyncEngine (sesje + broadcasty),
│           PlaybackClock (interpolacja + wygładzanie), NotificationAccess
├── lyrics/ LrclibClient, LyricsRepository (dopasowanie + cache plikowy), LyricsCache,
│           EnhancedLrcParser (Enhanced LRC + estymator liter/sylab), Syllables, TitleCleaner, DemoSong
├── render/ KaraokeSurfaceRenderer (wątek + Choreographer), KaraokeScene (rysowanie),
│           LyricsLayout (pomiar liter, zawijanie), RenderTheme
├── car/    FadoCarAppService, FadoCarSession, FadoCarScreen, FadoSurfaceCallback   (pełne karaoke)
├── media/  FadoMediaBrowserService, FadoMediaRelay, LyricChunker                     (tekst obok Map)
└── ui/     MainActivity
```

## Zmiany względem specyfikacji
- Brak Retrofit/OkHttp/Room: HttpURLConnection + org.json + cache w plikach JSON. Jedyna zależność zewnętrzna to `androidx.car.app`. Mniej rzeczy, które mogą się rozjechać przy budowaniu.
- Współrzędne liter nie są trzymane w modelach danych: telefon i auto rysują jednocześnie w różnych rozmiarach, więc każdy renderer ma własny układ.
- Piłka ląduje na początku każdego słowa/sylaby i czeka na długich nutach (zamiast przesuwać się przez całe słowo); w Enhanced LRC sylaby pochodzą ze znaczników z pliku.
- Płynne przewijanie linii, zbalansowane zawijanie, odliczanie 3-2-1 po przerwach, wcześniejsze pokazanie linii po solówce.
- Dwa motywy: kolory logo FA-DO (domyślny) i „klasyczny” z kolorami ze specyfikacji (cyjan + czerwona piłka).
- Wydajność: GPU canvas, 10 FPS gdy nic się nie rusza, limit 60 FPS dla auta.

## Ograniczenia
- Teksty pochodzą z LRCLIB (darmowa, społecznościowa baza). Większość utworów ma synchronizację linii — litery są wtedy estymowane; synchronizację słów ma mniejszość. Gdy jest tylko tekst bez czasów, linie są rozkładane w przybliżeniu (oznaczenie „≈”).
- Musixmatch RichSync wymaga komercyjnego klucza API — nie jest zaimplementowany.
- Sklep Play nie zaakceptuje aplikacji kategorii NAVIGATION, która nie nawiguje — to projekt do instalacji z pliku.
- Cel: Android Auto (projekcja z telefonu). Auta z wbudowanym Android Automotive OS wymagałyby osobnego modułu.
