# Podgląd Kadru

Aplikacja na Androida do robienia zdjęć **tylnym aparatem** telefonu zamontowanego na gimbalu,
z **podglądem kadru na żywo na drugim telefonie**. Rozwiązuje problem zdjęć typu „selfie z rodziną”
głównym obiektywem, gdy ekran telefonu jest odwrócony od fotografującego.

Jedna aplikacja, dwie role:

- **KAMERA** – telefon na gimbalu. Otwiera tylny aparat (CameraX), transmituje podgląd na żywo,
  a po naciśnięciu **pilota gimbala** (klawisz głośności) lub komendy z podglądu robi zdjęcie
  w pełnej rozdzielczości i zapisuje je do galerii (MediaStore).
- **PODGLĄD** – telefon w ręce. Pokazuje kadr na żywo, ma duży przycisk **MIGAWKA**
  i ustawienia (rozdzielczość / FPS / jakość).

Łącze działa **bez routera**: telefon-kamera stawia lokalny hotspot, a telefon-podgląd dołącza
po zeskanowaniu kodu QR. Alternatywnie oba telefony mogą być w tej samej sieci Wi-Fi.

---

## Wymagania

- **Android Studio** (zalecane) lub Gradle + JDK 17 + Android SDK (compileSdk 35).
- Do działania: **dwa telefony z Androidem** (min. Android 8.0 / API 26).
- Pilot gimbala sparowany po Bluetooth z telefonem-kamerą (większość pilotów działa jak
  klawiatura HID i wysyła klawisz głośności – to wyzwala migawkę).

## Budowanie

### Android Studio
1. *File → Open* → wskaż katalog projektu.
2. Poczekaj na synchronizację Gradle, podłącz telefon (debugowanie USB) i *Run*.

### Wiersz poleceń (Windows, PowerShell)
```powershell
$env:JAVA_HOME = "C:\Users\<użytkownik>\dev\jdk-17.0.19+10"
.\gradlew.bat :app:assembleDebug          # zbuduj APK (app\build\outputs\apk\debug\)
.\gradlew.bat :app:installDebug           # zainstaluj na podłączonym telefonie
```
Ścieżka do Android SDK jest w `local.properties` (`sdk.dir`) – dostosuj na innej maszynie.

Zainstaluj na obu telefonach to samo APK i na każdym wybierz inną rolę.

## Budowanie w chmurze (GitHub Actions)

Po wrzuceniu projektu na GitHub APK buduje się automatycznie — workflow
[.github/workflows/android.yml](.github/workflows/android.yml):

- **Każdy push / PR** → build `assembleDebug`; gotowy APK pobierzesz jako *artifact* w zakładce
  **Actions** → wybrany przebieg → sekcja *Artifacts* (`app-debug-apk`).
- **Tag `v*`** (np. `git tag v0.1 && git push origin v0.1`) → APK trafia dodatkowo do **Releases**
  jako bezpośredni plik `.apk` do pobrania.

To APK *debug* (podpisany kluczem debug) — instaluje się po włączeniu „instaluj z nieznanych źródeł”.
SDK na runnerze GitHuba jest preinstalowany; `local.properties` nie jest potrzebny (Gradle czyta `ANDROID_HOME`).

---

## Jak używać

### Telefon na gimbalu → rola **KAMERA**
1. Nadaj uprawnienie do aparatu.
2. Wybierz sposób połączenia:
   - **Hotspot + QR (bez routera)** – pokaże się kod QR (i SSID/hasło). Wymaga uprawnienia
     Wi-Fi/lokalizacji.
   - **Ta sama sieć Wi-Fi** – na ekranie wyświetla się adres IP telefonu; wpisz go w podglądzie.
3. Po połączeniu naciskaj **pilota gimbala** (głośność +/–), żeby robić zdjęcia. Aplikacja
   przechwytuje ten klawisz i wyzwala migawkę (z krótkim odstępem, bez zmiany głośności).
4. Ekran kamery ma też **własny spust** oraz sterowanie (zoom/pinch, jasność EV, lampa, timer,
   siatka) — przydatne do ustawienia kadru przed rozłożeniem wysięgnika albo do użycia jak
   zwykły aparat.

### Telefon w ręce → rola **PODGLĄD**
1. **Zeskanuj kod QR** z telefonu-kamery (dołączy do hotspotu i połączy się automatycznie),
   **albo** wpisz IP / użyj **„Szukaj w sieci (NSD)”** w tej samej sieci Wi-Fi.
2. Widzisz kadr na żywo. Duży przycisk **MIGAWKA** zdalnie wyzwala zdjęcie na telefonie-kamerze.
3. **Sterowanie aparatem z ręki** (pod podglądem): **zoom** (szczypanie palcami lub suwak),
   **jasność/EV**, **lampa/latarka**, **samowyzwalacz** (Off / 3 s / 10 s) i **siatka kadrowania**.
   Te kontrolki zdalnie sterują telefonem-kamerą; przy samowyzwalaczu na obu ekranach leci odliczanie.
