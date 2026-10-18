#!/usr/bin/env python3
"""UI helper for the demo clips: dump the screen with uiautomator, find a node, tap it. Real taps through adb input."""
import subprocess, sys, re, time, xml.etree.ElementTree as ET
def adb(*a): return subprocess.run(['adb', *a], capture_output=True, text=True, timeout=60).stdout
def dump(save=None):
    for _ in range(3):
        adb('shell', 'uiautomator', 'dump', '/sdcard/u.xml')
        x = adb('exec-out', 'cat', '/sdcard/u.xml')
        if '<hierarchy' in x:
            if save: open(save, 'w').write(x)
            return ET.fromstring(x[x.index('<hierarchy'):])
        time.sleep(1)
    return None
def center(b):
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', b)
    l, t, r, bt = map(int, m.groups()); return (l + r) // 2, (t + bt) // 2
def find(root, rx, pkg=None):
    p = re.compile(rx, re.I)
    for n in root.iter('node'):
        if pkg and n.get('package') != pkg: continue
        for k in ('text', 'content-desc', 'resource-id'):
            if n.get(k) and p.search(n.get(k)): return n
    return None
if __name__ == '__main__':
    cmd = sys.argv[1]
    if cmd == 'dump':
        dump(sys.argv[2]); sys.exit(0)
    if cmd == 'tap':
        tries = int(sys.argv[3]) if len(sys.argv) > 3 else 6
        for i in range(tries):
            root = dump()
            n = find(root, sys.argv[2]) if root is not None else None
            if n is not None:
                x, y = center(n.get('bounds')); adb('shell', 'input', 'tap', str(x), str(y)); print('tapped', sys.argv[2], x, y); sys.exit(0)
            time.sleep(2)
        print('NOT FOUND', sys.argv[2]); sys.exit(1)
    if cmd == 'tappkg':   # first clickable node of a package (an overlay bubble without text)
        pkg = sys.argv[2]
        for i in range(6):
            root = dump()
            if root is not None:
                for n in root.iter('node'):
                    if n.get('package') == pkg and n.get('clickable') == 'true' and n.get('class') != 'android.widget.FrameLayout.None':
                        x, y = center(n.get('bounds')); adb('shell', 'input', 'tap', str(x), str(y)); print('tapped pkg node', n.get('class'), x, y); sys.exit(0)
            time.sleep(2)
        print('NO PKG NODE'); sys.exit(1)
