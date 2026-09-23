#!/usr/bin/env python3
"""Diffs two OtlpFullDumper captures (standard agent vs java6 agent).

Compares
  * resource attributes (typed values),
  * spans as trees: each span is identified by scope/kind/name/status/typed attributes/events
    AND the chain of its ancestors, so a span that lost its parent is a difference,
  * metric definitions (scope, name, unit, type, temporality, histogram bounds) and, per metric,
    the set of data point attribute sets.

Volatile values (ids, timestamps, pids, ephemeral ports, thread ids, stack traces) are normalized.
With --cross-jvm, values that legitimately differ between JVMs (runtime name/version, user agent,
memory pool and GC names) are normalized too, so a J9 run can be diffed against a HotSpot run.

usage: parity-diff.py [--cross-jvm] [--ignore-metric NAME ...] official.jsonl java6.jsonl
"""
import collections
import json
import re
import sys

args = sys.argv[1:]
cross_jvm = '--cross-jvm' in args
ignored_metrics = set()
while '--ignore-metric' in args:
    i = args.index('--ignore-metric')
    ignored_metrics.add(args[i + 1])
    del args[i:i + 2]
args = [a for a in args if a != '--cross-jvm']
official_path, java6_path = args

VOLATILE = {
    'thread.id': 'int:X',
    'network.peer.port': 'int:X',
    'service.instance.id': 'string:X',
    'process.pid': 'int:X',
    'process.command_line': 'string:X',
    'process.command_args': 'X',
    'exception.stacktrace': 'string:X',
    'host.name': 'string:X',
}
# metrics whose data point attribute *values* sample the agent's own runtime (its threads differ
# from the standard agent's), so only their attribute keys are compared
SAMPLED_METRICS = {'jvm.thread.count'}
# metrics a JVM may legitimately lack a source for (IBM J9 6: no GC notifications, no process CPU
# load); the standard agent leaves them out on such JVMs too
CROSS_JVM_OPTIONAL_METRICS = {'jvm.gc.duration', 'jvm.cpu.recent_utilization'}
CROSS_JVM = {
    'process.runtime.name', 'process.runtime.version', 'process.runtime.description',
    'process.executable.path', 'user_agent.original', 'jvm.memory.pool.name', 'jvm.gc.name',
    'jvm.gc.action', 'host.arch', 'os.version', 'os.description',
}


def norm_attrs(attrs):
    out = {}
    for k, v in attrs.items():
        if k in VOLATILE:
            v = VOLATILE[k]
        elif cross_jvm and k in CROSS_JVM:
            v = 'X'
        elif k == 'thread.name':
            # identity hash codes in pool thread names can be negative on J9 (qtp-123-4)
            v = re.sub(r'-?\d+', 'N', v) if cross_jvm else re.sub(r'\d+', 'N', v)
        out[k] = v
    return out


def load(path):
    resource, spans, metrics = {}, [], collections.defaultdict(dict)
    for line in open(path):
        line = line.strip()
        if not line.startswith('{'):
            continue
        rec = json.loads(line)
        if rec['t'] == 'resource':
            resource.update(norm_attrs(rec['attrs']))
        elif rec['t'] == 'span':
            spans.append(rec)
        elif rec['t'] == 'metric' and rec['name'] not in ignored_metrics:
            key = (rec['scope'], rec['name'])
            d = metrics[key]
            d['def'] = (rec['unit'], rec['type'], rec.get('monotonic'), rec.get('temporality'))
            pts = d.setdefault('points', set())
            for p in rec['points']:
                a = norm_attrs(p['attrs'])
                if rec['name'] in SAMPLED_METRICS:
                    a = {k: 'X' for k in a}
                bounds = tuple(p.get('bounds', ()))
                pts.add((tuple(sorted(a.items())), bounds))
    return resource, spans, metrics


def span_sig(s):
    events = tuple(
        (e['name'], tuple(sorted(norm_attrs(e['attrs']).items()))) for e in s['events'])
    return '%s %s %r status=%s %s events=%s' % (
        s['scope'], s['kind'], s['name'], s['status'],
        sorted(norm_attrs(s['attrs']).items()), list(events))


