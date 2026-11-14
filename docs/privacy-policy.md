---
title: Privacy policy
permalink: /privacy-policy/
---

# PureIPTV privacy policy

**App:** PureIPTV for Android TV, Google TV and Android phones (`app.pureiptv`)
**Developer:** ExMachinaOpus
**Contact:** [info@exmachinaopus.eu](mailto:info@exmachinaopus.eu)
**Last updated:** 12 November 2026

## The two things to know

**1. We collect nothing.** PureIPTV has no accounts, no adverts, no analytics and no tracking. The
developer runs no servers that the app talks to, and receives no data from the app: not your
sources, not your logins, not what you watch. Everything stays on your device.

**2. You choose the sources, and you are responsible for them.** PureIPTV is a player only. It
includes no channels, playlists or subscriptions, and it connects only to the links and logins you
add. Which sources you use, whether you are entitled to use them, who runs them, and whether they
are encrypted are your choices and your responsibility. Most IPTV sources use unencrypted `http://`
links that carry your login in the address; the app uses a link exactly as you give it and cannot
make an unencrypted source secure.

The rest of this page gives the details behind those two points.

## What the app stores

Everything the app stores is kept in its private storage on your device.

| What | How it is kept | Does it leave the device? |
|---|---|---|
| Your sources: playlist links, guide links, server addresses, usernames and passwords | Encrypted, with a key held by the Android Keystore on this device | Only to the servers in those links (see "What the app sends"). Never included in backups. |
| The list of your sources (the server name, for example `example.com`, and whether it is switched on) | Unencrypted, in the app's private storage | Only through Android backup (see "Android backup") |
| Favourites, watch progress, watch history and your settings (interface language, adult content shown or hidden, picture zoom, chosen audio and subtitle languages) | Unencrypted, in the app's private storage | Only through Android backup |
| Channel lists, film and series lists and guide data downloaded from your sources | Unencrypted, in the app's private storage | No |
| Channel logos and posters | In the app's cache | No |

The backed-up items identify what you watched (for example a channel name), but they contain no
links, usernames or passwords.

## What the app sends, and to whom

The app connects only to addresses that come from the sources you add:

- the playlist or provider server, to sign in and to download channel lists and the TV guide;
- the stream, logo and poster addresses listed in that playlist or by that provider.

Those servers receive what any server receives when you connect to it: your IP address, the login
you gave the app (it is part of the request), which channel, film or image you ask for, and a
browser-style identification line containing the name "PureIPTV". They are operated by your
provider or by whoever the provider's playlist points to, not by the developer of PureIPTV. What
they do with this is governed by their own terms; the developer has no control over it and no
access to it.

The app sends nothing to the developer or to any analytics, advertising or crash-reporting
service, and contains no third-party software that does.

**Connections are often not encrypted.** Most providers only offer plain `http://` addresses, and
the login is part of the address. When a source uses `http://`, your login and what you watch can
be read by others on the network path between your device and the provider (for example on a
shared Wi-Fi network, or by the internet provider). If your provider offers an `https://` address,
use that one.

## "Set up from your phone"

While that screen is open on the TV, the TV listens on your home network for the form you fill in
on your phone. What you type (a playlist link, or a server, username and password) travels from
the phone to the TV over your own network without encryption, and nowhere else. The TV stops
listening when you leave the screen.

## Android backup

If backup is switched on in your device's Android settings, Android may copy the app's backed-up
items (see the table above) to your Google account, or to a new device when you transfer your
data. That copy is made and stored by Google under Google's terms, not by the developer, who
cannot see it. You can switch it off, or delete the backup, in your device's or Google account's
backup settings.

Your links, usernames and passwords are never part of a backup. After restoring onto a new device
you add your sources again.

## What other parts of the device can see

While something plays, the app tells Android its title and picture so the system can show playback
controls (for example on the Google TV home screen or a phone's lock screen). This stays on the
device.

Google Play may collect crash and performance reports from devices where the owner has agreed to
share diagnostics with Google. That is done by Android and Google Play, not by the app.

## Keeping and deleting your data

- Removing a source in the app deletes its login and everything downloaded from it.
- Favourites, watch progress and watch history stay until you remove them in the app (history keeps
  only the most recent entries) or uninstall the app.
- Uninstalling the app, or clearing its data in Android settings, deletes everything on the device.
- A backup in your Google account, if there is one, is removed through your Google backup settings.

Because the developer holds no data about you, there is nothing the developer could hand over,
correct or delete on request. Everything is in your hands, on your device.

## Permissions

- **Internet, network state**: to reach your sources.
- **Foreground service (data sync)**: to finish importing a large source after you leave the app.
  A notification shows while that runs.
- **Run at startup, keep the device awake**: so the daily refresh of your sources is scheduled
  again after a restart and can finish once started.

The app does not ask for, or use, the microphone, camera, location, contacts, files, phone state or
any advertising identifier.

## Children

The app has no content of its own and is not directed at children. It collects no data from
anyone, including children.

## Changes

If the app changes in a way that affects this policy, the policy is updated first, at this
address, with a new date above.

## Contact

Questions about this policy: [info@exmachinaopus.eu](mailto:info@exmachinaopus.eu)
