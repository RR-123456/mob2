# PragonMobile v6

## Fixes
- **YouTube (and other media) stuttering / pausing while Pragon listens.** Google's speech recognizer takes audio focus every time
  it restarts, so a video that is playing keeps getting paused and resumed. New *Media-friendly listening* (Settings > FLOAT & TYPER, ON by default):
  while music/video is playing, float mode keeps the microphone **closed** (the bubble dims). Tap the bubble once to say one command, or just
  pause the video and it listens again by itself. Typer mode is exempt (dictation needs the mic). Turn the setting off to listen all the time.
- **".N" dropped to a second line** in the P.R.A.G.O.N title: the title is now one line and shrinks to fit; chips are slightly tighter.
- **Macro DELETE did nothing.** The settings page used JavaScript `confirm()`, which an Android WebView without a WebChromeClient always answers "no".
  Added the missing client (a real OK/Cancel dialog). Also fixes the other two DELETE / UNINSTALL buttons.

## New
- **Play on YouTube really plays.** "play despacito" / "play despacito on youtube" / "watch lofi on youtube" search, wait for the results, and tap the first video.
  ("search X on youtube" still only shows the results.)
- **Click by position.** "click first video", "play the second video", "open the third result", "tap video 2", "open the last video", "click the first one".
  Videos are found by their accessibility description ("... play video") with a card-shape fallback for other apps; "result/item/link/post" use a generic list.
- **Read and explain anything on screen**, including photos: "what is this picture", "describe this image", "what does this say", "what is this number",
  "explain this paragraph", "what's on my screen". Pragon sends the visible text **plus a screenshot** (Android 11+) to your chosen engine
  (Gemini, Ollama vision model such as gemma3 / llava / qwen2.5vl, or an OpenAI-compatible vision model).
  Needs *Let Pragon read my screen* (Settings > Security). Not sent while a password box is visible. Gemini is refused while Privacy Shield is ON.
  It does not name real people from their faces.
- **Typer auto text-box detection.** "typer mode" now finds the box itself: the one with the cursor, else a search / message / comment box,
  else a chat composer at the bottom, else the top one; never a password box. Works again later in the session if you change screens.
  Setting: FLOAT & TYPER > *Typer mode finds the text box by itself* (ON by default).

## Notes
- The accessibility service config gained `canTakeScreenshot`. After installing, if "describe" says it couldn't get a screenshot, switch the Pragon accessibility service OFF and ON once.
- Compile-checked here against android.jar with okhttp stubbed (all changed Kotlin files compile; androidx/R unavailable in that check). ParserTest and ParserTest2 pass.
  Not run on a phone: please try  play despacito / click second video / what is this picture / typer mode in a search box / video playing + float bubble.

## AUTO engine + phone-speed tuning (v6b)
- **New engine: AUTO** (chip at the top cycles Gemini > AUTO > Ollama > Custom; Settings > AI engine).
  Gemini and your Ollama model back each other up. Chat: Gemini first, phone model if it fails or there is no internet
  (tick *Try the phone / Ollama model first* to flip the order). Screen/photo questions: Gemini when allowed, else the local vision model.
  Privacy Shield ON, no key or no internet = local model only. Plain phone commands never use either AI.
- **Faster small models:** Ollama is now told `think: false` (thinking models such as Qwen3.5 otherwise "think" before the first word; retried
  without it if a server rejects it); *Short prompt* (about a fifth of the words, default ON when Ollama is on 127.0.0.1); model kept loaded 10 min
  on the phone instead of forever (saves RAM).
- **Custom model keeps its personality:** *My model has its own personality* sends only date, mode, your name and memory.
- Model chips: qwen3.5:0.8b / 2b / 4b (all see pictures, use tools). A text-only model asked about a picture now answers from the screen text instead of failing.
- `termux/setup_pragon_ollama.sh`: installs Ollama in Termux, starts it, pulls a model and builds the `pragon` Modelfile model in one go.

