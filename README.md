# DSL.sk reader

A small Android app that shows [dsl.sk](https://www.dsl.sk/) — *digitálny svet pod lupou* — in a clean,
phone-friendly layout. It's a rewrite of the 2012 app `sk.freelancegroup.dslsk` (v1.1), whose source was lost.
I rebuilt it by decompiling the old APK.

## What it does

- **News list**: the home page plus older pages ("Staršie správy"), which load as you scroll. Newest first,
  grouped by day, with the perex, time and comment count. Articles you've opened are greyed out.
- **Article view**: a reader layout with title, date, tags, a bold perex, paragraphs, pictures with captions,
  and YouTube embeds as tappable thumbnails. **A− / A+** sets the text size (remembered). **Share** and
  **open in browser** are in the top bar.
- **Discussion**: the comments are shown under the article as a collapsible thread, with author, date,
  rating and nested replies. Low-rated comments are dimmed. Posting a comment opens the website, because
  it needs a dsl.sk login.
- **Bookmarks**: tap ☆ in an article (or long-press a headline in the list) to save it. The ☆ in the
  list's top bar switches to "bookmarks only"; Back returns to all news. Bookmarks are stored on the phone,
  with title and summary, so they stay listed after the article drops off dsl.sk's news pages.
- Follows the system **dark mode**. If the parser ever fails on an article, the app shows the original page
  instead of an empty screen.

## How it works

| File | Role |
|---|---|
| `DslParser.kt` | Turns dsl.sk HTML into a news list / article. It tries the site's known CSS classes first (`article_body`, `article_perex`, the same ones the 2012 app used), then falls back to structural heuristics. Pure JVM, so it can be unit-tested. |
| `Http.kt` | Downloads pages and detects the charset (header → `<meta>` → UTF-8 → windows-1250). |
| `FeedRepository.kt` | Keeps the loaded list in memory and handles paging. |
| `Bookmarks.kt` | Starred articles, saved as JSON in the app's private settings. |
| `MainActivity.kt`, `FeedAdapter.kt` | The list screen. |
| `ArticleActivity.kt`, `ArticleHtml.kt` | The article screen: sanitised HTML in a WebView with JavaScript off. |

The only library is [jsoup](https://jsoup.org/). The UI uses plain Android framework classes, with no AndroidX.

## Build

Open the folder in Android Studio, or run:

```bash
./gradlew assembleRelease        # → app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest      # parser tests against the HTML in app/src/test/resources/fixtures
```

Requirements: JDK 17+ and Android SDK 35. minSdk is 26 (Android 8.0).

### Signing (important)

The signing key is **not** in this repository, because the repo is public. To build APKs that install over the
copy already on your phone, put these two files next to `settings.gradle.kts`:

```
keystore/dslsk-release.jks
keystore.properties
```

`keystore.properties`:

```properties
storeFile=keystore/dslsk-release.jks
storePassword=…
keyAlias=dslsk
keyPassword=…
```

Without them, builds are signed with the Android debug key. They still work, but Android will refuse to
install them over a copy signed with the real key; you would have to uninstall first.

## GitHub Actions

- **Build APK** runs on every push: unit tests plus the release APK, which you download from the run's
  *Artifacts*. To have CI sign with your key, add the repository secrets `KEYSTORE_BASE64`
  (`base64 -w0 keystore/dslsk-release.jks`) and `KEYSTORE_PASSWORD`.
- **Site check** runs weekly against the live dsl.sk and fails, which emails you, if the parser stops
  finding articles. Run it manually with *snapshot* ticked to push the pages' tag structure, without
  article text, to the `site-snapshot` branch. That shows what changed when the parser needs updating.