4. **Ustawienia** (góra ekranu): rozdzielczość (480p/720p), płynność (FPS), jakość JPEG oraz
   **„Zapisuj zdjęcia też na tym telefonie"** (domyślnie włączone). Gdy włączone, kamera po
   zrobieniu zdjęcia wysyła pełny plik JPEG, który **zapisuje się w galerii telefonu-podglądu**
   (Pictures/PhotoPreview) z potwierdzeniem „Zapisano ✓". Wyłącz, aby oszczędzać pasmo/baterię.

---

## Jak to działa (architektura)

```
TELEFON-KAMERA (serwer)                         TELEFON-PODGLAD (klient)
CameraX: Preview + ImageAnalysis + Capture      odbiera ramki, dekoduje (BitmapFactory)
ImageAnalysis -> YUV_420_888 -> JPEG  --TCP-->  rysuje na Canvas (z obrotem)
pilot BT (volume key) -> ImageCapture           przycisk MIGAWKA -> komenda SHUTTER
zapis -> MediaStore                   <--cmd--  ustawienia -> komenda CONFIG
```

Protokół: ramki `[1 bajt typ][4 bajty długość][payload]` po TCP (`net/Protocol.kt`).
Transport jest schowany za prostym socketem, więc to samo działa po hotspocie, wspólnym Wi-Fi
lub (w przyszłości) Wi-Fi Direct.

Mapa kodu (`app/src/main/java/pl/photopreview/`):

| Obszar | Pliki |
|---|---|
| Wejście / nawigacja | `MainActivity.kt` (przechwyt klawiszy głośności), `ui/AppRoot.kt` |
| Ekrany | `ui/RoleSelectScreen.kt`, `ui/CameraScreen.kt`, `ui/ViewerScreen.kt` |
| Kamera | `camera/CameraController.kt`, `camera/YuvToJpeg.kt` |
| Sieć | `net/Protocol.kt`, `net/Connection.kt`, `net/CameraSessionManager.kt`, `net/ViewerSessionManager.kt`, `net/NsdHelper.kt`, `net/JoinPayload.kt`, `net/NetUtils.kt` |
| Wi-Fi bez routera | `wifi/HotspotManager.kt`, `wifi/WifiJoiner.kt` |
| Pozostałe | `service/StreamingService.kt`, `vm/CameraViewModel.kt`, `vm/ViewerViewModel.kt`, `input/ShutterKeyBus.kt`, `ui/QrCode.kt` |

---

## Lista testów na urządzeniu (potrzebne 2 telefony)

- [ ] **Wspólne Wi-Fi:** kamera pokazuje IP; w podglądzie wpisz IP → obraz na żywo. Machnij ręką
      przed aparatem – opóźnienie powinno być rzędu ~0,1 s. Sprawdź licznik FPS.
- [ ] **Spust z pilota:** po połączeniu naciśnij pilota gimbala → zdjęcie ląduje w galerii
      (Pictures/PhotoPreview), a w podglądzie pojawia się miniatura „Zrobiono”.
- [ ] **Bez routera:** w kamerze „Hotspot + QR”, w podglądzie „Zeskanuj kod QR” → łączy się
      automatycznie i leci podgląd. Sprawdź, czy pilot BT nadal wyzwala zdjęcie podczas streamu
      (koegzystencja Bluetooth + Wi-Fi w paśmie 2.4 GHz).
- [ ] **Zdalny spust:** przycisk MIGAWKA w podglądzie → zdjęcie na kamerze.
- [ ] **Ustawienia:** zmiana rozdzielczości/FPS/jakości wpływa na podgląd.
- [ ] **Reconnect:** wyjdź poza zasięg i wróć → podgląd łączy się ponownie.

## Znane ograniczenia / do dostrojenia na urządzeniu

- **Dołączanie do hotspotu przez QR** (`WifiNetworkSpecifier`) działa od Androida 10 i bywa różne
  u producentów – system pokazuje okno potwierdzenia. Na Androidzie 8–9 połącz się z siecią ręcznie,
  a potem użyj „Szukaj w sieci (NSD)”.
- **Foreground service** używa typu `connectedDevice`; restrykcyjne nakładki OEM mogą wymagać korekt.
- **Podgląd to JPEG na klatkę** (proste, działa wszędzie). Jeśli zależy Ci na większej płynności
  / mniejszym paśmie, kolejnym krokiem jest sprzętowy **H.264 (MediaCodec)** przy tym samym protokole.
- Telefon-kamera podczas sesji ma włączony ekran (jest zamontowany na gimbalu).