## PragonMob v6c (renamed from PragonMobile; package id unchanged so permissions and adb grants still apply)
- **Offline listening (Vosk)** - Settings > SMART TOOLS. Download a language pack once (English US/India, Hindi), tick *Use offline listening*.
  The float bubble then records through AudioRecord: no audio focus, no beep, no internet, so YouTube is never paused or stuttered.
  While a video plays only "Pragon ..." commands are accepted (the speaker leaks into the mic). If the native library or model fails to load,
  float mode falls back to Google's recognizer by itself. Adds ~25-40 MB to the APK (arm64 + armv7 only).
- **Screen agent** - "agent, open Instagram and message Ravi hello" / "do this: ...". Looks at the screen (accessibility tree; screenshot only for Gemini),
  asks your AI engine for one JSON action at a time (tap / type / scroll / back / home / open app / done / ask), up to 14 steps.
  Asks **yes/no** before anything matching send, post, pay, buy, order, confirm, delete, submit, transfer, subscribe, sign out, install, share, call...
  Never types into password boxes, stops when one is visible, refuses payment/banking apps. "stop" or a bubble tap cancels. Off until you enable it.
- **Notification reader** - "read my notifications", "read WhatsApp messages" (spoken on the phone, no AI), "summarize my notifications" (AI, only if allowed;
  4-8 digit codes hidden). In memory only. Needs Android's Notification access for PragonMob plus the two switches in SMART TOOLS.
- **Fuzzy matching** - app names ("instagran", "spotifi", "whats up", "gogle maps") and on-screen click targets tolerate mishearings.
- **Saved answers** - repeats of general questions are answered instantly from a small on-phone cache (not for personal/time-sensitive/follow-up questions). Clear it in Settings.
- **Semantic memory** - with >10 remembered facts, only identity + the 8 closest facts go to the AI (Ollama `nomic-embed-text`); falls back to all facts.
- Build: vosk-android 0.3.47 + jna 5.13.0 aar; versionName 6.0. NOT built or run on a phone here; Kotlin compiled against android.jar with the Vosk/okhttp APIs stubbed.

## Mock run / bug sweep (v6d)
Bugs found and fixed before release:
- "summarize this article / my day / the news" was captured by the notification summariser; now only known apps (or "... notifications/messages") are.
- "agent mode on" and "take control of the volume" were read as agent tasks; the agent trigger is now only "agent ..." / "autopilot ..." / "do this ...".
- Loading the offline speech model blocked the main thread (1-3 s freeze); it now loads on a background thread with a cancel flag.
- Semantic memory could hang a reply while Ollama was unreachable; the embedding call now times out after 4 s and falls back to all facts.
- Turning notifications ON after Android had already connected the listener showed nothing until new ones arrived; it now re-reads active ones.
- After you said "yes" to "press Send?", the agent used the old screen layout; it now re-finds the button on the current screen first.
- One-time-code hiding matched "pin" inside other words and any number near them; it now matches whole words (OTP, code, PIN, password, CVV...).
- "why is the sky blue" was treated as a follow-up and never cached.
Checks run: parser regression diff old vs new on ~130 phrases (only intended changes), 90+ mock checks (AI-reply parsing, confirm-before-send,
cache rules, code hiding, fuzzy matching, a simulated "message Ravi hello" agent run), settings-page <-> Kotlin bridge cross-check, manifest/XML validity.
Not covered (needs a phone): real accessibility taps, Vosk native library loading, Google/Vosk recognition quality, notification listener binding.

## v6e - volume and listening while a video plays
- **Voice did nothing while a video played:** with *Media-friendly listening* ON (default) and Google's recognizer, the mic is deliberately closed during playback.
  With *Offline listening* the old rule only accepted "Pragon ..." commands. Now, during playback, offline listening also accepts whole-sentence short controls
  without the wake word: volume up/down/louder/quieter/lower, mute, unmute, pause, resume, play, next, previous, skip, stop (and "pause the video"). Anything else still needs "Pragon".
- **Volume not changing:** the volume actions never checked the result. They now apply the change, verify it, retry the way the physical keys do
  (adjustSuggestedStreamVolume), choose the music stream whenever something is playing, and say plainly if Android refuses (headphone safe-volume limit, locked volume).
