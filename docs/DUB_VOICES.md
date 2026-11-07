# Dub voices: how the Hindi voices work and how to add more

## What is there now (Hindi, neural / offline)

| Actor | Gender | Engine | Source |
|---|---|---|---|
| Myira | woman | Pocket TTS (voice cloning) | `app/src/main/assets/dub/voices/myira.wav` |
| Kyra | woman | Pocket TTS (voice cloning) | `app/src/main/assets/dub/voices/kyra.wav` |
| Rohan | man | Piper | downloaded once |
| Pratham | man | Piper | downloaded once |

The old Piper woman (Priyamvada) is gone. Her files are deleted from the phone on the next start
(`SherpaModels.cleanupRemoved`).

English still uses the two Kokoro voices, nothing changed there.

## How the cloning works

1. Subtitle line -> translated to Hindi (as before).
2. `SherpaTtsVoice` picks the actor of that character. A woman gets Myira / Kyra, a man gets Rohan / Pratham.
3. For Myira / Kyra the helper process (`NeuralTtsService`) runs sherpa-onnx **Pocket TTS** with the
   actor's recording as the voice prompt (`GenerationConfig.referenceAudio`). The model speaks the
   translated line in the voice of that recording.
4. `DubFx.process(..., faithful = true)`: the clip is NOT pitch-shifted, NOT re-timed and gets no
   "acting" effects, so the voice stays the voice of the sample. It only trims silence, evens the loudness
   and, when a line is longer than its slot, speeds it up WITHOUT changing the pitch.

The cloning model (about 170 MB, int8) is downloaded on the first neural Hindi dub into
`files/dub_models/pocket-multi/`. The files and download addresses are in `SherpaModels`
(`CLONE_FILES`, `CLONE_BASES`). If that download fails, Hindi neural dubbing still works with the men's voices
and the app says so.

## If the voices do not download

The dub panel shows the real error in a box (host, file, reason) and has a **Copy** button. Typical reasons:

| Message | Meaning |
|---|---|
| `UnknownHostException: huggingface.co` / `github.com` | the phone's DNS or the network blocks that host: try another Wi-Fi / mobile data, or pick a DNS in Settings -> Network and Internet -> DNS mode |
| `answered 404` | the file is not at that address: fix `SherpaModels.CLONE_BASES` |
| `sent a web page instead of the file` | a captive portal / block page answered |
| `the file was too small` | the same, or a cut file: it is deleted and the next address is tried |
| `Network problem ... after 4 tries` | the connection keeps dropping: tap Try again, it continues from what is saved |

Everything downloaded so far is kept, so Try again never starts from zero.

## Add another cloned voice

1. Record / cut 6 to 12 seconds of ONE person talking, clean, no music. Convert to WAV, 24 kHz, mono, 16-bit:

   ```
   ffmpeg -i sample.mp3 -ac 1 -ar 24000 -c:a pcm_s16le name.wav
   ```
2. Put it in `app/src/main/assets/dub/voices/name.wav`.
3. Add one line to `SherpaModels.CLONE_VOICES`:

   ```kotlin
   PiperVoice("clone-name", Gender.FEMALE, "Name", clone = true, ref = "dub/voices/name.wav"),
   ```

## Add another language

The same Pocket TTS multilingual model also speaks Marathi (`mr`), Tamil (`ta`), Telugu (`te`),
Kannada (`kn`) and Malayalam (`ml`). To switch one on, add its code to `SherpaModels.CLONE_LANGS`
(for example `setOf("hi", "mr")`). Men and women both come from the cloned voices in a language that has no
Piper voices (a man's voice is the cloned voice moved lower). Listen to a few lines before shipping a new language.

## If the model download address ever dies

`SherpaModels.CLONE_BASES` is a list of folders; each file is tried in every folder, in order. Upload the 7 files
(`lm_main.int8.onnx lm_flow.int8.onnx encoder.onnx decoder.int8.onnx text_conditioner.onnx vocab.json token_scores.json`)
to any HTTPS folder you control (a GitHub release works) and add that folder, ending with `/`, to the list.

## Build

`.github/workflows/build.yml` fetches sherpa-onnx **v1.13.8** (native libraries + `Tts.kt`). Pocket TTS needs
1.12.24 or newer. A guard step fails the build early with a clear message if the fetched `Tts.kt` lacks a name
the dub code uses.
