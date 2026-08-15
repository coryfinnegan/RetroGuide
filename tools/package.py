#!/usr/bin/env python3
"""Build the sideload zip.

Roku expects manifest, source/, components/ and images/ at the ROOT of the
archive - a zip containing a single top level folder will not install.
"""
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "out", "retroguide.zip")
INCLUDE = ("manifest", "source", "components", "images")
SKIP_SUFFIX = (".pyc",)
SKIP_NAMES = {".DS_Store", "Thumbs.db", "desktop.ini"}


def main():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    count = 0
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
        for entry in INCLUDE:
            path = os.path.join(ROOT, entry)
            if os.path.isfile(path):
                z.write(path, entry)
                count += 1
            elif os.path.isdir(path):
                for base, _dirs, files in os.walk(path):
                    for name in files:
                        if name in SKIP_NAMES or name.endswith(SKIP_SUFFIX):
                            continue
                        full = os.path.join(base, name)
                        rel = os.path.relpath(full, ROOT).replace(os.sep, "/")
                        z.write(full, rel)
                        count += 1
            else:
                print("missing: %s" % entry, file=sys.stderr)
                return 1
    print("%s  (%d files, %.1f KB)" % (OUT, count, os.path.getsize(OUT) / 1024.0))
    return 0


if __name__ == "__main__":
    sys.exit(main())
