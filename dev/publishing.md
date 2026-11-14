# Publishing to Google Play

What has to be right outside the code. The code side is covered in `dev/architecture.md`.

## Before every upload

- Build the bundle without `-PdebuggableRelease` (the build refuses it for bundle tasks).
- `./gradlew test assembleRelease` passes (the release build runs the fatal lint checks).
- A dependency changed? Regenerate `gradle/verification-metadata.xml` (see `dev/dev-setup.md`).

## Play Console declarations

**Public pages.** Everything in `docs/` is public: GitHub Pages builds it from `master` on every
push (one-time setup: repository Settings › Pages › Source: Deploy from a branch, `master`,
`/docs`; Pages needs the repository to be public). Developer documentation goes in `dev/`, never
in `docs/`. The Play Console fields:

| Play Console field | Address |
|---|---|
| Privacy policy | https://exmachinaopus.github.io/PureIPTV/privacy-policy/ |
| Website | https://exmachinaopus.github.io/PureIPTV/ |
| Support / contact email | info@exmachinaopus.eu |

The privacy policy address is also in the app (`privacy_policy_url` in
`ui/src/main/res/values/strings.xml`). It must be publicly reachable before the first upload; if
it moves, change the string and the Play listing together. When the app changes what it stores or
sends, update `docs/privacy-policy.md` (and its date) first.

**Account deletion.** Not applicable: the app has no accounts and the developer holds no data.

**Data safety form.**
- Data collected or shared by the developer: none. Nothing reaches a server the developer runs.
- Data encrypted in transit: **no**. Sources are mostly plain `http://` and the user chooses them.
- Users can request deletion: data exists only on the device; removing a source or uninstalling deletes it.

**Foreground service.** One type is declared: `dataSync` (`ImportKeepAliveWorker`). Justification:
"Importing the user's playlist (often 50,000+ entries) continues after the user leaves the app; it
is started by the user adding or refreshing a source, shows a notification, and ends when the
import is done." There is no `mediaPlayback` service; don't add one without an `onConnect` check.

**Content rating.** The app has no content. Sources can contain adult content; it is hidden unless
the user switches it on. Answer the questionnaire for a player of user-provided content.

## Store listing

IPTV players are removed for their listing far more often than for their code.

- State plainly, near the top: PureIPTV is a player only and includes no channels, playlists or subscriptions.
- Never name, link or hint at a provider, and never promise specific channels, films or sports.
- Screenshots and the TV banner show no real channel names, logos, posters or programme titles.
  Use `scripts/fake-provider.py` content.
- No "free TV" style wording, in any language.

The listing lives in the repo in fastlane's layout, which F-Droid reads as well:
`fastlane/metadata/android/en-GB/` holds `title.txt`, `short_description.txt`, `full_description.txt`
and `images/` (`icon.png`, `featureGraphic.png`, `tvBanner.png`, `phoneScreenshots/`, `tvScreenshots/`).
`python3 scripts/store-graphics.py` redraws the icon, feature graphic and banner from the app mark.
Screenshots come from the release build with the fake provider: TV at 1920×1080 on the Google TV
emulator, phone at 1080×1920 (`adb shell wm size 1080x1920`; Play rejects taller than 2:1) with the
status bar in demo mode.
