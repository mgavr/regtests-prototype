"""Finds every REG-n pointer in the source and saves the code that follows it, for the live page.

Runs when the image is built, so the page always shows the code that is really in the image.

    python3 make_snippets.py <source root> <output file>
"""
import json
import pathlib
import re
import sys

POINTER = re.compile(r"(?://|#|<!--)\s*(REG-\d+):")
SUFFIXES = {".java", ".xml", ".py"}
MOST_LINES = 16


def snippet(lines, start):
    """The pointer comment and the code after it, up to the first gap once code has begun."""
    taken = []
    for line in lines[start:start + MOST_LINES]:
        if taken and POINTER.search(line):
            break  # The next pointer starts its own snippet.
        if not line.strip() and len(taken) > 4:
            break
        taken.append(line.rstrip())
    indent = len(taken[0]) - len(taken[0].lstrip())
    return "\n".join(line[min(indent, len(line) - len(line.lstrip())):] for line in taken).rstrip()


def collect(root):
    found = {}
    for path in sorted(root.rglob("*")):
        if path.suffix not in SUFFIXES or not path.is_file():
            continue
        lines = path.read_text(encoding="utf-8").splitlines()
        for index, line in enumerate(lines):
            match = POINTER.search(line)
            if match and match.group(1) not in found:
                found[match.group(1)] = {"file": path.relative_to(root).as_posix(), "line": index + 1,
                                         "code": snippet(lines, index)}
    return found


def main():
    root, output = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
    found = collect(root)
    output.write_text(json.dumps(found, indent=1), encoding="utf-8")
    print(f"{len(found)} code pointers saved to {output}")


if __name__ == "__main__":
    main()
