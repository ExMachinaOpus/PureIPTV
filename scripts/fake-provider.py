#!/usr/bin/env python3
"""
Fake IPTV provider for development and store screenshots: an Xtream Codes panel plus a plain M3U with
an XMLTV guide. Every name, title, plot, picture and video is invented or generated here, so the
content is ours and safe to show anywhere (Play screenshots included). Nothing is downloaded.

    python3 scripts/fake-provider.py [port]        # default 8765

Needs ffmpeg and Pillow (python3 -m pip install pillow). On first start it generates its videos and
pictures into build/fake-media/ (about a minute); later starts reuse them.

From an Android emulator the host is 10.0.2.2:
    M3U:    http://10.0.2.2:8765/playlist.m3u   (?noguide=1: without a guide link)
    Xtream: server http://10.0.2.2:8765  user demo  password demo

Test cases on purpose: a backup stream that refuses connections (failover), a channel without a
guide id (guide matched by name), a separator row, an adult category, a 4K and a +1 channel, and a
season-only copy of a series (merged by its TMDB id). Guide times are relative to "now".
"""
import json
import os
import random
import re
import subprocess
import sys
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse
from xml.sax.saxutils import escape

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
USER, PASSWORD = "demo", "demo"
MEDIA = Path(__file__).resolve().parent.parent / "build" / "fake-media"
DEAD = "http://127.0.0.1:9/dead.ts"  # refuses connections: exercises failover

# ---- generated videos ------------------------------------------------------------------------
# (name, ffmpeg video source). All abstract, made by ffmpeg's own generators.
CLIPS = [
    ("aurora", "gradients=s=1280x720:r=25:c0=0x0b3d5c:c1=0x2a9d8f:c2=0x8ab4ff:c3=0x1d1a3a:nb_colors=4:speed=0.012:type=radial:seed=7"),
    ("ember", "gradients=s=1280x720:r=25:c0=0x3a0d12:c1=0xe76f51:c2=0xf4a261:c3=0x2b1b2f:nb_colors=4:speed=0.01:type=spiral:seed=3"),
    ("dusk", "gradients=s=1280x720:r=25:c0=0x1d1a3a:c1=0x6a4c93:c2=0xf28482:c3=0x0e0f13:nb_colors=4:speed=0.008:type=circular:seed=11"),
    ("meadow", "life=s=320x180:r=25:mold=12:ratio=0.12:death_color=#13261b:life_color=#9be564:mold_color=#2a5b3a,scale=1280:720:flags=neighbor"),
]
# Constant quality per clip; the cellular pattern is noisy and needs a coarser setting to stay small.
CRF = {"meadow": "34"}
CLIP_SECONDS = 60
MOVIE_LOOPS = 30  # films and episodes: 30 minutes, made by repeating a clip (fast, no re-encode)
AUDIO = "aevalsrc=0.06*sin(2*PI*220*t)+0.04*sin(2*PI*277.2*t)+0.03*sin(2*PI*329.6*t):s=48000:c=stereo"

LIVE_CATEGORIES = [("1", "UK | Entertainment"), ("2", "UK | Sports"), ("3", "DE | Nachrichten"),
                   ("4", "XXX | Adults"), ("5", "UK | Kids"), ("6", "UK | Documentary")]
