"""Conservative detection of direct infinite inputs in finite-collection tests.

This is deliberately not a general termination checker. Unknown syntax is ignored.
"""
import re


TOKEN = re.compile(r'\s+|;[^\n]*|"(?:\\.|[^"\\])*"|#\(|[()\[\]{}\x27`]|[^\s()\[\]{}\x27`]+')


def forms(text):
    stack = []
    roots = []
    for match in TOKEN.finditer(text):
        token = match.group()
        if token.isspace() or token.startswith(';'):
            continue
        if token in ('(', '#(', '[', '{'):
            node = [token]
            if stack:
                stack[-1].append(node)
            else:
                roots.append(node)
            stack.append(node)
        elif token in (')', ']', '}'):
            if not stack:
                return []
            opened = stack.pop()[0]
            if (opened, token) not in [('(', ')'), ('#(', ')'), ('[', ']'), ('{', '}')]:
                return []
        elif stack:
            stack[-1].append(token)
        else:
            roots.append(token)
    if stack:
        return []
    return roots


def unbounded(node):
    if not isinstance(node, list) or len(node) < 2 or node[0] != '(':
        return False
    name = node[1]
    if not isinstance(name, str):
        return False
    name = name.removeprefix('clojure.core/')
    if name == 'range' and len(node) == 2:
        return True
    if name in ('repeat', 'repeatedly') and len(node) == 3:
        return True
    if name == 'iterate' and len(node) == 4:
        return True
    if name in ('map', 'map-indexed') and len(node) == 4:
        return unbounded(node[-1])
    return False


def infinite_test_input(text, function):
    def visit(node):
        if not isinstance(node, list):
            return False
        if len(node) > 1 and node[1] in ('quote', 'comment'):
            return False
        if len(node) == 4 and node[:2] == ['(', function] and unbounded(node[-1]):
            return True
        return children(node[1:])
    def children(nodes):
        quoted = False
        for child in nodes:
            if quoted:
                quoted = False
            elif child in ("'", '`'):
                quoted = True
            elif visit(child):
                return True
        return False
    if '#_' in text:
        return False
    return children(forms(text))
