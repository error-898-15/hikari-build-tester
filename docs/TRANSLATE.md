# Manga page translation (Settings -> Translate/Dub)

Reads a manga page, finds the text, translates it and draws the translation back
into the speech bubbles. Everything runs in `com.hikari.app.translate`.

## Where it hooks in

Nothing in the viewers changed. Every page the reader shows is a file made by
`WebtoonPageCache.fileFor`; that function now moves the downloaded bytes into
place through `PageTranslation.place`, which translates them first when the
feature is on. The webtoon viewer, the pager viewer, the page metadata and the
prewarm loop therefore all see the translated page.

| File | Job |
| --- | --- |
| `translate/MangaTranslator.kt` | OCR (ML Kit) -> bubbles -> translate (ML Kit / Google / Gemini) -> erase + typeset. Ported from Kotatsu, logic unchanged. |
| `translate/TranslateConfig.kt` | The settings as one value; `cacheTag` goes into the page-cache file name. |
| `translate/MangaTranslateSettings.kt` | In-memory copy of the settings (the cache name is computed synchronously). |
| `translate/PageTranslation.kt` | Cache <-> translator glue, including retry of half-translated pages. |
| `translate/TranslateOptions.kt` | The language / engine lists shown in Settings. |
| `data/AppStore.kt` | Six `MT_*` keys + accessors. |
| `ui/screens/SettingsScreen.kt` | The `Translate/Dub` folder and `MangaTranslateCard`. |

## Cache behaviour

* Feature off: `cacheTag` is empty, so every page file name is exactly what it was
  before this feature existed.
* Feature on: the name includes source language, target language and engine, so
  changing any of them shows a freshly translated page instead of a stale one.
* A page that could not be fully translated (e.g. offline with an online engine)
  keeps its original bytes as `<key>.src` and is translated again, from that copy,
  the next time it is opened (once per app run).

## Left alone on purpose

Animated GIF/WebP pages, and pages above 36 million pixels (a very long webtoon
strip would need more memory to decode than a phone can spare), are shown
untranslated.