# (stream id, name, category id, epg id, clip or DEAD, logo colour)
LIVE = [
    (101, "UK: Harbourline One HD", "1", "harbourlineone.uk", "aurora", "#2a6f97"),
    (102, "UK: Harbourline One FHD (Backup)", "1", "harbourlineone.uk", DEAD, "#2a6f97"),
    (103, "UK: Quillfield News HD", "1", "quillfieldnews.uk", "ember", "#9d0208"),
    (104, "UK: Harbourline One +1", "1", "harbourlineoneplus1.uk", "aurora", "#2a6f97"),
    (105, "UK: Morrow Weather", "1", None, DEAD, "#457b9d"),  # offline on purpose
    (106, "UK: Lanternbox Cinema HD", "1", None, "dusk", "#6a4c93"),  # no tvg-id: guide matched by name
    (201, "UK: Pitchside Sport 4K", "2", "pitchsidesport.uk", "meadow", "#2b9348"),
    (202, "##### SPORTS #####", "2", None, "meadow", "#000000"),
    (203, "UK: Tumbleweed Kids HD", "5", "tumbleweedkids.uk", "ember", "#f4a261"),
    (204, "UK: Fernway Nature HD", "6", "fernwaynature.uk", "meadow", "#40916c"),
    (301, "DE: Rheinblick Eins HD", "3", "rheinblickeins.de", "aurora", "#1d3557"),
    (302, "DE: Zwielicht Nachrichten FHD", "3", "zwielichtnachrichten.de", "ember", "#b5179e"),
    (401, "XXX: Adult Channel", "4", None, "dusk", "#333333"),
]
# Guide channels that no playlist entry names by tvg-id: the app matches them by display name.
GUIDE_ONLY = {"lanternboxcinema.uk": "Lanternbox Cinema", "morrowweather.uk": "Morrow Weather"}
GUIDE_TITLES = {
    "harbourlineone.uk": ["Kitchen Table", "The Puzzle Hour", "Village Lights", "Weekend Workshop", "Late Lounge"],
    "quillfieldnews.uk": ["News Hour", "The Briefing", "Market Watch", "World Report", "Weather Outlook"],
    "morrowweather.uk": ["Forecast", "Weekend Outlook", "Coast and Sea", "Forecast", "Skywatch"],
    "lanternboxcinema.uk": ["Film: The Quiet Orbit", "Film: Paper Lanterns", "Film: Saltwater Summer", "Film: Ember & Ash"],
    "pitchsidesport.uk": ["Matchday Live", "Courtside", "The Long Pass", "Finish Line", "Sports Desk"],
    "tumbleweedkids.uk": ["Pebble and Moss", "Rocket Garden", "Story Boat", "Paint Along", "The Busy Burrow"],
    "fernwaynature.uk": ["Tide Pools", "Mountain Light", "Night Forest", "Rivers of Glass", "Wild Gardens"],
    "rheinblickeins.de": ["Abendschau Rhein", "Wetterblick", "Kochstudio", "Reisezeit", "Nachtcafé am Fluss"],
    "zwielichtnachrichten.de": ["Nachrichten am Morgen", "Mittagsjournal", "Wirtschaft kompakt", "Tagesblick", "Wetter"],
}

