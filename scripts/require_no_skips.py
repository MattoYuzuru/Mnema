#!/usr/bin/env python3
"""Run unittest modules and fail if any test was skipped or none ran.

A skipped test is a silent hole in a gate that exists for safety (the codec matrix, the media runner, the dispatcher):
a missing tool or a changed environment must break the build, not quietly turn the check into a no-op.
usage: require_no_skips.py [--path DIRECTORY] MODULE [MODULE...]"""
import argparse
import sys
import unittest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--path', action='append', default=[], help='directory to import the modules from')
    parser.add_argument('modules', nargs='+')
    args = parser.parse_args(argv)
    for path in reversed(args.path):
        sys.path.insert(0, path)
    suite = unittest.defaultTestLoader.loadTestsFromNames(args.modules)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    if not result.wasSuccessful():
        return 1
    if result.testsRun == 0:
        print('no test ran', file=sys.stderr)
        return 1
    if result.skipped:
        for test, reason in result.skipped:
            print('SKIPPED (not allowed here): %s: %s' % (test.id(), reason), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
