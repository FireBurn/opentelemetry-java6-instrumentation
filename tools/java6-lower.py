#!/usr/bin/env python3
"""Mechanical Java 7/8 -> Java 6 source lowering for code vendored from the main project:
rewrites packages, drops @Nullable, and expands diamonds whose declared type is on the same line.
Anything else (lambdas, multi-catch, try-with-resources, string switch) is left for javac to flag
and is fixed by hand. usage: java6-lower.py <from-package> <to-package> files..."""
import re, sys
src_pkg, dst_pkg = sys.argv[1], sys.argv[2]
for f in sys.argv[3:]:
    s = open(f).read()
    s = s.replace(src_pkg, dst_pkg)
    s = re.sub(r'^import javax\.annotation\.Nullable;\n', '', s, flags=re.M)
    s = re.sub(r'@Nullable[ \t]*\n?[ \t]*', '', s)
    s = re.sub(r'(\b(?:Map|HashMap|List|ArrayList|Set|HashSet|LinkedHashMap|Collection|Deque|ArrayDeque)'
               r'<([^=;\n]+?)>[ \t]+\w+[ \t]*=[ \t]*new[ \t]+\w+)<>',
               lambda m: m.group(1) + '<' + m.group(2) + '>', s)
    open(f, 'w').write(s)
