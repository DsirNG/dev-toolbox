# i18n Translation Helper for WebStorm

This WebStorm plugin shows an inline translation after literal calls such as `$t('team.labels.archived')` and `t('common.actions.cancel')` in `.vue` files. Choose the inline display language in **Settings → Tools → i18n Translation Helper**; missing entries fall back to `zh-CN`. Hover anywhere over a static translation call for 150 ms to open its locale chooser 4×6 pixels below the latest pointer position. The chooser stays open while the pointer is over the call or the chooser itself, then closes immediately after leaving both regions. Pressing Ctrl closes the hover chooser and suppresses it while Ctrl is held so it cannot cover navigation targets. Hold Ctrl over a navigable key to turn the pointer into a hand and color the exact key under the mouse. Ctrl+Click hit-testing uses the physical mouse position, independent of the text caret. It opens the only matching locale definition directly or shows a one-line-per-locale chooser when several locales exist; Ctrl+Click on the `t`/`$t` function name keeps WebStorm's normal navigation. Ctrl+Click a locale JSON property key to open the native **Show Usages** popup for matching `.vue` calls, including its progress state, result count, file grouping, and direct navigation for a single result. It reads `src/app/*/locales/*.json` and `src/i18n/locales/*.json` from the opened project's root.

## Build

Requires JDK 17+ and the WebStorm installation used by the build classpath. In PowerShell:

```powershell
./webstorm-i18n-inline/build.ps1
```

The resulting archive is `webstorm-i18n-inline/build/i18n-translation-helper.zip`.

## Install

In WebStorm, open **Settings → Plugins → ⚙ → Install Plugin from Disk…**, select the ZIP, then restart WebStorm. Use **Tools → Hide inline translations / Show inline translations** to toggle hints.

## Current scope

- `.vue` files and static `$t('key')` / `t('key')` calls
- Hover chooser for available locale translations
- Ctrl+Click navigation from a static key to the only locale definition or a compact one-line-per-locale chooser
- Native Show Usages popup from a locale JSON property key back to matching `.vue` calls
- Inline hint language is selectable in project settings, with `zh-CN` fallback for missing entries
- Reads nested JSON catalogs from this repository's current layout
- Dynamic keys, plural calls, and JS/TS files outside Vue SFCs are not implemented yet

This is an early prototype. Its UI integration should be checked in the target WebStorm build before relying on it for daily editing.
