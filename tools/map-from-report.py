#!/usr/bin/env python3
"""Import only hook names and stock checksums from an on-car Car Report.

usage: map-from-report.py REPORT --make hyundai --model ioniq5 --region kor
       --firmware 260902 --name 'Hyundai Ioniq 5' --product-prefix NEEV.KOR.

The report is untrusted input. Network details, unit identifiers, logs and all
other report fields are ignored. The resulting map is always unverified.
"""
import argparse
import glob
import json
import os
import re

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
IDENT = re.compile(r'^[A-Za-z_$][A-Za-z0-9_.$]*(?:\[\])*$')
MEMBER = re.compile(r'^[A-Za-z_$][A-Za-z0-9_$]*$')
MD5 = re.compile(r'^[0-9a-f]{32}$')
PATH = re.compile(r'^/system/(?:app|framework)/[A-Za-z0-9_.-]+$')


def require(ok, message):
    if not ok:
        raise ValueError(message)


def check_entry(point, entry, kind):
    require(isinstance(entry, dict), point + ': entry must be an object')
    keys = {'class': {'class'}, 'method': {'method', 'params', 'ret', 'static'},
            'ctor': {'ctor'}, 'field': {'field', 'type', 'static'}}[kind]
    require(set(entry) == keys, point + ': unexpected entry fields')
    for key, value in entry.items():
        if key in ('class', 'ret', 'type'):
            require(isinstance(value, str) and IDENT.fullmatch(value), point + ': bad type name')
        elif key in ('method', 'field'):
            require(isinstance(value, str) and MEMBER.fullmatch(value), point + ': bad member name')
        elif key in ('params', 'ctor'):
            require(isinstance(value, list) and all(isinstance(v, str) and IDENT.fullmatch(v) for v in value),
                    point + ': bad parameter types')
        elif key == 'static':
            require(isinstance(value, bool), point + ': static must be boolean')


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('report')
    for key in ('make', 'model', 'region', 'firmware', 'name', 'product-prefix'):
        ap.add_argument('--' + key, required=True)
    args = ap.parse_args()
    for value in (args.make, args.model, args.region, args.firmware):
        require(re.fullmatch(r'[a-z0-9][a-z0-9_-]*', value), 'unsafe map folder name')
    require(re.fullmatch(r'[A-Z0-9]+\.[A-Z0-9]+\.', args.product_prefix), 'unsafe product prefix')
    require(re.fullmatch(r'[A-Za-z0-9 -]{1,80}', args.name), 'unsafe car name')
    report = json.load(open(args.report))
    unit, probe = report['unit'], report['map_car']
    require(str(unit.get('product', '')).startswith(args.product_prefix), 'product prefix differs from report')
    require(str(unit.get('firmware')) == args.firmware, 'firmware differs from report')
    catalogue = {p['id']: p for f in glob.glob(os.path.join(REPO, 'core/hookpoints/*.json'))
                 for p in json.load(open(f))['points']}
    points, missing = probe['points'], probe['not_found']
    require(isinstance(points, dict) and isinstance(missing, dict), 'missing point maps')
    require(set(points).isdisjoint(missing) and set(points) | set(missing) == set(catalogue),
            'report does not cover exactly the hook catalogue')
    require(len(points) == probe['found'], 'point count differs from report')
    for point, entry in points.items():
        check_entry(point, entry, catalogue[point]['kind'])
    for point, why in missing.items():
        require(isinstance(why, str) and re.fullmatch(r'[A-Za-z0-9_.$ :()\[\],-]{1,160}', why),
                point + ': unsafe missing reason')
    files, app_files = probe['files'], probe['app_files']
    require(isinstance(files, dict) and isinstance(app_files, dict), 'missing file maps')
    for path, digest in files.items():
        require(PATH.fullmatch(path) and isinstance(digest, str) and MD5.fullmatch(digest),
                'unsafe file path or checksum')
    apps = sorted(app_files)
    require(set(apps) == {point.split('.', 1)[0] for point in points}, 'app list differs from points')
    for app, info in app_files.items():
        require(re.fullmatch(r'[a-z][a-z0-9_]*', app) and set(info) == {'package', 'files'},
                'unsafe app metadata')
        package = info['package']
        require(package is None or isinstance(package, str) and IDENT.fullmatch(package), 'unsafe package name')
        require(isinstance(info['files'], list) and all(path in files for path in info['files']),
                'app file lacks a stock checksum')
    folder = os.path.join(REPO, 'maps', args.make, args.model, args.region, args.firmware)
    require(not os.path.exists(folder), 'map already exists: ' + folder)
    coverage_path = os.path.join(REPO, 'maps/coverage.json')
    coverage = json.load(open(coverage_path))
    label = args.model + '-' + args.region + '-' + args.firmware
    require(label not in coverage, 'coverage entry already exists: ' + label)
    os.makedirs(folder)
    car = {'make': args.make, 'model': args.model, 'name': args.name, 'region': args.region,
           'firmware': args.firmware, 'products': [args.product_prefix], 'status': 'mapped',
           'verified': None, 'files': dict(sorted(files.items())), 'apps': apps,
           'app_files': {a: app_files[a] for a in apps}, 'missing': dict(sorted(missing.items()))}
    for app in apps:
        entries = {p: points[p] for p in sorted(points) if p.startswith(app + '.')}
        write(os.path.join(folder, app + '.json'), entries)
    write(os.path.join(folder, 'car.json'), car)
    coverage[label] = {p: 'found' if p in points else 'not found: ' + missing[p] for p in sorted(catalogue)}
    write(coverage_path, dict(sorted(coverage.items())), indent=1)
    print(os.path.relpath(folder, REPO) + ': %d points, %d missing, status mapped' %
          (len(points), len(missing)))


def write(path, data, indent=2):
    with open(path, 'w') as f:
        json.dump(data, f, indent=indent)
        f.write('\n')


if __name__ == '__main__':
    main()
