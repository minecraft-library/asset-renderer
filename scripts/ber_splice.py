#!/usr/bin/env python3
"""BER dual-format transition helper (temporary; removed at Phase 3).

Splices freshly-regenerated bone-format entries for the given model ids into the
committed block_models.json, leaving every other (still-element, possibly stale-2dp)
entry byte-identical. Keeps each family's migration diff family-scoped.

Usage: python scripts/ber_splice.py <fresh_regen.json> <model_id> [<model_id> ...]
"""
import sys

RESOURCE = 'src/main/resources/lib/minecraft/renderer/block_models.json'


def extract_entry(text, key):
    marker = '    "%s": {' % key
    start = text.index(marker)
    i = text.index('{', start)
    depth = 0
    j = i
    while j < len(text):
        c = text[j]
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                break
        j += 1
    return start, j + 1, text[start:j + 1]


def main():
    fresh_path = sys.argv[1]
    ids = sys.argv[2:]
    fresh = open(fresh_path, encoding='utf-8').read()
    cur = open(RESOURCE, encoding='utf-8').read()
    for mid in ids:
        cs, ce, _ = extract_entry(cur, mid)
        _, _, rblock = extract_entry(fresh, mid)
        cur = cur[:cs] + rblock + cur[ce:]
        print('spliced', mid)
    open(RESOURCE, 'w', encoding='utf-8', newline='\n').write(cur)
    print('wrote', RESOURCE, len(cur), 'bytes')


if __name__ == '__main__':
    main()
