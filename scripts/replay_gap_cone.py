#!/usr/bin/env python3
"""Replay the GAP-regime upright veto cone (Thresholds.gapVetoUprightAngleDeg) over the field corpus.

For every GAP-regime decision (CRASH_OK with gap_ms > 8000 or decided_by=GAP, and GAP_VETO regime=GAP)
the silence angle is recomputed from pre_x/y/z vs sil_x/y/z and each candidate cone is scored:
how many field confirms it would suppress, and whether any of them was left uncancelled.
Also prints the at-rest near-gravity confirms of the prompt regime, i.e. how a downed bike reads,
which is the FN margin a wider GAP cone eats into.

Usage: python3 scripts/replay_gap_cone.py [--root DIR] [--cones 25,30,35,40,45]
"""
import argparse, collections, csv, datetime, glob, math, os, re

DELAYED_STOP_GAP_MS = 8000  # Thresholds.delayedStopGapMs


def kv(p):
    return dict(x.split('=', 1) for x in p.split(',') if '=' in x)


def angle(d):
    try:
        a = [float(d[k]) for k in ('pre_x', 'pre_y', 'pre_z')]
        b = [float(d[k]) for k in ('sil_x', 'sil_y', 'sil_z')]
    except (KeyError, ValueError):
        return None, None
    na, nb = math.hypot(*a), math.hypot(*b)
    if na < 1 or nb < 1:
        return None, nb
    cos = sum(x * y for x, y in zip(a, b)) / na / nb
    return math.degrees(math.acos(max(-1.0, min(1.0, cos)))), nb


def outcome(evs, i, ts):
    for (t2, e2), _ in evs[i + 1:]:
        if t2 - ts > 180_000:
            break
        if e2 == 'CRASH_NO':
            return f'cancel {(t2 - ts) / 1000:.1f}s'
        if e2.startswith('ALERT_'):
            return e2
    return 'NO CANCEL'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--root', default=os.path.expanduser('~/Downloads/Telegram Desktop'))
    ap.add_argument('--cones', default='25,30,35,40,45')
    args = ap.parse_args()
    cones = [float(c) for c in args.cones.split(',')]

    sessions = collections.defaultdict(dict)  # chunks are disjoint; dict dedups re-downloads
    for f in glob.glob(os.path.join(args.root, 'ksafe_*.csv')):
        m = re.match(r'ksafe_(v[\d.]+)_([0-9a-f]+)_([0-9a-f]{6})_', os.path.basename(f))
        if not m:
            continue
        key = f'{m.group(2)}_{m.group(3)} {m.group(1)}'
        for r in csv.reader(open(f, errors='replace')):
            if len(r) >= 4 and r[0].isdigit():
                sessions[key][(int(r[0]), r[2])] = r[3]

    gap, rest = [], []
    for key, ev in sessions.items():
        evs = sorted(ev.items())
        for i, ((ts, e), p) in enumerate(evs):
            if e not in ('CRASH_OK', 'GAP_VETO'):
                continue
            d = kv(p)
            a, mag = angle(d)
            day = datetime.datetime.fromtimestamp(ts / 1000).strftime('%Y-%m-%d')
            if e == 'GAP_VETO':
                if d.get('regime') == 'GAP' and a is not None:
                    gap.append((a, mag, key, day, 'already vetoed'))
                continue
            try:
                g = int(float(d.get('gap_ms', '0')))
                spd = float(d.get('speed', '99'))
            except ValueError:
                continue  # comma-decimal legacy rows
            if a is None:
                continue
            if g > DELAYED_STOP_GAP_MS or d.get('decided_by') == 'GAP':
                gap.append((a, mag, key, day, outcome(evs, i, ts)))
            elif spd <= 1.5 and mag and 9.0 <= mag <= 10.6:
                rest.append((a, mag, key, day, d.get('decided_by')))

    print('GAP-regime field confirms, 20-60 deg (angle, |sil|, session, day, outcome):')
    for a, mag, key, day, out in sorted(gap):
        if 20 <= a < 60:
            print(f'  {a:5.1f}  {mag:5.2f}  {key:24} {day}  {out}')
    confirms = [g for g in gap if g[4] != 'already vetoed']
    print('\nCone -> field GAP confirms it would suppress (of which uncancelled):')
    for c in cones:
        hit = [g for g in confirms if g[0] < c]
        print(f'  {c:4.0f} deg: {len(hit):3d}  ({sum(1 for g in hit if not g[4].startswith("cancel"))})')
    print('\nPrompt-regime at-rest near-gravity confirms (how a downed/propped bike reads; untouched by the GAP cone):')
    for a, mag, key, day, by in sorted(rest):
        print(f'  {a:5.1f}  {mag:5.2f}  {key:24} {day}  {by}')


if __name__ == '__main__':
    main()