VOD_CATEGORIES = [("10", "Movies | Drama"), ("11", "Movies | Sci-Fi"), ("12", "Movies | Family")]
# (id, name, category, tmdb id, clip, rating, plot, palette). TMDB ids are far beyond real ones.
VOD = [
    (1001, "The Quiet Orbit (2021)", "11", "99000101", "aurora", 7.4,
     "A lone engineer keeps a fading space station running and starts hearing a signal nobody else can.", ("#0b3d5c", "#8ab4ff")),
    (1002, "Paper Lanterns (2019)", "10", "99000102", "ember", 6.9,
     "Three sisters reopen their grandmother's lantern shop for one last summer festival.", ("#7f1d1d", "#f4a261")),
    (1003, "Saltwater Summer (2022)", "12", "99000103", "meadow", 7.1,
     "Two cousins spend a summer on a tiny island and build a raft out of everything the sea brings in.", ("#03465f", "#90e0ef")),
    (1004, "EN - Clockwork Orchard (2020)", "11", "99000104", "dusk", 6.6,
     "In a town where the trees are machines, a young gardener learns to repair the last one that bears fruit.", ("#283618", "#dda15e")),
    (1005, "Northbound Static (2023)", "10", "99000105", "aurora", 7.8,
     "A late-night radio host takes a call from someone claiming to be driving through tomorrow.", ("#14213d", "#fca311")),
    (1006, "The Glass Cartographer (2018)", "10", "99000106", "ember", 7.0,
     "A mapmaker hired to chart a frozen coast finds the coastline changing every night.", ("#1b263b", "#e0e1dd")),
    (1007, "Midnight Allotment (2024)", "12", "99000107", "meadow", 6.8,
     "Neighbours compete for the town's vegetable prize while something eats the giant marrows.", ("#2d6a4f", "#d8f3dc")),
    (1008, "Ember & Ash (2017)", "11", "99000108", "dusk", 7.2,
     "Two rival inventors are forced to share a workshop during the longest winter in a century.", ("#370617", "#ff7b00")),
]
SERIES_CATEGORIES = [("20", "Series | Drama"), ("21", "Series | Comedy")]
# (id, name, category, tmdb id, plot, palette). 5002 is a season-only copy of 5001 (same TMDB id),
# as real providers list them.
SERIES = [
    (5001, "The Lighthouse Keepers", "20", "99000201",
     "A family inherits a remote lighthouse and the strange logbook that comes with it.", ("#003049", "#fcbf49")),
    (5002, "The Lighthouse Keepers (S02)", "20", "99000201",
     "A family inherits a remote lighthouse and the strange logbook that comes with it.", ("#003049", "#fcbf49")),
    (5003, "Small Town Signals", "21", "99000202",
     "A community radio station with four listeners becomes the most important thing in town.", ("#6d597a", "#eaac8b")),
]
EPISODES = {
    5001: {
        1: [(90001, 1, "The Inheritance", "aurora"), (90002, 2, "Fog Bell", "ember"), (90003, 3, "Low Tide", "meadow")],
        2: [(90004, 1, "The Second Log", "dusk"), (90005, 2, "Keeper's Night", "aurora")],
    },
    5002: {2: [(90004, 1, "The Second Log", "dusk")]},
    5003: {
        1: [(90101, 1, "On Air", "ember"), (90102, 2, "Dead Air", "meadow"), (90103, 3, "Request Hour", "aurora"),
            (90104, 4, "The Pledge Drive", "dusk")],
    },
}
CAST = ["Mara Ellison", "Tobias Wren", "Ines Halloway", "Kwame Osei", "Lotte Brandt", "Rafael Quint"]
DIRECTORS = ["Hanna Lisle", "Oskar Venn", "Priya Anand", "Jules Moreau"]

STREAMS = {("live", sid): clip for sid, _, _, _, clip, _ in LIVE}
STREAMS.update({("movie", v[0]): v[4] for v in VOD})
for seasons in EPISODES.values():
    for eps in seasons.values():
        for eid, _, _, clip in eps:
            STREAMS[("series", eid)] = clip

# FAKE_SLOW=<seconds>: the guide download takes that long; FAKE_SLOW_SERIES=<seconds>: the Xtream series
# list does (to look at the app mid-refresh).
SLOW = float(os.environ.get("FAKE_SLOW", "0"))
SLOW_SERIES = float(os.environ.get("FAKE_SLOW_SERIES", "0"))


# ---- generating the media ----------------------------------------------------------------------

def ffmpeg(*args: str) -> None:
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *args], check=True)


def generate_videos() -> None:
    for name, source in CLIPS:
        clip = MEDIA / f"{name}.ts"
        if not clip.exists():
            print(f"fake-provider: generating video {name}…", flush=True)
            tmp = MEDIA / f"{name}.tmp.ts"
            ffmpeg("-f", "lavfi", "-i", source, "-f", "lavfi", "-i", AUDIO, "-t", str(CLIP_SECONDS),
                   "-c:v", "libx264", "-preset", "veryfast", "-crf", CRF.get(name, "26"), "-pix_fmt", "yuv420p", "-g", "50",
                   "-c:a", "aac", "-b:a", "96k", "-metadata:s:a:0", "language=eng", "-f", "mpegts", str(tmp))
            tmp.rename(clip)
        film = MEDIA / f"{name}.mp4"
        if not film.exists():
            tmp = MEDIA / f"{name}.tmp.mp4"
            ffmpeg("-stream_loop", str(MOVIE_LOOPS - 1), "-i", str(clip), "-c", "copy", "-movflags", "+faststart", str(tmp))
            tmp.rename(film)


