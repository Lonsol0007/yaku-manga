# UI overhaul

Yaku Manga currently looks like Mihon, because it *is* Mihon underneath. The goal is an app that
is visibly its own product while still crediting its origin — the fork stays Apache-2.0 and the
README still credits upstream. What has to change is the surface, not the attribution.

The design reference is `yaku-manga-ios-ui.zip`: an iOS-flavoured Compose mockup.

## What the reference is, and is not

It is a **532-line standalone mockup**. One `MainActivity.kt`, a hardcoded list of nine invented
titles (*Crimson Requiem*, *Void City*, …), no database, no sources, no extensions, no reader, no
translation. It is a picture of an app, not an app.

So it is a **specification to implement against**, never something to merge. Three specifics that
must not be copied across:

| Reference | Reality | Why |
|---|---|---|
| `applicationId com.yaku.manga` | `app.yaku` | Changing it makes 1.0/1.1 users install a *second* app rather than upgrade, and orphans their library. |
| Tabs: Home / Library / Updates / Downloads / Settings | Library / Updates / History / Browse / Translate / More | The reference has no **Browse**, which is where sources and extensions live — the thing that makes the app able to read anything at all. Adopting its navigation literally would delete the core feature. It also predates the Translate tab. |
| Fake `library` list | SQLDelight, sources, downloads | Obvious, but worth stating: the mockup's screens have nothing behind them. |

## What is being taken from it

- **Palette.** Light: near-white ground, white surfaces, `#D90429` accent. Dark: near-black
  ground, maroon surfaces, `#E21B3C` accent. This alone is most of the visual distance from
  Mihon's blue.
- **Floating translucent taskbar.** A rounded, bordered, elevated bar inset from the edges,
  instead of a standard Material navigation bar. The single most recognisable element.
- **Shape language.** 16–25dp corner radii on surfaces, 1dp hairline borders, generous padding.
- **Typography scale.** Large semibold titles over small muted subtitles; Poppins already ships.
- **Home screen with Continue Reading**, and an explicit sort control with its state written out
  ("Sorted by: Newest to Oldest") rather than implied by an icon.

## Order of work

Foundation first, because every screen inherits it:

1. **Palette and shapes** — new colour scheme as the default, new shape scale.
2. **Floating taskbar** — replaces the Material navigation bar.
3. **Screen surfaces** — cards, list rows, headers restyled to the shape language.
4. **Home screen** — new, with Continue Reading; the reference's most distinctive screen.
5. **Strings and artefacts** — the leftovers that are not on screen but are still user-visible:
   `mihon_crash_logs.txt`, `mihon_restore_error.txt`, `mihon_update_errors.txt`, the `mihon://`
   deep-link scheme, and `mihon.app` help URLs inside error messages.

`mihon://bangumi-auth` is deliberately last and may not move at all: it is an OAuth redirect
registered with Bangumi, so renaming it breaks Bangumi login until the redirect URI is changed on
their side.

## Cost to state plainly

Yaku Manga currently tracks Mihon closely enough that pulling upstream fixes is realistic. Every
screen rewritten here is a screen where future upstream changes become a manual merge. That is
probably the right trade for the goal, but it is a real and permanent cost, not a free win.
