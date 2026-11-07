"""Build the UTF-8 byte range and ranking inputs for each pinned current record."""
import json
import re
import sys
from pathlib import Path


def index_currents(data):
    source = data.decode("utf-8")
    rows = []
    decoder = json.JSONDecoder()
    cursor = source.index("[") + 1
    offset = len(source[:cursor].encode())
    separators = re.compile(r"[ \t\r\n,]*")
    while True:
        start = separators.match(source, cursor).end()
        if source[start] == "]":
            break
        offset += start - cursor  # JSON separators are ASCII.
        row, end = decoder.raw_decode(source, start)
        size = len(source[start:end].encode())
        signal = abs(row["meanFlow"]) + sum(c["amplitude"] for c in row["constituents"])
        rows.append(f'{row["id"]}\t{offset}\t{size}\t{int("reference" in row)}\t{signal}\n')
        offset += size
        cursor = end
    return "".join(rows).encode()


if __name__ == "__main__":
    dest = Path(sys.argv[2])
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(index_currents(Path(sys.argv[1]).read_bytes()))
