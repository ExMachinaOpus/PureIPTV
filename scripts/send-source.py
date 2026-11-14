#!/usr/bin/env python3
"""Sends a source to a TV that shows "Set up from your phone", without typing it.

Usage: scripts/send-source.py <setup address shown on the TV> [file]

The file (default: fixtures-private/source.properties, git-ignored) holds either
    link=<playlist URL>
or
    server=<server URL>
    username=<user>
    password=<password>

Nothing from the file is printed: playlist links and logins contain credentials.
"""
import sys
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__.strip())
        return 1
    address = sys.argv[1].strip()
    if not address.startswith("http://"):
        address = "http://" + address
    path = Path(sys.argv[2]) if len(sys.argv) > 2 else ROOT / "fixtures-private" / "source.properties"
    if not path.is_file():
        print(f"Missing {path}. See the usage in this script.")
        return 1
    fields = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            fields[key.strip()] = value.strip()
    form = {k: fields.get(k, "") for k in ("link", "server", "username", "password")}
    if not form["link"] and not (form["server"] and form["username"] and form["password"]):
        print("The file needs link=..., or server=, username= and password=.")
        return 1
    body = urllib.parse.urlencode(form).encode()
    try:
        with urllib.request.urlopen(urllib.request.Request(address, data=body), timeout=15) as response:
            page = response.read().decode("utf-8", "replace")
    except Exception as e:  # network errors only; never echo the form
        print(f"Couldn't reach the TV: {type(e).__name__}")
        return 1
    if "Sent!" in page:
        print("Sent. The TV is checking the source now.")
        return 0
    if "expired" in page:
        print("The setup address has expired: open 'Set up from your phone' again and use the new address.")
    else:
        print("The TV didn't accept it (check the file's fields).")
    return 1


if __name__ == "__main__":
    sys.exit(main())