def font(size: int):
    """A bold sans-serif from the system (fontconfig), else Pillow's built-in font."""
    from PIL import ImageFont
    try:
        path = subprocess.run(["fc-match", "-f", "%{file}", "sans:bold"], capture_output=True, text=True).stdout.strip()
    except FileNotFoundError:
        path = ""
    if path:
        fnt = ImageFont.truetype(path, size)
        try:
            fnt.set_variation_by_name("Bold")  # variable fonts
        except Exception:
            pass
        return fnt
    return ImageFont.load_default(size=size)


def art(width: int, height: int, palette, seed: int):
    """An abstract picture: a two-colour gradient with soft circles."""
    from PIL import Image, ImageDraw, ImageFilter
    top = tuple(int(palette[0][i:i + 2], 16) for i in (1, 3, 5))
    bottom = tuple(int(palette[1][i:i + 2], 16) for i in (1, 3, 5))
    img = Image.new("RGB", (width, height))
    draw = ImageDraw.Draw(img)
    for y in range(height):
        t = (y / height) ** 1.4
        draw.line([(0, y), (width, y)], fill=tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    rnd = random.Random(seed)
    glow = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    g = ImageDraw.Draw(glow)
    for _ in range(7):
        r = rnd.randint(width // 8, width // 2)
        x, y = rnd.randint(-r // 2, width), rnd.randint(-r // 2, height)
        g.ellipse([x - r, y - r, x + r, y + r], fill=(255, 255, 255, rnd.randint(14, 40)))
    glow = glow.filter(ImageFilter.GaussianBlur(width // 40))
    return Image.alpha_composite(img.convert("RGBA"), glow)


def wrap(draw, text: str, fnt, width: int):
    lines, line = [], ""
    for w in text.split():
        test = f"{line} {w}".strip()
        if draw.textlength(test, font=fnt) <= width:
            line = test
        else:
            lines.append(line)
            line = w
    return lines + [line]


def poster(path: Path, title: str, palette, seed: int) -> None:
    from PIL import Image, ImageDraw, ImageFilter
    img = art(600, 900, palette, seed)
    draw = ImageDraw.Draw(img)
    fnt = font(64)
    lines = wrap(draw, title.upper(), fnt, 520)
    y = 900 - 70 - len(lines) * 74
    # A dark band behind the title keeps it readable on light pictures.
    shade = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(shade).rectangle([0, y - 60, 600, 900], fill=(0, 0, 0, 110))
    img = Image.alpha_composite(img, shade.filter(ImageFilter.GaussianBlur(40)))
    draw = ImageDraw.Draw(img)
    for line in lines:
        draw.text((40, y), line, font=fnt, fill=(255, 255, 255, 245))
        y += 74
    img.convert("RGB").save(path)


def backdrop(path: Path, palette, seed: int) -> None:
    art(1280, 720, palette, seed).convert("RGB").save(path)


def logo(path: Path, name: str, colour: str) -> None:
    """A channel logo: the channel's name as a wordmark on a coloured badge."""
    from PIL import Image, ImageDraw
    img = Image.new("RGBA", (480, 270), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.rounded_rectangle([10, 45, 470, 225], radius=36, fill=colour)
    words = name.split()
    lines = [" ".join(words[:1]), " ".join(words[1:])] if len(words) > 1 else [name]
    fnt = font(54 if len(lines) == 1 else 46)
    y = 135 - len(lines) * 28
    for line in lines:
        w = draw.textlength(line, font=fnt)
        draw.text(((480 - w) / 2, y), line, font=fnt, fill="white")
        y += 56
    img.save(path)


def display_name(raw: str) -> str:
    """'UK: Harbourline One HD' -> 'Harbourline One' (for logos and the guide)."""
    name = re.sub(r"^[A-Z]{2,3}:\s*", "", raw)
    return re.sub(r"(\s+(HD|FHD|4K|\(Backup\)))+$", "", name).strip()


def generate_pictures() -> None:
    for sid, name, _, _, _, colour in LIVE:
        path = MEDIA / f"logo-{sid}.png"
        if not path.exists() and not name.startswith("#"):
            logo(path, re.sub(r"\s+\+1$", "", display_name(name)), colour)
    for vid, name, _, _, _, _, _, palette in VOD:
        if not (MEDIA / f"backdrop-{vid}.png").exists():
            poster(MEDIA / f"poster-{vid}.png", re.sub(r"^EN - |\s*\(\d{4}\)$", "", name), palette, vid)
            backdrop(MEDIA / f"backdrop-{vid}.png", palette, vid + 1)
    for sid, name, _, _, _, palette in SERIES:
        if not (MEDIA / f"backdrop-s{sid}.png").exists():
            poster(MEDIA / f"cover-{sid}.png", re.sub(r"\s*\(S\d+\)$", "", name), palette, sid)
            backdrop(MEDIA / f"backdrop-s{sid}.png", palette, sid + 1)
    for seasons in EPISODES.values():
        for eps in seasons.values():
            for eid, _, _, _ in eps:
                if not (MEDIA / f"still-{eid}.png").exists():
                    backdrop(MEDIA / f"still-{eid}.png", ("#1b263b", "#8ab4ff"), eid)


def generate_media() -> None:
    MEDIA.mkdir(parents=True, exist_ok=True)
    try:
        generate_videos()
        generate_pictures()
    except FileNotFoundError:
        sys.exit("fake-provider: needs ffmpeg (and Pillow: python3 -m pip install pillow)")
    except ImportError:
        sys.exit("fake-provider: needs Pillow: python3 -m pip install pillow")


EPISODE_PLOTS = {
    90001: "The family arrives on the island and finds the last keeper's logbook still open.",
    90002: "A bell rings in the fog although nobody on the island is near it.",
    90003: "An unusually low tide uncovers a path no map shows.",
    90004: "A second logbook turns up, written in a hand the family recognises.",
    90005: "The storm of the decade arrives on the night the light fails.",
    90101: "The station's first broadcast reaches exactly four people.",
    90102: "The transmitter breaks down in the middle of the town council debate.",
    90103: "A request for a song nobody has heard of starts a town-wide search.",
    90104: "The station needs a new transmitter, and the town needs to pay for it.",
}
# ---- the provider ------------------------------------------------------------------------------

def fmt_time(t: int) -> str:
    return datetime.fromtimestamp(t, timezone.utc).strftime("%Y%m%d%H%M%S +0000")


def xmltv() -> bytes:
    now = int(time.time()) // 1800 * 1800
    ids = sorted({epg for _, _, _, epg, _, _ in LIVE if epg} | set(GUIDE_ONLY))
    names = {epg: display_name(n) for _, n, _, epg, _, _ in LIVE if epg} | GUIDE_ONLY
    out = ['<?xml version="1.0" encoding="UTF-8"?>', '<tv generator-info-name="fake-provider">']
    for i in ids:
        out.append(f'<channel id="{i}"><display-name>{escape(names[i])}</display-name></channel>')
    for n, i in enumerate(ids):
        # A +1 channel shows its main channel's programmes an hour later.
        base = i.replace("plus1.", ".")
        delay = 2 if base != i else 0
        titles = GUIDE_TITLES.get(base, ["Programme"])
        for slot in range(-2, 12):
            start = now + slot * 1800
            title = titles[(slot - delay + ids.index(base)) % len(titles)]
            out.append(
                f'<programme channel="{i}" start="{fmt_time(start)}" stop="{fmt_time(start + 1800)}">'
                f'<title>{escape(title)}</title><desc>{escape(title)}, today on {escape(names[i])}.</desc></programme>'
            )
    out.append("</tv>")
    return "\n".join(out).encode()


def playlist(host: str, guide: bool = True) -> bytes:
    header = f' x-tvg-url="http://{host}/xmltv.php?username={USER}&password={PASSWORD}"' if guide else ""
    lines = [f'#EXTM3U{header}']
    cats = dict(LIVE_CATEGORIES)
    for sid, name, cat, epg, _, _ in LIVE:
        tvg = f' tvg-id="{epg}"' if epg else ""
        icon = f' tvg-logo="http://{host}/img/logo-{sid}.png"' if not name.startswith("#") else ""
        lines.append(f'#EXTINF:-1{tvg}{icon} group-title="{cats[cat]}",{name}')
        lines.append(f"http://{host}/live/{USER}/{PASSWORD}/{sid}.ts")
    vcats = dict(VOD_CATEGORIES)
    for vid, name, cat, *_ in VOD:
        lines.append(f'#EXTINF:-1 tvg-logo="http://{host}/img/poster-{vid}.png" group-title="{vcats[cat]}",{name}')
        lines.append(f"http://{host}/movie/{USER}/{PASSWORD}/{vid}.mp4")
    return ("\n".join(lines) + "\n").encode()


def big_playlist(n: int, host: str) -> bytes:
    """Scale test: n entries in provider style, ~3 copies per channel across qualities/backups."""
    countries = ["UK", "DE", "FR", "ES", "IT", "NL", "SE", "US", "PL", "TR", "AR", "PT"]
    groups = ["Entertainment", "Sports", "News", "Kids", "Movies", "Documentary", "Music"]
    variants = ["HD", "FHD", "SD (Backup)"]
    lines = [f'#EXTM3U x-tvg-url="http://{host}/bigguide.xml?n={n}"']
    live = [sid for sid, _, _, _, clip, _ in LIVE if clip != DEAD]
    for i in range(n):
        ch = i // 3
        c = countries[ch % len(countries)]
        g = groups[(ch // len(countries)) % len(groups)]
        lines.append(f'#EXTINF:-1 tvg-id="ch{ch}.{c.lower()}" group-title="{c} | {g}",{c}: Channel {ch} {variants[i % 3]}')
        lines.append(f"http://{host}/live/{USER}/{PASSWORD}/{live[i % len(live)]}.ts?i={i}")
    return ("\n".join(lines) + "\n").encode()


BIG_TITLES = [
    "News at Ten", "Motor Racing: Harbour Grand Prix", "Weather", "Football Tonight", "The Village Bake",
    "Nature Documentary", "Morning Show", "Late Night Talk", "Cartoon Hour", "Film: The Long Road",
    "Alpine Skiing: Winter Cup", "Cooking with Friends", "Quiz Night", "Classic Music", "Travel Diaries",
]


def big_guide(n: int, out) -> None:
    """Guide for big.m3u: 45-minute programmes from 3 h ago to 36 h ahead on every channel."""
    countries = ["UK", "DE", "FR", "ES", "IT", "NL", "SE", "US", "PL", "TR", "AR", "PT"]
    now = int(time.time()) // 900 * 900
    out.write(b'<?xml version="1.0" encoding="UTF-8"?>\n<tv>\n')
    channels = (n + 2) // 3
    ids = [f"ch{ch}.{countries[ch % len(countries)].lower()}" for ch in range(channels)]
    for i in ids:
        out.write(f'<channel id="{i}"><display-name>{i}</display-name></channel>\n'.encode())
    for ch, i in enumerate(ids):
        buf = []
        for slot in range(-4, 48):
            start = now + slot * 2700
            title = BIG_TITLES[(ch + slot) % len(BIG_TITLES)]
            buf.append(
                f'<programme channel="{i}" start="{fmt_time(start)}" stop="{fmt_time(start + 2700)}">'
                f'<title>{title}</title><desc>Programme {slot} on channel {ch}.</desc></programme>\n'
            )
        out.write("".join(buf).encode())
    out.write(b"</tv>\n")


def api(action, q, host: str):
    img = f"http://{host}/img"
    if action is None:
        return {
            "user_info": {"username": USER, "auth": 1, "status": "Active", "exp_date": str(int(time.time()) + 86400 * 30),
                          "allowed_output_formats": ["m3u8", "ts"], "max_connections": "1"},
            "server_info": {"url": host.split(":")[0], "port": str(PORT), "timezone": "UTC", "timestamp_now": int(time.time())},
        }
    if action == "get_live_categories":
        return [{"category_id": c, "category_name": n, "parent_id": 0} for c, n in LIVE_CATEGORIES]
    if action == "get_vod_categories":
        return [{"category_id": c, "category_name": n, "parent_id": 0} for c, n in VOD_CATEGORIES]
    if action == "get_series_categories":
        return [{"category_id": c, "category_name": n, "parent_id": 0} for c, n in SERIES_CATEGORIES]
    if action == "get_live_streams":
        return [{"num": i + 1, "name": n, "stream_type": "live", "stream_id": sid,
                 "stream_icon": "" if n.startswith("#") else f"{img}/logo-{sid}.png",
                 "epg_channel_id": epg, "added": "1700000000", "is_adult": "1" if c == "4" else "0",
                 "category_id": c, "tv_archive": 0, "tv_archive_duration": 0}
                for i, (sid, n, c, epg, _, _) in enumerate(LIVE)]
    if action == "get_vod_streams":
        return [{"num": i + 1, "name": n, "stream_type": "movie", "stream_id": vid, "stream_icon": f"{img}/poster-{vid}.png",
                 "rating": str(r), "added": str(1700000000 + i * 1000), "category_id": c,
                 "container_extension": "mp4", "tmdb": tmdb}
                for i, (vid, n, c, tmdb, _, r, _, _) in enumerate(VOD)]
    if action == "get_series":
        return [{"num": i + 1, "name": n, "series_id": sid, "cover": f"{img}/cover-{sid}.png", "plot": plot,
                 "genre": "Drama" if c == "20" else "Comedy", "releaseDate": f"{2019 + i}-03-01",
                 "last_modified": str(1700000000 + i), "rating": "7.6", "category_id": c, "tmdb": tmdb,
                 "backdrop_path": [f"{img}/backdrop-s{sid}.png"]}
                for i, (sid, n, c, tmdb, plot, _) in enumerate(SERIES)]
    if action == "get_series_info":
        sid = int(q.get("series_id", "0"))
        seasons = EPISODES.get(sid, {})
        name = next((n for s, n, *_ in SERIES if s == sid), "")
        return {
            "info": {"name": name, "cover": f"{img}/cover-{sid}.png"},
            "episodes": {str(s): [{"id": str(eid), "episode_num": num, "title": f"{name} - S{s:02d}E{num:02d} - {t}",
                                   "container_extension": "mp4", "season": s,
                                   "info": {"plot": EPISODE_PLOTS.get(eid, ""), "duration_secs": 1800,
                                            "movie_image": f"{img}/still-{eid}.png"}}
                                  for eid, num, t, _ in eps] for s, eps in seasons.items()},
        }
    if action == "get_vod_info":
        vid = int(q.get("vod_id", "0"))
        v = next((v for v in VOD if v[0] == vid), None)
        if v is None:
            return {}
        rnd = random.Random(vid)
        return {"info": {"plot": v[6], "genre": dict(VOD_CATEGORIES)[v[2]].split("| ")[1],
                         "cast": ", ".join(rnd.sample(CAST, 3)), "director": rnd.choice(DIRECTORS),
                         "duration_secs": CLIP_SECONDS * MOVIE_LOOPS, "backdrop_path": [f"{img}/backdrop-{vid}.png"],
                         "movie_image": f"{img}/poster-{vid}.png"},
                "movie_data": {"stream_id": vid}}
    return []


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        sys.stderr.write("fake-provider: " + (fmt % args) + "\n")

    def send(self, body: bytes, ctype="application/json", code=200):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_file(self, path: Path, ctype: str):
        """Serves a file with Range support, so the player can seek in films."""
        size = path.stat().st_size
        start, end = 0, size - 1
        m = re.match(r"bytes=(\d*)-(\d*)", self.headers.get("Range", ""))
        if m and (m.group(1) or m.group(2)):
            if m.group(1):
                start = int(m.group(1))
                end = min(int(m.group(2)), size - 1) if m.group(2) else end
            else:
                start = max(0, size - int(m.group(2)))
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        else:
            self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        self.end_headers()
        with path.open("rb") as f:
            f.seek(start)
            left = end - start + 1
            try:
                while left > 0:
                    chunk = f.read(min(1 << 16, left))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    left -= len(chunk)
            except (BrokenPipeError, ConnectionResetError):
                pass

    def do_GET(self):
        url = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(url.query).items()}
        host = self.headers.get("Host", f"10.0.2.2:{PORT}")
        parts = url.path.strip("/").split("/")

        if url.path == "/playlist.m3u":
            # ?noguide=1: a playlist that names no guide (to test adding a guide link by hand).
            return self.send(playlist(host, guide=not q.get("noguide")), "audio/x-mpegurl")
        if url.path == "/bigguide.xml":
            self.send_response(200)
            self.send_header("Content-Type", "application/xml")
            self.send_header("Connection", "close")
            self.end_headers()
            big_guide(int(q.get("n", "50000")), self.wfile)
            self.close_connection = True
            return
        if url.path == "/big.m3u":
            return self.send(big_playlist(int(q.get("n", "50000")), host), "audio/x-mpegurl")
        if url.path == "/xmltv.php":
            if SLOW:
                time.sleep(SLOW)
            return self.send(xmltv(), "application/xml")
        if len(parts) == 2 and parts[0] == "img" and re.fullmatch(r"[a-z0-9-]+\.png", parts[1]):
            path = MEDIA / parts[1]
            return self.send_file(path, "image/png") if path.exists() else self.send(b"not found", "text/plain", 404)
        if len(parts) == 4 and parts[0] in ("live", "movie", "series"):
            clip = STREAMS.get((parts[0], int(parts[3].split(".")[0])))
            if clip is None:
                return self.send(b"not found", "text/plain", 404)
            if clip == DEAD:
                self.send_response(302)
                self.send_header("Location", DEAD)
                self.end_headers()
                return
            if parts[0] == "live":
                return self.send_file(MEDIA / f"{clip}.ts", "video/mp2t")
            return self.send_file(MEDIA / f"{clip}.mp4", "video/mp4")
        if url.path != "/player_api.php":
            return self.send(b"not found", "text/plain", 404)
        if q.get("username") != USER or q.get("password") != PASSWORD:
            return self.send(json.dumps({"user_info": {"auth": 0}}).encode())
        if SLOW_SERIES and q.get("action") == "get_series":
            time.sleep(SLOW_SERIES)
        return self.send(json.dumps(api(q.get("action"), q, host)).encode())


if __name__ == "__main__":
    generate_media()
    print(f"fake-provider on :{PORT}  (emulator: http://10.0.2.2:{PORT}/playlist.m3u, Xtream user/pass {USER}/{PASSWORD})", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
