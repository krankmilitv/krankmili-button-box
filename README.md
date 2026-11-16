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
Henüz lisans seçilmedi (tüm hakları saklıdır).
