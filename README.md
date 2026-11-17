# KRANKMILI & O.K BUTTON BOX

Euro Truck Simulator 2 için tablet/telefon button box. Android uygulaması (Kotlin + Jetpack Compose) Wi-Fi üzerinden PC'deki küçük bir Windows sunucusuna bağlanır; sunucu oyuna klavye tuşu (SendInput, scancode) gönderir.

## Parçalar
- `app/` — Android uygulaması (minSdk 26).
- `server-exe/` — C# Windows tray sunucusu (`KrankmiliServer.cs`, tek dosya, Windows'un `csc.exe` derleyicisiyle derlenir) ve varsayılan `config.json`.
- Protokol: TCP 5555 (JSON satırları), UDP 48765 (keşif).

## Özellikler
- Sayfa 1: cam, kapı, kontak, ışıklar, sinyal, el freni, kameralar, araç durumu (telemetri eklentisi gerekir).
- Sayfa 2: ön/arka süspansiyon, dorse dingili, kabin ışığı, çakar, silecek, "Servise git" makrosu (F7 → 1) ve oyun onayı (Enter/Esc).
- Sayfa 3: numpad (Num 0–9, / * - + . , Num Enter, Num Lock). Telefonda tek ekran.

## İndir (v1.5)
- [KrankmiliButtonBox.apk](dist/KrankmiliButtonBox.apk) — tablet/telefon uygulaması
- [KrankmiliServer.exe](dist/KrankmiliServer.exe) — PC sunucusu
- [SHA256.txt](dist/SHA256.txt) — dosya doğrulama

Dosyayı indirmek için sayfada "Download raw file" düğmesini kullan. Tarayıcı exe için uyarı verirse dosyanın SHA256 değerini `SHA256.txt` ile karşılaştır.

## Derleme
1. Android Studio ile aç, `Build → Assemble Project`. Gradle, sunucu exe'sini de derler (`server-exe/build.log` içinde `exitcode 0` görünmeli).
2. APK: `app/build/outputs/apk/debug/app-debug.apk`. Sunucu: `server-exe/KrankmiliServer.exe`.

## Kullanım
1. PC'de `KrankmiliServer.exe` çalıştır (tray ikonu). Oyun yönetici olarak çalışıyorsa sunucuyu da yönetici olarak başlat (tray: "Yönetici olarak yeniden başlat").
2. Tabletteki uygulamayı aç; aynı ağdaki sunucu otomatik bulunur.
3. Oyunda Ayarlar → Klavye ve fare'den eylemlere tuş ata. Ayrıntı: `TUS-ATAMA.txt`.
4. Tuşları `config.json` ile özelleştir; tray'den "Ayarları varsayılana sıfırla" yedek alıp varsayılanı yazar.

Test edildi: ETS2, Windows 11. Oyun sürümü güncellemelerinde tuş atamaları değişebilir.

## Lisans
MIT. Kodu kullanabilir, değiştirebilir ve dağıtabilirsin; telif satırını (`LICENSE`) korumak zorundasın. Kod dışındaki adlar ve görseller ("Krankmili TV", anma yazısı) lisansa dahil değildir.


## Reverse camera (experimental, 1.6)
ETS2's Coaches DLC draws the reverse-camera image on the bus dashboard screen. Run the game in windowed/borderless mode, choose the screen area from the tray menu ("Geri kamera bölgesini seç…"), then enable auto mode on page 2 ("Oto aç"). When the truck speed goes negative the tablet shows the captured image full screen; tap to close. Limitations: needs the fixed cab camera, exclusive fullscreen may capture black, no automatic bus detection.
