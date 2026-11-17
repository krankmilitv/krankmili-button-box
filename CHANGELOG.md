# Changelog

## 1.8.0
- New: voice control. Mic button in the header; while on, the tablet listens (Android SpeechRecognizer, tr-TR) and presses the matching buttons. No wake word: a phrase must be (nearly) the whole utterance, with a small edit-distance tolerance for recognizer spelling. Phrases come from the new optional `voice` list on each button in config.json (protocol: additive `voice` array in the layout). Voice commands skip the confirm prompt; the prompt stays for touch.
- Voice answers the in-game confirmation itself ("servise git" also sends Enter); windows are held 5 s so they travel fully; the recognizer beep is hidden by muting the media/notification/system streams while listening.
- New buttons: park camera (page 1, key `\`), sun visor lower/raise (page 2, keys `;` and `'`), all with voice phrases; many more phrase variants per feature.
- Status panel: the cargo box alternates every 3 s between cargo (name, weight) and destination city with remaining km. Fixed the status panel disappearing when hidden buttons were filtered.
- Requires the RECORD_AUDIO permission. Several buttons may share one phrase (e.g. "camları aç" opens both windows).

## 1.7.0
- New: update notice. App and server read `version.json` from this repo at start and tell the user when a newer release exists (notification only, nothing is downloaded). Can be switched off in both (tablet connect screen, server tray menu). Only the GitHub request is made.
- To announce a release: publish the Release, then bump `version`/`notes` in `version.json`.

## 1.6.0
- New: swipe between pages with the page following the finger (swipe right = next page, left = previous). Tap keys now fire when the finger lifts, so a swipe that starts on a button does not press it; hold keys (windows, selektör) still act on touch.
- New: eye button hides/shows the PC address on the tablet header and in the server tray menu.
- The experimental reverse camera was removed again before release.

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
