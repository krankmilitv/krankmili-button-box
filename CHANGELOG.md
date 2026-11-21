# Changelog

## 1.9.5
- Fix: fuel / damage / arrival warnings were spoken in menus. They are armed by the first movement or by the engine button; until then the current state is only remembered.
- New: pressing the engine button (touch or "motoru çalıştır") says the start briefing: destination, km, cargo, ETA, fuel and range (with the 30 % fuel warning). The tablet guesses the engine state from the button presses (every second press while standing is a start); the game's real engine flag was not verified.
- Cargo wording: "Yükünüz <yük>, 6,5 ton ağırlığında."

## 1.9.4
- Fix: the trip start / "araç çalıştı" information was spoken in menus. It now waits for the first movement of the truck (> 5 km/h); the game exposes no reliable engine-on flag we could verify.

## 1.9.3
- New: lit buttons. Held keys (windows, sun visors, selector) glow amber while they are held; taps pulse briefly; high/low beam and wipers keep following the game state.
- New: hazard blinks red, left/right indicators blink amber. The game exposes no flasher state, so the tablet tracks it from its own presses (indicators clear after 20 s or when the other side is pressed); pressing the keys on the keyboard is not seen.
- New: voice command "sesli bilgi ver" (also "genel durum", "bilgi ver"): says distance left, fuel, cargo and damage.

## 1.9.2
- Fix: the route distance arrives from the server in meters; spoken info said e.g. "500000 kilometre". It is now converted to km once when the status is parsed (on-screen text unchanged).

## 1.9.1
- New: `KrankmiliKontrolKurulum.exe` adds the button box keys to the game's `controls.sii` (ETS2 `profiles` and `steam_profiles`; ATS with `--ats`). Only adds `| keyboard.X?0` alternatives to the matching game actions, backs up each file first, refuses to run while the game is open, `--dry` previews, `--geri-al` restores the newest backup.
- Changed: sun visor keys are now F8 / Shift+F8 / Ctrl+F8 / Alt+F8 (the old `;` `'` collided with the retarder defaults). Park camera uses V (the game default).
- Removed: navigation voice button and "sesli navigasyonu aç/kapat" (the game has no key binding for it; F10 is the screenshot key).
- New: when the truck first moves after connecting, the tablet says cargo, destination, km left and a rough ETA.
- New: fuel warning at 30 %: "benzin bitiyor, en yakın benzin istasyonuna git" (15 % and 7 % warnings stay).
- Better voice: uses Google's speech engine when installed (same voices as Google Maps), picks the highest-quality installed Turkish voice, network voices included.

## 1.9.0
- New: spoken feedback on the tablet (Android TTS, tr-TR). Header chip cycles female / male / off. Output uses the alarm stream because the recognizer mutes the media stream while listening; the tablet ignores the microphone while it speaks.
- New: `speak` on a button in config.json: text said after the press (touch or voice).
- New: navigation voice button (page 2, key F10) plus voice-only "sesli navigasyonu aktif et / kapat"; the tablet confirms "Sesli navigasyon aktif edildi / kapatıldı". The game has to bind F10 to its navigation voice toggle.
- New: spoken answers from live telemetry: "yol durumu", "yakıt durumu", "hasar durumu", "yük bilgisi", "hızım ne". "hava durumu" answers that the game does not provide it (no weather channel in the telemetry).
- New: automatic spoken alerts: low / critical fuel, damage crossing 25/50/75 %, 5 km to the destination.
- New: live button state: buttons with `state` (high beam, wipers, low beam) light up from telemetry (`states` in the status message).
- New: key-set profiles (`profiles` in config.json, chip in the header when two or more exist). Switching sends `{"t":"profile","name":...}`; the server remembers the choice in profile.txt.
- New: automatic trip information without a command: trip start (destination, km, cargo, fuel range check), every 100 km (km left, rough ETA, fuel, damage, flow), a speed-based "traffic is slow" guess and "traffic cleared", collision on a sudden damage jump. "otomatik bilgiyi kapat/aç" toggles it. The game exposes no road works / accident / real traffic data.
- Bus passenger satisfaction is not exposed by the telemetry plugin we read; not included.

## 1.8.0
- New: voice control. Mic button in the header; while on, the tablet listens (Android SpeechRecognizer, tr-TR) and presses the matching buttons. No wake word: a phrase must be (nearly) the whole utterance, with a small edit-distance tolerance for recognizer spelling. Phrases come from the new optional `voice` list on each button in config.json (protocol: additive `voice` array in the layout). Voice commands skip the confirm prompt; the prompt stays for touch.
- Voice answers the in-game confirmation itself ("servise git" also sends Enter); windows are held 5 s so they travel fully; the recognizer beep is hidden by muting the media/notification/system streams while listening.
- New buttons: park camera (page 1, key `\`), sun visor lower/raise (page 2, keys `;` and `'`), all with voice phrases; many more phrase variants per feature.
- Status panel: the cargo box alternates every 3 s between cargo (name, weight) and destination city with remaining km. Fixed the status panel disappearing when hidden buttons were filtered.
- Voice: lights and wipers are state-aware. Hidden voice-only buttons tap the key until the telemetry state matches (`until` in config.json: state, value, max, wait_ms), so "kısaları aç" ends on low beam and "silecekleri kapat" ends with the wipers off across all stages. Without telemetry they tap once.
- Voice keeps listening in the background and with the screen off: the recognizer runs in a microphone-type foreground service (notification with state and a stop button; needs notification permission, MIUI battery set to no restrictions).
- Sun visors: separate left/right buttons (lower `,` `.`, raise `;` `'`), held like windows (5 s by voice). "güneşlikleri indir/kaldır" moves both; ids are `sunvisor_left_down` etc. Replaces the single lower/raise pair.
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
