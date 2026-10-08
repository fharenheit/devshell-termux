#!/usr/bin/env python3
"""Small adb driver: python3 drv.py <cmd> [args]

cmds: dump                       print clickable/text nodes with centers
      tap <text|x,y> [n]         tap the n-th node whose text/desc contains text
      long <text|x,y> [ms]       long press
      type <text>                type text (adb input, ascii)
      key <KEYCODE...>           send keyevents
      shot <out.png>             screenshot
      swipe x1 y1 x2 y2 [ms]
      wait <text> [sec]          wait until text appears
      res <name>                 print a string resource in LANGX (en or ko)

<text> can be a string resource as @name, in the language of LANGX.
"""
import glob, os, re, subprocess, sys, time, xml.etree.ElementTree as ET

ADB = [os.environ.get("ADB", "adb"), "-s", os.environ.get("ANDROID_SERIAL", "emulator-5554")]
REPO = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "..")


def adb(*a, **kw):
    return subprocess.run(ADB + list(a), capture_output=True, **kw)


def nodes():
    for _ in range(3):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        r = adb("exec-out", "cat", "/sdcard/ui.xml")
        try:
            root = ET.fromstring(r.stdout)
            break
        except ET.ParseError:
            time.sleep(1)
    else:
        return []
    out = []
    for n in root.iter("node"):
        b = re.findall(r"\d+", n.get("bounds", ""))
        if len(b) != 4:
            continue
        x1, y1, x2, y2 = map(int, b)
        out.append(dict(text=n.get("text", ""), desc=n.get("content-desc", ""),
                        rid=n.get("resource-id", "").split("/")[-1], cls=n.get("class", "").split(".")[-1],
                        click=n.get("clickable") == "true", cx=(x1 + x2) // 2, cy=(y1 + y2) // 2,
                        bounds=(x1, y1, x2, y2)))
    return out


_STR = {}


def res(name):
    lang = os.environ.get("LANGX", "en")
    if not _STR:
        for sub in ("values", "values-ko"):
            d = _STR.setdefault(sub, {})
            for f in glob.glob(f"{REPO}/*/src/main/res/{sub}/*.xml"):
                try:
                    for e in ET.parse(f).getroot().iter("string"):
                        d[e.get("name")] = (e.text or "").replace("\\'", "'").replace('\\"', '"')
                except ET.ParseError:
                    pass
    v = _STR["values-ko" if lang == "ko" else "values"].get(name) or _STR["values"].get(name)
    if v is None:
        sys.exit(f"no string {name}")
    return v.split("%")[0].strip() or v


def find(t, idx=0):
    if t.startswith("@"):
        t = res(t[1:])
    if re.fullmatch(r"\d+,\d+", t):
        return tuple(map(int, t.split(",")))
    lt = t.lower()
    hits = [n for n in nodes() if lt in n["text"].lower() or lt in n["desc"].lower() or t == n["rid"]]
    exact = [n for n in hits if n["text"].lower() == lt or n["desc"].lower() == lt or t == n["rid"]]
    hits = exact or hits
    if len(hits) <= idx:
        sys.exit(f"not found: {t}")
    return hits[idx]["cx"], hits[idx]["cy"]


def main():
    c, a = sys.argv[1], sys.argv[2:]
    if c == "dump":
        for n in nodes():
            if n["text"] or n["desc"] or n["click"]:
                print(f'{n["cx"]:4},{n["cy"]:<4} {"C" if n["click"] else " "} {n["cls"][:14]:14} {n["rid"][:24]:24} {n["text"][:50]!r} {n["desc"][:30]!r}')
    elif c == "tap":
        x, y = find(a[0], int(a[1]) if len(a) > 1 else 0)
        adb("shell", "input", "tap", str(x), str(y))
    elif c == "long":
        x, y = find(a[0])
        ms = a[1] if len(a) > 1 else "900"
        adb("shell", "input", "swipe", str(x), str(y), str(x), str(y), ms)
    elif c == "type":
        adb("shell", "input", "text", a[0].replace(" ", "%s").replace("'", "\\'").replace('"', '\\"')
            .replace("&", "\\&").replace("|", "\\|").replace(";", "\\;").replace("(", "\\(").replace(")", "\\)")
            .replace("<", "\\<").replace(">", "\\>").replace("*", "\\*").replace("$", "\\$").replace("~", "\\~"))
    elif c == "key":
        adb("shell", "input", "keyevent", *a)
    elif c == "swipe":
        adb("shell", "input", "swipe", *a)
    elif c == "shot":
        time.sleep(0.8)
        with open(a[0], "wb") as f:
            f.write(adb("exec-out", "screencap", "-p").stdout)
    elif c == "res":
        print(res(a[0]))
    elif c == "wait":
        if a[0].startswith("@"):
            a[0] = res(a[0][1:])
        end = time.time() + (float(a[1]) if len(a) > 1 else 30)
        while time.time() < end:
            if any(a[0].lower() in n["text"].lower() or a[0].lower() in n["desc"].lower() or a[0] == n["rid"] for n in nodes()):
                return
            time.sleep(1)
        sys.exit(f"timeout waiting for {a[0]}")


main()
