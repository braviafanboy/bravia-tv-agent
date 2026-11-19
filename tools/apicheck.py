#!/usr/bin/env python3
"""Fail when the app uses an Android API newer than its minSdkVersion without
a recorded review.

The sources are compiled against a newer platform than the oldest Android the
app installs on (minSdkVersion in AndroidManifest.xml), and javac accepts every
API of that newer platform. On an older TV a call to a method it does not have
throws NoSuchMethodError when it is reached, and a callback overload that only
newer Android knows is never invoked. Neither shows in a build or a unit test.

This reads the compiled classes, looks every platform class, method and field
they use up in the SDK's own table (platforms/<platform>/data/api-versions.xml)
and lists the ones added after minSdkVersion:

  calls      a method or constructor is called, or referenced as a method handle
  uses       a field is read or written, or a class is instantiated, cast to,
             caught, extended, or named in a field or method signature
  overrides  a method of the app overrides one that older Android does not have

Each must have a line in the allow-list (app/api-allowed.txt), which records
that somebody checked the use is safe on older versions (it is inside a
Build.VERSION.SDK_INT check, or the older overload is handled as well). The
check fails on a use without a line, on a line that matches nothing any more,
and on anything that is not part of Android at all (javac also accepts JDK
classes that Android lacks).

What it cannot see: constants, whose values the compiler copies into the class
(Context.RECEIVER_NOT_EXPORTED is one); anything reached by reflection; and
whether an allowed use really is guarded. An entry covers that API in that
method, so a second, unguarded call in the same method is not noticed.

usage: python3 tools/apicheck.py            (after app/build.sh)
"""
import argparse
import os
import re
import struct
import sys
import xml.etree.ElementTree as ET
from collections import namedtuple

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

# invokedynamic bootstrap classes that exist only in the JDK. d8 rewrites the
# lambdas and string concatenations that use them, so they never reach the TV.
DESUGARED = {"java/lang/invoke/LambdaMetafactory", "java/lang/invoke/StringConcatFactory"}

ACC_PRIVATE, ACC_STATIC = 0x0002, 0x0008


class Fatal(Exception):
    """A problem with the inputs, as opposed to a finding."""


# --- class files -------------------------------------------------------------

Method = namedtuple("Method", "name desc access code handlers lines")
ClassFile = namedtuple("ClassFile", "name super_name interfaces fields methods cp bootstraps source")


