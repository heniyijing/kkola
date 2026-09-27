"""Static sanity check for the red-team app sources.

No compiler and no Android SDK are available here, so this is not a build. It
checks the failure modes that are cheap to catch mechanically:

  * unbalanced braces / parens
  * a class declared in one file but referenced under a different name
  * calls to methods that no class in the tree defines
  * references to fields that no class in the tree declares
  * duplicate method declarations inside one class
  * classes implementing an interface without defining its methods

It will not catch type errors. It exists to shrink the first compile error list.
"""

from __future__ import annotations

import os
import re
import sys
from collections import defaultdict
from typing import Dict, List, Set, Tuple

SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "app", "src", "main", "java", "com", "milai", "redteam")

CLASS_RE = re.compile(r"\b(?:public\s+|final\s+|abstract\s+)*"
                      r"(?:class|interface|enum)\s+(\w+)")
METHOD_RE = re.compile(
    r"(?:public|private|protected|static|final|synchronized|abstract|\s)+"
    r"[\w<>\[\],.?\s]+\s+(\w+)\s*\(([^;{)]*)\)\s*(?:throws [\w,.\s]+)?\{")
FIELD_RE = re.compile(
    r"(?:public|private|protected|static|final|volatile|\s)+"
    r"[\w<>\[\],.?\s]+\s+(\w+)\s*(?:=[^;]*)?;")
CALL_RE = re.compile(r"(?<![\w.])(\w+)\s*\(")

JAVA_KEYWORDS = {
    "if", "for", "while", "switch", "catch", "return", "new", "synchronized",
    "try", "do", "else", "super", "this", "assert", "throw", "case",
}

# methods provided by the platform / libraries rather than our classes
PLATFORM = {
    "toString", "equals", "hashCode", "getClass", "notify", "wait", "clone",
    "finalize", "values", "valueOf", "name", "ordinal", "getResources",
    "getSystemService", "findViewById", "setContentView", "onCreate", "onDestroy",
    "onStartCommand", "onBind", "onTransact", "requestPermissions", "startService",
    "startForegroundService", "stopSelf", "startForeground", "getApplicationContext",
    "setText", "setTextSize", "setPadding", "setHint", "addView", "setOrientation",
    "setAdapter", "setOnClickListener", "setMovementMethod", "setGravity",
    "setInputType", "setChecked", "getText", "getSelectedItem", "setContentTitle",
    "setContentText", "setSmallIcon", "setContentIntent", "addAction", "setOngoing",
    "build", "createNotificationChannel", "getExternalFilesDir", "mkdirs", "exists",
    "getAbsolutePath", "append", "write", "flush", "close", "println",
    "post", "postDelayed", "removeCallbacks", "run", "interrupt", "isInterrupted",
    "sleep", "uptimeMillis", "currentTimeMillis", "getDefaultDisplay", "getRealSize",
    "getMainLooper", "setAccessible", "invoke", "getDeclaredMethods", "getMethods",
    "getName", "getParameterTypes", "getDeclaredMethod", "getMessage", "printStackTrace",
    "put", "get", "optString", "optInt", "length", "size", "add", "contains",
    "unmodifiableSet", "unmodifiableList", "asList", "addLast", "removeFirst",
    "round", "hypot", "pow", "sin", "cos", "nextGaussian", "nextDouble", "nextFloat",
    "nextInt", "shuffle", "setNoDelay", "start", "join", "waitFor", "recycle",
    "obtain", "writeInt", "writeString", "readInt", "readString", "readException",
    "writeNoException", "enforceInterface", "transact", "pingBinder", "bindUserService",
    "requestPermission", "checkSelfPermission", "isPreV11", "addRequestPermissionResultListener",
    "removeRequestPermissionResultListener", "getSystemService", "createFromParcel",
    "getBytes", "toHexString", "format", "getDefault", "getResources",
}


def read_sources() -> Dict[str, str]:
    out: Dict[str, str] = {}
    for name in sorted(os.listdir(SRC)):
        if name.endswith(".java"):
            with open(os.path.join(SRC, name), encoding="utf-8") as fh:
                out[name] = fh.read()
    return out