def span_paths(spans):
    by_id = {s['spanId']: s for s in spans}
    paths = collections.Counter()
    info = {}
    for s in spans:
        chain = []
        cur = s
        while True:
            chain.append('%s %s %r' % (cur['scope'].split('.')[-1], cur['kind'], cur['name']))
            parent = cur['parent']
            if not parent:
                break
            if parent not in by_id:
                chain.append('<remote %s>' % parent)
                break
            cur = by_id[parent]
        path = ' <- '.join(chain)
        key = (path, span_sig(s))
        paths[key] += 1
        info[key] = s
    return paths, info


r1, s1, m1 = load(official_path)
r2, s2, m2 = load(java6_path)
ok = True

# --- resource ---
if r1 == r2:
    print('RESOURCE: identical (%d attributes)' % len(r1))
else:
    ok = False
    print('RESOURCE DIFF:')
    for k in sorted(set(r1) | set(r2)):
        if r1.get(k) != r2.get(k):
            print('  %-32s official=%s java6=%s' % (k, r1.get(k), r2.get(k)))

# --- spans ---
p1, i1 = span_paths(s1)
p2, i2 = span_paths(s2)
only1 = p1 - p2
only2 = p2 - p1
matched = sum((p1 & p2).values())
print('SPANS: %d/%d official spans matched (java6 exported %d)' % (matched, len(s1), len(s2)))
if only1 or only2:
    ok = False
    for key, n in sorted(only1.items()):
        path, sig = key
        print('  ONLY OFFICIAL (x%d): %s' % (n, path))
        s = i1[key]
        # closest java6 span: same scope/kind/name
        close = [i2[k] for k in only2 if i2[k]['name'] == s['name'] and i2[k]['kind'] == s['kind']
                 and i2[k]['scope'] == s['scope']]
        if close:
            a1, a2 = norm_attrs(s['attrs']), norm_attrs(close[0]['attrs'])
            for k in sorted(set(a1) | set(a2)):
                if a1.get(k) != a2.get(k):
                    print('      %-32s official=%s java6=%s' % (k, a1.get(k), a2.get(k)))
            if s['status'] != close[0]['status']:
                print('      status official=%s java6=%s' % (s['status'], close[0]['status']))
            e1 = [e['name'] for e in s['events']]
            e2 = [e['name'] for e in close[0]['events']]
            if e1 != e2:
                print('      events official=%s java6=%s' % (e1, e2))
    for key, n in sorted(only2.items()):
        print('  ONLY JAVA6    (x%d): %s' % (n, key[0]))

# --- metrics ---
k1, k2 = set(m1), set(m2)
print('METRICS: official=%d java6=%d common=%d' % (len(k1), len(k2), len(k1 & k2)))
for key in sorted(k1 - k2):
    if cross_jvm and key[1] in CROSS_JVM_OPTIONAL_METRICS:
        print('  expected on this JVM (no source): %s' % key[1])
        continue
    ok = False
    print('  ONLY OFFICIAL: %s %s %s' % (key[0], key[1], m1[key]['def']))
for key in sorted(k2 - k1):
    ok = False
    print('  ONLY JAVA6   : %s %s %s' % (key[0], key[1], m2[key]['def']))
for key in sorted(k1 & k2):
    if m1[key]['def'] != m2[key]['def']:
        ok = False
        print('  DEF DIFF %s: official=%s java6=%s' % (key[1], m1[key]['def'], m2[key]['def']))
    a, b = m1[key]['points'], m2[key]['points']
    if cross_jvm and key[1].startswith('jvm.') and b <= a:
        # another JVM exposes a subset (e.g. memory pools without a max have no limit)
        if a != b:
            print('  expected on this JVM (fewer points): %s' % key[1])
        continue
    if a != b:
        ok = False
        print('  POINTS DIFF %s:' % key[1])
        for p in sorted(a - b):
            print('      only-official: %s' % (p,))
        for p in sorted(b - a):
            print('      only-java6   : %s' % (p,))

print('PARITY OK' if ok else 'PARITY DIFF FOUND')
sys.exit(0 if ok else 1)