def parse_class(data):
    """The parts of a class file this check needs (JVM specification, chapter 4)."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise Fatal("not a class file")
    pos = 8
    (count,) = struct.unpack_from(">H", data, pos)
    pos += 2
    cp = [None] * count
    i = 1
    while i < count:
        tag = data[pos]
        pos += 1
        if tag == 1:  # Utf8
            (size,) = struct.unpack_from(">H", data, pos)
            pos += 2
            cp[i] = data[pos:pos + size].decode("utf-8", "replace")
            pos += size
        elif tag in (3, 4):  # Integer, Float
            pos += 4
        elif tag in (5, 6):  # Long, Double: two slots
            pos += 8
            i += 1
        elif tag in (7, 8, 16, 19, 20):  # Class, String, MethodType, Module, Package
            cp[i] = (tag, struct.unpack_from(">H", data, pos)[0])
            pos += 2
        elif tag in (9, 10, 11, 12, 17, 18):  # member refs, NameAndType, (Invoke)Dynamic
            cp[i] = (tag,) + struct.unpack_from(">HH", data, pos)
            pos += 4
        elif tag == 15:  # MethodHandle
            cp[i] = (tag,) + struct.unpack_from(">BH", data, pos)
            pos += 3
        else:
            raise Fatal("unknown constant pool tag %d" % tag)
        i += 1

    def u2():
        nonlocal pos
        (value,) = struct.unpack_from(">H", data, pos)
        pos += 2
        return value

    def class_name(index):
        return cp[cp[index][1]] if index else None

    def attributes():
        nonlocal pos
        found = {}
        for _ in range(u2()):
            name = cp[u2()]
            (size,) = struct.unpack_from(">I", data, pos)
            pos += 4
            found[name] = data[pos:pos + size]
            pos += size
        return found

    u2()  # access flags
    name = class_name(u2())
    super_name = class_name(u2())
    interfaces = [class_name(u2()) for _ in range(u2())]

    fields = []
    for _ in range(u2()):
        access, name_index, desc_index = u2(), u2(), u2()
        attributes()
        fields.append((cp[name_index], cp[desc_index], access))

    methods = []
    for _ in range(u2()):
        access, name_index, desc_index = u2(), u2(), u2()
        code, handlers, lines = None, [], []
        body = attributes().get("Code")
        if body is not None:
            (size,) = struct.unpack_from(">I", body, 4)
            code = body[8:8 + size]
            at = 8 + size
            (n,) = struct.unpack_from(">H", body, at)
            at += 2
            for _ in range(n):
                handlers.append(struct.unpack_from(">H", body, at + 6)[0])  # catch type
                at += 8
            (n,) = struct.unpack_from(">H", body, at)
            at += 2
            for _ in range(n):
                attr_name, attr_size = struct.unpack_from(">HI", body, at)
                at += 6
                if cp[attr_name] == "LineNumberTable":
                    (m,) = struct.unpack_from(">H", body, at)
                    lines += [struct.unpack_from(">HH", body, at + 2 + 4 * k) for k in range(m)]
                at += attr_size
            lines.sort()
        methods.append(Method(cp[name_index], cp[desc_index], access, code, handlers, lines))

    attrs = attributes()
    bootstraps = []
    table = attrs.get("BootstrapMethods")
    if table is not None:
        at = 2
        for _ in range(struct.unpack_from(">H", table, 0)[0]):
            handle, n = struct.unpack_from(">HH", table, at)
            at += 4
            bootstraps.append((handle, struct.unpack_from(">%dH" % n, table, at)))
            at += 2 * n
    source = attrs.get("SourceFile")
    source = cp[struct.unpack_from(">H", source, 0)[0]] if source else None
    return ClassFile(name, super_name, interfaces, fields, methods, cp, bootstraps, source)


def instructions(code):
    """Yield (pc, opcode, constant pool index or None) for each instruction."""
    pc, end = 0, len(code)
    while pc < end:
        op = code[pc]
        index = None
        if op == 18:  # ldc
            index, size = code[pc + 1], 2
        elif op in (19, 20) or 178 <= op <= 184 or op in (187, 189, 192, 193):
            # ldc_w, ldc2_w, field access, invokes, new, anewarray, checkcast, instanceof
            index, size = struct.unpack_from(">H", code, pc + 1)[0], 3
        elif op in (185, 186):  # invokeinterface, invokedynamic
            index, size = struct.unpack_from(">H", code, pc + 1)[0], 5
        elif op == 197:  # multianewarray
            index, size = struct.unpack_from(">H", code, pc + 1)[0], 4
        elif op in (16, 188) or 21 <= op <= 25 or 54 <= op <= 58 or op == 169:
            size = 2  # bipush, newarray, loads and stores with an index, ret
        elif op in (17, 132) or 153 <= op <= 168 or op in (198, 199):
            size = 3  # sipush, iinc, branches
        elif op in (200, 201):  # goto_w, jsr_w
            size = 5
        elif op == 170:  # tableswitch
            at = (pc + 4) & ~3
            low, high = struct.unpack_from(">ii", code, at + 4)
            size = at + 12 + 4 * (high - low + 1) - pc
        elif op == 171:  # lookupswitch
            at = (pc + 4) & ~3
            (pairs,) = struct.unpack_from(">i", code, at + 4)
            size = at + 8 + 8 * pairs - pc
        elif op == 196:  # wide
            size = 6 if code[pc + 1] == 132 else 4
        elif op <= 201:
            size = 1
        else:
            raise Fatal("unknown opcode %d" % op)
        yield pc, op, index
        pc += size
    if pc != end:
        raise Fatal("bytecode does not end on an instruction boundary")


def classes_in(descriptor):
    return re.findall(r"L([^;]+);", descriptor)


def element_class(name):
    """The class behind a Class constant, or None for arrays of primitives."""
    if not name.startswith("["):
        return name
    found = classes_in(name)
    return found[0] if found else None


# A use: where it is, what kind, and the reference:
#   ("class", name) | ("method", owner, name, descriptor) | ("field", owner, name)
Use = namedtuple("Use", "cls member kind ref line")


def source_member(name):
    """The method a person wrote: a lambda body counts as its enclosing method."""
    match = re.match(r"lambda\$(.+)\$\d+$", name)
    if not match:
        return name
    return {"new": "<init>", "static": "<clinit>"}.get(match.group(1), match.group(1))


def uses_of(cf):
    """Every reference this class makes to another class or its members."""
    cp = cf.cp

    def name_of(index):
        return cp[cp[index][1]]

    def member(index, as_field):
        _, owner, name_type = cp[index]
        _, name, desc = cp[name_type]
        if as_field:
            return ("field", name_of(owner), cp[name])
        return ("method", name_of(owner), cp[name], cp[desc])

    def constant(index, seen=()):
        """References made through a loadable constant or a bootstrap argument."""
        entry = cp[index]
        if not isinstance(entry, tuple) or index in seen:
            return
        tag = entry[0]
        if tag == 7:
            cls = element_class(name_of(index))
            if cls:
                yield "uses", ("class", cls)
        elif tag == 16:
            for cls in classes_in(cp[entry[1]]):
                yield "uses", ("class", cls)
        elif tag == 15:
            yield ("uses", member(entry[2], True)) if entry[1] <= 4 else ("calls", member(entry[2], False))
        elif tag in (17, 18):
            for cls in classes_in(cp[cp[entry[2]][2]]):
                yield "uses", ("class", cls)
            handle, arguments = cf.bootstraps[entry[1]]
            if name_of(cp[cp[handle][2]][1]) not in DESUGARED:
                yield from constant(handle, seen + (index,))
            for argument in arguments:
                yield from constant(argument, seen + (index,))

    for parent in [cf.super_name] + cf.interfaces:
        if parent:
            yield Use(cf.name, None, "uses", ("class", parent), None)
    for name, desc, _ in cf.fields:
        for cls in classes_in(desc):
            yield Use(cf.name, name, "uses", ("class", cls), None)
    for m in cf.methods:
        where = source_member(m.name)
        first = m.lines[0][1] if m.lines else None

        def line_at(pc):
            line = first
            for start, number in m.lines:
                if start > pc:
                    break
                line = number
            return line

        for cls in classes_in(m.desc):
            yield Use(cf.name, where, "uses", ("class", cls), first)
        for catch_type in m.handlers:
            if catch_type:
                yield Use(cf.name, where, "uses", ("class", name_of(catch_type)), first)
        for pc, op, index in instructions(m.code or b""):
            if index is None:
                continue
            if 178 <= op <= 181:
                found = [("uses", member(index, True))]
            elif 182 <= op <= 185:
                found = [("calls", member(index, False))]
            elif op in (18, 19, 186, 187, 189, 192, 193, 197):
                found = constant(index)
            else:
                continue  # ldc2_w: a long or a double
            for kind, ref in found:
                yield Use(cf.name, where, kind, ref, line_at(pc))


# --- the SDK's API table -------------------------------------------------------

ApiClass = namedtuple("ApiClass", "since parents methods fields")


def level(text):
    """An API level as a comparable value; "36" and a later "36.1" both parse."""
    major, _, minor = text.partition(".")
    return (int(major), int(minor or 0))


def load_api(path):
    api = {}
    for node in ET.parse(path).getroot().iter("class"):
        since = node.get("since", "1")
        parents = [(e.get("name"), e.get("since", since)) for e in node if e.tag in ("extends", "implements")]
        methods = {e.get("name"): e.get("since", since) for e in node.iter("method")}
        fields = {e.get("name"): e.get("since", since) for e in node.iter("field")}
        api[node.get("name")] = ApiClass(since, parents, methods, fields)
    if not api:
        raise Fatal("%s lists no classes" % path)
    return api


def api_member(api, cls, key, is_field, seen=None):
    """The API level from which platform class cls has the member, declared or
    inherited; None when it never has. The rule Android lint uses: the lowest
    level over every path, a path being as new as its newest link."""
    seen = set() if seen is None else seen
    if cls in seen or cls not in api:
        return None
    seen.add(cls)
    entry = api[cls]
    best = (entry.fields if is_field else entry.methods).get(key)
    if key.startswith("<init>"):
        return best  # constructors are not inherited
    for parent, edge in entry.parents:
        found = api_member(api, parent, key, is_field, seen)
        if found is None:
            continue
        if level(edge) > level(found):
            found = edge
        if best is None or level(found) < level(best):
            best = found
    return best


# --- the check -----------------------------------------------------------------

Finding = namedtuple("Finding", "where api since kind place")


def dotted(cls):
    return cls.replace("/", ".")


def simple_type(descriptor):
    """One field descriptor as a short Java type: [B -> byte[], Lx/Y; -> Y."""
    dims = len(descriptor) - len(descriptor.lstrip("["))
    core = descriptor[dims:]
    primitive = {"B": "byte", "C": "char", "D": "double", "F": "float", "I": "int",
                 "J": "long", "S": "short", "Z": "boolean", "V": "void"}
    name = primitive[core] if core in primitive else core[1:-1].rsplit("/", 1)[-1]
    return name + "[]" * dims


def api_name(ref):
    """The reference the way the allow-list spells it."""
    if ref[0] == "class":
        return dotted(ref[1])
    if ref[0] == "field":
        return "%s#%s" % (dotted(ref[1]), ref[2])
    params = re.findall(r"\[*(?:[BCDFIJSZ]|L[^;]+;)", ref[3][1:ref[3].index(")")])
    return "%s#%s(%s)" % (dotted(ref[1]), ref[2], ",".join(simple_type(p) for p in params))


def check(classes, api, min_sdk):
    """Findings newer than min_sdk, and references to things Android lacks."""
    app = {cf.name: cf for cf in classes}
    declared = {
        cf.name: ({m.name + m.desc for m in cf.methods}, {f[0] for f in cf.fields}) for cf in classes
    }
    floor = (min_sdk, 0)

    def parents(cf):
        return [p for p in [cf.super_name] + cf.interfaces if p]

    def platform_roots(cf, seen=None):
        """The platform types an app class inherits from, directly or not."""
        seen = set() if seen is None else seen
        for parent in parents(cf):
            if parent in seen:
                continue
            seen.add(parent)
            if parent in app:
                yield from platform_roots(app[parent], seen)
            else:
                yield parent

    def resolve(owner, key, is_field):
        """Where a referenced member comes from: "app" when the app declares it,
        (level, platform class it is reached through) when the platform does,
        None when Android has no such member."""
        if owner.startswith("["):
            owner = "java/lang/Object"  # array.clone() and the like
        if owner not in app:
            since = api_member(api, owner, key, is_field)
            return (since, owner) if since else None
        if key in declared[owner][1 if is_field else 0]:
            return "app"
        best = None
        for parent in parents(app[owner]):
            found = resolve(parent, key, is_field)
            if found == "app":
                return found
            if found and (best is None or level(found[0]) < level(best[0])):
                best = found
        return best

    findings, missing = [], []
    for cf in classes:
        source = cf.source or "?"

        def place(line):
            return "%s:%d" % (source, line) if line else source

        def where(member):
            return dotted(cf.name) + ("#" + member if member else "")

        for use in uses_of(cf):
            ref = use.ref
            if ref[0] == "class":
                if ref[1] in app:
                    continue
                found = (api[ref[1]].since, ref[1]) if ref[1] in api else None
            else:
                is_field = ref[0] == "field"
                found = resolve(ref[1], ref[2] if is_field else ref[2] + ref[3], is_field)
                if found == "app":
                    continue
                if found:
                    # Name the platform class, also when the code reaches the
                    # member through an app class that inherits it.
                    ref = (ref[0], found[1]) + ref[2:]
            if found is None:
                missing.append(Finding(where(use.member), api_name(ref), None, use.kind, place(use.line)))
            elif level(found[0]) > floor:
                findings.append(Finding(where(use.member), api_name(ref), found[0], use.kind, place(use.line)))

        # Overrides. A method that a platform parent has only from a newer level
        # is called by that level and later, and by nothing before.
        roots = list(platform_roots(cf))
        for m in cf.methods:
            if m.name in ("<init>", "<clinit>") or m.access & (ACC_PRIVATE | ACC_STATIC):
                continue
            best = None
            for root in roots:
                since = api_member(api, root, m.name + m.desc, False)
                if since and (best is None or level(since) < level(best[0])):
                    best = (since, root)
            # Newer than the parent itself, that is: a parent that is too new
            # as a whole is already reported where the class extends it.
            if best and level(best[0]) > floor and level(best[0]) > level(api[best[1]].since):
                ref = ("method", best[1], m.name, m.desc)
                line = m.lines[0][1] if m.lines else None
                findings.append(Finding(where(m.name), api_name(ref), best[0], "overrides", place(line)))
    return findings, missing


def read_allow(path):
    """{(where, api): (level, reason, line number)} from the allow-list."""
    allowed = {}
    with open(path, encoding="utf-8") as handle:
        for number, line in enumerate(handle, 1):
            text = line.strip()
            if not text or text.startswith("#"):
                continue
            parts = text.split(None, 3)
            here = "%s:%d" % (path, number)
            if len(parts) < 4 or parts[3].startswith("<"):
                raise Fatal("%s: expected four columns: <where> <api> <level> <reason>, the reason "
                            "in words" % here)
            if not re.fullmatch(r"\d+(\.\d+)?", parts[2]):
                raise Fatal("%s: the third column is the API level, a number, not %r" % (here, parts[2]))
            if (parts[0], parts[1]) in allowed:
                raise Fatal("%s: listed twice: %s %s" % (here, parts[0], parts[1]))
            allowed[(parts[0], parts[1])] = (parts[2], parts[3], number)
    return allowed


def pinned_platform():
    """ANDROID_PLATFORM from app/toolchain.env."""
    path = os.path.join(ROOT, "app", "toolchain.env")
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            if line.startswith("ANDROID_PLATFORM="):
                return line.split("=", 1)[1].strip()
    raise Fatal("%s does not set ANDROID_PLATFORM" % path)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--classes", default=os.path.join(ROOT, "app", "build", "classes"),
                        help="directory of compiled classes (default: app/build/classes)")
    parser.add_argument("--manifest", default=os.path.join(ROOT, "app", "AndroidManifest.xml"),
                        help="manifest to read minSdkVersion from (default: app/AndroidManifest.xml)")
    parser.add_argument("--allow", default=os.path.join(ROOT, "app", "api-allowed.txt"),
                        help="the allow-list (default: app/api-allowed.txt)")
    parser.add_argument("--api-versions", help="api-versions.xml (default: the one of the platform "
                        "pinned in app/toolchain.env, in $ANDROID_SDK_ROOT)")
    args = parser.parse_args()

    table = args.api_versions
    if not table:
        sdk = os.environ.get("ANDROID_SDK_ROOT") or os.path.expanduser("~/Library/Android/sdk")
        table = os.path.join(sdk, "platforms", pinned_platform(), "data", "api-versions.xml")
    if not os.path.isfile(table):
        raise Fatal("no API table at %s (is that platform installed?)" % table)

    uses_sdk = ET.parse(args.manifest).getroot().find("uses-sdk")
    min_sdk = uses_sdk.get(ANDROID_NS + "minSdkVersion") if uses_sdk is not None else None
    if not min_sdk or not min_sdk.isdigit():
        raise Fatal("%s: no numeric minSdkVersion" % args.manifest)
    min_sdk = int(min_sdk)

    paths = sorted(
        os.path.join(folder, name)
        for folder, _, names in os.walk(args.classes) for name in names if name.endswith(".class")
    )
    if not paths:
        raise Fatal("no classes in %s (run app/build.sh first)" % args.classes)
    classes = []
    for path in paths:
        try:
            with open(path, "rb") as handle:
                classes.append(parse_class(handle.read()))
        except (Fatal, struct.error, IndexError, UnicodeError) as error:
            raise Fatal("%s: cannot read the class file: %s" % (path, error))

    allowed = read_allow(args.allow)
    findings, missing = check(classes, load_api(table), min_sdk)

    def grouped(items):
        """One row per (where, api): [(where, api, since, kinds, places)], sorted."""
        rows = {}
        for item in items:
            row = rows.setdefault((item.where, item.api), [item.since, set(), set()])
            row[1].add(item.kind)
            row[2].add(item.place)
        return [(key[0], key[1], row[0], "/".join(sorted(row[1])), ", ".join(sorted(row[2])))
                for key, row in sorted(rows.items())]

    def show(rows):
        for where, api, since, kinds, places in rows:
            print("  %s  %s  (%s)" % ("API %s" % since if since else "absent", where, places))
            print("      %s %s" % (kinds, api))

    rows = grouped(findings)
    absent = grouped(missing)
    accepted = [r for r in rows if (r[0], r[1]) in allowed and allowed[(r[0], r[1])][0] == r[2]]
    wrong_level = [r for r in rows if (r[0], r[1]) in allowed and allowed[(r[0], r[1])][0] != r[2]]
    refused = [r for r in rows if (r[0], r[1]) not in allowed]
    stale = sorted(set(allowed) - {(r[0], r[1]) for r in rows}, key=lambda key: allowed[key][2])

    allow_name = os.path.relpath(args.allow, ROOT)
    print("API check: %d classes, minSdkVersion %d, API levels from %s" % (len(classes), min_sdk, table))
    if accepted:
        print("\nNewer than API %d, reviewed in %s (%d):" % (min_sdk, allow_name, len(accepted)))
        show(accepted)
    if refused:
        print("\nNOT ALLOWED: newer than API %d and not in %s (%d):" % (min_sdk, allow_name, len(refused)))
        show(refused)
        print("\n  Android older than the level shown does not have these. Guard each use with\n"
              "  Build.VERSION.SDK_INT (for an override: handle the older overload as well),\n"
              "  then record the review as one line in %s:\n" % allow_name)
        for where, api, since, _, _ in refused:
            print("%s  %s  %s  <how it is guarded>" % (where, api, since))
    if wrong_level:
        print("\nWRONG LEVEL in %s (%d): the SDK gives another level than the line does:" %
              (allow_name, len(wrong_level)))
        for where, api, since, _, _ in wrong_level:
            print("  line %d says %s, the SDK says %s: %s  %s" %
                  (allowed[(where, api)][2], allowed[(where, api)][0], since, where, api))
    if stale:
        print("\nSTALE lines in %s (%d): nothing in the code matches them any more; remove them:" %
              (allow_name, len(stale)))
        for key in stale:
            print("  line %d: %s  %s" % (allowed[key][2], key[0], key[1]))
    if absent:
        print("\nNOT IN ANDROID (%d): the SDK's table has no such class or member:" % len(absent))
        show(absent)
        print("\n  javac also accepts JDK classes that Android does not have. If d8 rewrites this\n"
              "  one for old versions, add its class to DESUGARED in tools/apicheck.py.")

    failed = bool(refused or wrong_level or stale or absent)
    print("\nAPI check: %s" % ("FAILED" if failed else "ok"))
    return 1 if failed else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Fatal as error:
        print("apicheck: %s" % error, file=sys.stderr)
        sys.exit(2)
    except (OSError, ET.ParseError) as error:
        print("apicheck: %s" % error, file=sys.stderr)
        sys.exit(2)
