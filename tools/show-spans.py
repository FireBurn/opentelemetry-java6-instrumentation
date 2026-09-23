#!/usr/bin/env python3
"""Prints the spans of an OtlpFullDumper capture as indented trees (debugging aid)."""
import json, sys
spans = [json.loads(l) for l in open(sys.argv[1]) if '"t":"span"' in l]
by_parent = {}
ids = {s['spanId'] for s in spans}
for s in spans:
    by_parent.setdefault(s['parent'] if s['parent'] in ids else None, []).append(s)
def show(s, depth):
    a = {k: v for k, v in s['attrs'].items() if k not in ('thread.id', 'thread.name')}
    ev = [(e['name'], sorted(e['attrs'])) for e in s['events']]
    print('%s%s %s %r %s%s %s %s' % ('    ' * depth, s['scope'].replace('io.opentelemetry.', ''), s['kind'],
          s['name'], s['status'], ('(' + s['statusMessage'] + ')') if s['statusMessage'] else '',
          ' '.join('%s=%s' % kv for kv in sorted(a.items())), ev if ev else ''))
    for c in by_parent.get(s['spanId'], []):
        show(c, depth + 1)
for s in by_parent.get(None, []):
    show(s, 0)
