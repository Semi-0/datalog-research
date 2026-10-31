#!/usr/bin/env python3
# W17 helper: string/char/comment-aware paren scan of a Clojure file.
# Prints per-top-level-form start/end lines and final balance.
import sys

def scan(path):
    s = open(path).read()
    i, n = 0, len(s)
    line, bal, depth0 = 1, 0, None
    forms = []
    in_str = in_cmt = esc = False
    while i < n:
        c = s[i]
        if c == "\n":
            line += 1; in_cmt = False; esc = False
        elif in_cmt:
            pass
        elif in_str:
            if esc: esc = False
            elif c == "\\": esc = True
            elif c == '"': in_str = False
        elif c == ";":
            in_cmt = True
        elif c == "\\":          # char literal like \( : consume next char
            i += 1
            if s[i] == "\n": line += 1
        elif c == '"':
            in_str = True
        elif c == "(":
            if bal == 0: depth0 = line
            bal += 1
        elif c == ")":
            bal -= 1
            if bal == 0:
                forms.append((depth0, line)); depth0 = None
            elif bal < 0:
                print("UNCLOSED-RPAREN at line", line); return
        i += 1
    for a, b in forms:
        if b - a > 200 or a > 200:
            pass
    print("forms:", [(a, b) for a, b in forms if b - a > 30 or a > 230])
    print("final-balance:", bal)

scan(sys.argv[1] if len(sys.argv) > 1 else "flash_once.clj")
