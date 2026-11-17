# Changelog

## 1.6.0
- New: reverse camera. The server captures the screen region where ETS2 draws the bus reverse-camera display and streams it as JPEG over TCP 5556; the tablet shows it full screen when reversing (auto, from signed truck speed) or via the "Geri kamera" keys on page 2. Region is picked from the tray ("Geri kamera bölgesini seç…"), settings in rear.json.
- Protocol (additive): server -> client `{"t":"rear","on":bool,"port":5556}`; client keys `rear_auto_on`, `rear_auto_off`, `rear_toggle`, `rear_hide`.
- Experimental: bus vs truck is not detected, capture of exclusive fullscreen may be black.

## 1.5.2
- Sayfa 2: "Dorse dingili" yerine "Dorse süspansiyonu" (Num 8 / Num 2); dorse dingili tuşları sayfa 1'de.

## 1.5.1
- Sayfa 1: çekici ve dorse dingili indir/kaldır tuşlarının hepsi bir arada.
- "SELEKTÖR" gibi tek kelimelik etiketler artık ortadan bölünmez, sığacak şekilde küçülür.

## 1.5
- Çoklu sayfa desteği (sayfa seçici başlıkta), vJoy/gamepad modu kaldırıldı (klavye modu).
- Sayfa 2: süspansiyon, dorse dingili, kabin ışığı, çakar, silecek, servis makrosu, oyun onayı.
- Sayfa 3: numpad (numlock dahil); telefonda sekmesiz tek ekran.
- Sığmayan alt yazı/ikon gizlenir, yarım kesilmez.
- Açılışta anma uyarısı, yeni başlık.
