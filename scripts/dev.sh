#!/usr/bin/env bash
# Developer helper. Usage:
#   scripts/dev.sh emu tv|phone [--headless]   start the Google TV (API 34, matches the Streamer) or phone emulator
#   scripts/dev.sh stop                        stop all emulators and the Gradle daemon (frees RAM)
#   scripts/dev.sh run [serial]                build, install and launch the debug app
#   scripts/dev.sh release [serial]            build, install and launch the release (production) app
#   scripts/dev.sh keys [serial] KEY...        send remote keys: up down left right ok back home menu playpause
#   scripts/dev.sh shot [serial] [file.png]    screenshot (default: build/screens/<timestamp>.png)
#   scripts/dev.sh connect <ip:port>           connect to a real device over Wi-Fi (e.g. the Google TV Streamer)
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="$SDK/platform-tools/adb"
EMU="$SDK/emulator/emulator"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_ID="app.pureiptv.debug"

serial_args() { if [ -n "${1:-}" ]; then echo "-s $1"; fi; }

cmd="${1:-}"; shift || true
case "$cmd" in
  emu)
    target="${1:-tv}"; shift || true
    case "$target" in
      tv) avd=PureIPTV_GoogleTV_API34 ;;
      phone) avd=PureIPTV_Phone_API37 ;;
      *) echo "emu tv|phone"; exit 1 ;;
    esac
    extra=()
    # Note: the headless emulator build crashes on some Linux hosts; use the windowed one if it does.
    [ "${1:-}" = "--headless" ] && extra=(-no-window -no-audio)
    # Always a cold boot, and no quick-boot snapshot saved on exit: a snapshot taken after the host
    # slept (or while memory was short) got corrupted and made every later start hang. Cold boot costs
    # some seconds per start but is reliable. (--cold is accepted for old habits; it's the default now.)
    extra+=(-no-snapshot)
    # Memory budget: only one emulator at a time (each needs ~2.5 GB incl. overhead).
    for s in $("$ADB" devices | awk '/^emulator-/{print $1}'); do
      echo "Stopping $s (one emulator at a time to save memory)"
      "$ADB" -s "$s" emu kill >/dev/null 2>&1 || true
    done
    # Wait until the old emulator is really gone (its AVD lock blocks a restart of the same AVD).
    for _ in $(seq 1 30); do
      pgrep -f 'qemu-system' >/dev/null 2>&1 || break
      sleep 1
    done
    avail_mb=$(awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo)
    if [ "$avail_mb" -lt 3000 ]; then
      echo "Warning: only ${avail_mb} MB RAM available; the emulator needs ~2.5 GB. Close other apps or run 'scripts/dev.sh stop'."
    fi
    # Sound on (testing playback); --headless turns it off.
    nohup setsid "$EMU" -avd "$avd" -no-boot-anim "${extra[@]}" >"/tmp/$avd.log" 2>&1 &
    echo "Starting $avd (log: /tmp/$avd.log). Waiting for boot..."
    for _ in $(seq 1 120); do
      serial=$("$ADB" devices | awk '/^emulator-.*device$/{print $1; exit}')
      if [ -n "$serial" ] && [ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
        # Emulated TVs go to standby after a few minutes (black screen, keys ignored): keep awake.
        "$ADB" -s "$serial" shell settings put global stay_on_while_plugged_in 7 >/dev/null 2>&1 || true
        "$ADB" -s "$serial" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
        # This machine's time zone and a 24-hour clock (the image boots in GMT). The app itself follows
        # whatever the device is set to.
        host_tz=$(timedatectl show -p Timezone --value 2>/dev/null || cat /etc/timezone 2>/dev/null || true)
        if [ -n "$host_tz" ]; then
          "$ADB" -s "$serial" shell settings put global auto_time_zone 0 >/dev/null 2>&1 || true
          "$ADB" -s "$serial" shell service call alarm 3 s16 "$host_tz" >/dev/null 2>&1 || true
        fi
        "$ADB" -s "$serial" shell settings put system time_12_24 24 >/dev/null 2>&1 || true
        # The Google TV home screen keeps asking for a Google account ("Set up Google TV"). The app
        # doesn't need it: open PureIPTV directly instead (if installed; otherwise use 'run').
        # Right after boot_completed the package manager may not answer yet and the launcher still
        # comes up on top, so retry until the app really is in front (up to ~40 s).
        for i in $(seq 1 20); do
          pkg=""
          for p in app.pureiptv.debug app.pureiptv.bench; do
            if "$ADB" -s "$serial" shell pm path "$p" 2>/dev/null | grep -q '^package:'; then pkg="$p"; break; fi
          done
          if [ -n "$pkg" ]; then
            if "$ADB" -s "$serial" shell dumpsys activity activities 2>/dev/null | grep -q "topResumedActivity=.*$pkg/"; then break; fi
            "$ADB" -s "$serial" shell am start -n "$pkg/app.pureiptv.MainActivity" >/dev/null 2>&1 || true
          elif [ "$i" -ge 5 ]; then
            break # not installed: use 'run'
          fi
          sleep 2
        done
        echo "Booted: $serial"; exit 0
      fi
      if ! pgrep -f "qemu-system.*-avd $avd" >/dev/null; then
        echo "Emulator exited. See /tmp/$avd.log"; exit 1
      fi
      sleep 3
    done
    echo "Timed out waiting for boot. See /tmp/$avd.log"; exit 1
    ;;
  stop)
    # Frees memory: all emulators + the Gradle daemon.
    for s in $("$ADB" devices | awk '/^emulator-/{print $1}'); do "$ADB" -s "$s" emu kill >/dev/null 2>&1 || true; done
    (cd "$ROOT" && ./gradlew --stop -q) || true
    echo "Stopped emulators and Gradle daemon."
    ;;
  run)
    # ANDROID_SERIAL: install only on the given device (Gradle otherwise installs on every one).
    (cd "$ROOT" && ANDROID_SERIAL="${1:-${ANDROID_SERIAL:-}}" ./gradlew --console=plain -q :app:installDebug)
    # shellcheck disable=SC2046
    "$ADB" $(serial_args "${1:-}") shell am start -W -n "$APP_ID/app.pureiptv.MainActivity" >/dev/null
    # The Google TV home screen pulls itself to the front shortly after a package update; bring the
    # app back once it has done that.
    sleep 3
    # shellcheck disable=SC2046
    "$ADB" $(serial_args "${1:-}") shell am start -n "$APP_ID/app.pureiptv.MainActivity" >/dev/null 2>&1 || true
    echo "Launched $APP_ID"
    ;;
  keys)
    serial=""
    if [[ "${1:-}" == emulator-* || "${1:-}" == *:* ]]; then serial="$1"; shift; fi
    for k in "$@"; do
      case "$k" in
        up) code=DPAD_UP ;; down) code=DPAD_DOWN ;; left) code=DPAD_LEFT ;; right) code=DPAD_RIGHT ;;
        ok) code=DPAD_CENTER ;; back) code=BACK ;; home) code=HOME ;; menu) code=MENU ;;
        playpause) code=MEDIA_PLAY_PAUSE ;; *) code="$k" ;;
      esac
      # shellcheck disable=SC2046
      "$ADB" $(serial_args "$serial") shell input keyevent "KEYCODE_$code"
      sleep 0.3
    done
    ;;
  shot)
    serial=""
    if [[ "${1:-}" == emulator-* || "${1:-}" == *:* ]]; then serial="$1"; shift; fi
    out="${1:-$ROOT/build/screens/$(date +%Y%m%d-%H%M%S).png}"
    mkdir -p "$(dirname "$out")"
    # shellcheck disable=SC2046
    "$ADB" $(serial_args "$serial") exec-out screencap -p >"$out"
    echo "$out"
    ;;
  release)
    # Production build (R8, not debuggable, release-signed) on a device: how the app really feels.
    # Installs next to the debug app as "PureIPTV" (own data: add the source once).
    [ -f "$ROOT/keystore.properties" ] || { echo "No keystore.properties: see dev/dev-setup.md, 'Release build'."; exit 1; }
    (cd "$ROOT" && ./gradlew --console=plain -q :app:assembleRelease) || exit 1
    # shellcheck disable=SC2046
    "$ADB" $(serial_args "${1:-}") install -r "$ROOT/app/build/outputs/apk/release/app-release.apk"
    # shellcheck disable=SC2046
    "$ADB" $(serial_args "${1:-}") shell am start -W -n "app.pureiptv/app.pureiptv.MainActivity" >/dev/null
    sleep 3
    # shellcheck disable=SC2046
    "$ADB" $(serial_args "${1:-}") shell am start -n "app.pureiptv/app.pureiptv.MainActivity" >/dev/null 2>&1 || true
    echo "Launched app.pureiptv (release)"
    ;;
  connect)
    "$ADB" connect "$1"
    ;;
  *)
    sed -n '2,10p' "$0"; exit 1 ;;
esac