def strip_noise(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    text = re.sub(r'"(?:\\.|[^"\\])*"', '""', text)
    text = re.sub(r"'(?:\\.|[^'\\])*'", "''", text)
    return text


def check_balance(name: str, text: str) -> List[str]:
    problems = []
    for open_c, close_c, label in (("{", "}", "花括号"), ("(", ")", "圆括号")):
        depth = 0
        for ch in text:
            if ch == open_c:
                depth += 1
            elif ch == close_c:
                depth -= 1
                if depth < 0:
                    problems.append(f"{name}: {label}提前闭合")
                    return problems
        if depth != 0:
            problems.append(f"{name}: {label}不平衡（差 {depth}）")
    return problems


def main() -> int:
    srcs = read_sources()
    if not srcs:
        print(f"没有找到源码: {SRC}")
        return 1

    problems: List[str] = []
    classes: Dict[str, str] = {}
    methods: Dict[str, Set[str]] = defaultdict(set)
    fields: Dict[str, Set[str]] = defaultdict(set)
    method_owner: Dict[str, Set[str]] = defaultdict(set)
    ifaces: Dict[str, Set[str]] = {}

    for name, raw in srcs.items():
        clean = strip_noise(raw)
        problems += check_balance(name, clean)

        for m in CLASS_RE.finditer(clean):
            cls = m.group(1)
            if cls in classes:
                problems.append(f"{name}: 类名 {cls} 重复定义（另一处在 {classes[cls]}）")
            classes[cls] = name

        # methods declared in this file (attribute loosely to the file)
        for m in METHOD_RE.finditer(clean):
            mname = m.group(1)
            if mname in JAVA_KEYWORDS:
                continue
            methods[name].add(mname)
            method_owner[mname].add(name)

        for m in FIELD_RE.finditer(clean):
            fname = m.group(1)
            if fname in JAVA_KEYWORDS or len(fname) < 2:
                continue
            fields[name].add(fname)

        im = re.search(r"class\s+(\w+)[^{]*\bimplements\s+([\w,\s]+)\{", clean)
        if im:
            ifaces[im.group(1)] = {x.strip() for x in im.group(2).split(",") if x.strip()}

    all_methods: Set[str] = set()
    for s in methods.values():
        all_methods |= s
    all_fields: Set[str] = set()
    for s in fields.values():
        all_fields |= s

    # interface contracts
    iface_methods: Dict[str, Set[str]] = {}
    for name, raw in srcs.items():
        clean = strip_noise(raw)
        m = re.search(r"interface\s+(\w+)\s*\{(.*?)\n\}", clean, flags=re.S)
        if m:
            sigs = set()
            for mm in METHOD_RE.finditer(m.group(2)):
                sigs.add(mm.group(1))
            # interface methods may be declared without a body
            for mm in re.finditer(r"[\w<>\[\],.\s]+\s+(\w+)\s*\([^;{)]*\)\s*;", m.group(2)):
                sigs.add(mm.group(1))
            iface_methods[m.group(1)] = sigs

    for cls, impls in ifaces.items():
        owner = classes.get(cls)
        for iface in impls:
            required = iface_methods.get(iface, set())
            provided = methods.get(owner, set())
            missing = required - provided
            if missing:
                problems.append(f"{owner}: 实现 {iface} 但缺少方法 {sorted(missing)}")

    # calls with no definition anywhere
    undefined: Dict[str, Set[str]] = defaultdict(set)
    for name, raw in srcs.items():
        clean = strip_noise(raw)
        for m in CALL_RE.finditer(clean):
            fn = m.group(1)
            if fn in JAVA_KEYWORDS or fn in PLATFORM or fn in all_methods:
                continue
            if fn[0].isupper():      # constructor / class reference
                continue
            undefined[name].add(fn)

    for name, fns in sorted(undefined.items()):
        problems.append(f"{name}: 调用了未定义的方法 {sorted(fns)}")

    print(f"扫描 {len(srcs)} 个文件, {len(classes)} 个类")
    print(f"声明方法 {len(method_owner)} 个, 字段 {len(all_fields)} 个")
    print()
    if problems:
        print("发现问题:")
        for p in problems:
            print("  -", p)
        return 1
    print("静态自检通过：括号平衡、类名唯一、接口方法齐全、无未定义方法调用。")
    print("注意：这不能替代编译，只用于缩短首次编译的错误列表。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
