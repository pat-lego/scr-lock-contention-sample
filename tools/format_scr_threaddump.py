#!/usr/bin/env python3
"""Format the Felix SCR "Thread dump" DEBUG entries from an AEM error.log so they are easy to read.

SCR logs the dump (AbstractComponentManager.dumpThreads) as one log entry. AEM logging writes it on a
single line and replaces line breaks with "_", so a 270 KB dump becomes one line. This script:

  * splits every "Thread dump" entry found in the input back into threads and frames,
  * prints a summary (thread states, SCR lock holders/waiters, groups of identical stacks),
  * prints every thread in jstack style, SCR-related threads first.

Usage:
  format_scr_threaddump.py error.log > threaddump-formatted.txt
  grep ': Thread dump' error.log | format_scr_threaddump.py > threaddump-formatted.txt
"""
import collections
import re
import sys

ENTRY_RE = re.compile(
    r"^(?P<date>\d{2}\.\d{2}\.\d{4}) (?P<time>[\d:.]+) \*(?P<level>[A-Z]+)\* \[(?P<thread>[^\]]*)\] "
    r"(?P<logger>\S+) (?P<prefix>.*?) : Thread dump_*(?P<body>ThreadId: .*)$")
THREAD_SPLIT_RE = re.compile(r"_+(?=ThreadId: \d+ : name: )")
HEADER_RE = re.compile(r"^ThreadId: (?P<id>\d+) : name: (?P<name>.*) State: (?P<state>[A-Z_]+)$")
LOCK_RE = re.compile(r"^LockInfo: (?P<info>.*) LockOwnerId: (?P<owner_id>-?\d+) LockOwnerName: (?P<owner>.*)$")
MODULE_FRAME_RE = re.compile(r"^(?P<module>[\w.\-]+@[^/]+)/(?P<method>[^(]+)\((?P<src>.*)\)$")
COMPONENT_RE = re.compile(r"\[(?P<name>[^\[\]()]+)\((?P<id>\d+)\)\]\s*$")

SCR = "org.apache.felix.scr.impl."
SKIP_PREFIXES = ("java.", "jdk.", "sun.", "com.sun.", SCR)


class Thread:
    def __init__(self, tid, name, state, lock_info, owner_id, owner_name, frames):
        self.tid = tid
        self.name = name
        self.state = state
        self.lock_info = lock_info
        self.owner_id = owner_id
        self.owner_name = owner_name
        self.frames = frames  # raw frames, top of stack first
        self.notes = []

    def method(self, i):
        m = MODULE_FRAME_RE.match(self.frames[i])
        return m.group("method") if m else self.frames[i].split("(", 1)[0]


def jstack_frame(frame):
    """java.base@21/java.lang.Thread.sleep0(Native Method) -> java.lang.Thread.sleep0(java.base@21/Native Method)"""
    m = MODULE_FRAME_RE.match(frame)
    if m:
        return "%s(%s/%s)" % (m.group("method"), m.group("module"), m.group("src"))
    return frame


def parse_threads(body):
    threads = []
    for chunk in THREAD_SPLIT_RE.split(body):
        parts = chunk.split("_  ")
        header = HEADER_RE.match(parts[0].strip())
        if not header:
            continue
        lock = LOCK_RE.match(parts[1].strip()) if len(parts) > 1 else None
        frames = [p.strip() for p in parts[2 if lock else 1:] if p.strip()]
        threads.append(Thread(
            int(header.group("id")), header.group("name"), header.group("state"),
            lock.group("info") if lock else "?",
            int(lock.group("owner_id")) if lock else -1,
            lock.group("owner") if lock else "null",
            frames))
    return threads


def component_code_frame(t, create_idx):
    """First frame above SCR's createComponent that is not JDK or SCR code: the component's own method."""
    for i in range(create_idx - 1, -1, -1):
        method = t.method(i)
        if not method.startswith(SKIP_PREFIXES):
            return jstack_frame(t.frames[i])
    return None


def annotate(threads, failed_component):
    by_id = {t.tid: t for t in threads}
    for t in threads:
        methods = [t.method(i) for i in range(len(t.frames))]
        if any(m.endswith("ThreadImpl.dumpThreads0") for m in methods) and \
                any(m == SCR + "manager.AbstractComponentManager.dumpThreads" for m in methods):
            t.notes.append("TOOK THIS DUMP: its tryLock on an SCR component lock timed out "
                           "(AbstractComponentManager.obtainLock -> dumpThreads)")
            if failed_component:
                t.notes.append("Lock that timed out: SCR state lock of component %s" % failed_component)
        elif any(m == SCR + "manager.AbstractComponentManager.obtainLock" for m in methods):
            t.notes.append("WAITING for an SCR component lock (AbstractComponentManager.obtainLock)")
        for i, m in enumerate(methods):
            if m == SCR + "manager.SingleComponentManager.createComponent" and \
                    any(x == SCR + "manager.SingleComponentManager.getServiceInternal" for x in methods[i:]):
                code = component_code_frame(t, i)
                t.notes.append("HOLDS the SCR state lock of the component it is creating "
                               "(getServiceInternal runs createComponent under obtainStateLock); component code: %s"
                               % (code or "?"))
        if t.owner_id != -1:
            owner = by_id.get(t.owner_id)
            t.notes.append("BLOCKED on %s owned by \"%s\" #%d" % (
                t.lock_info, owner.name if owner else t.owner_name, t.owner_id))


LOW_LEVEL = ("jdk.internal.misc.", "java.util.concurrent.locks.", "java.lang.Object.wait", "java.lang.Thread.sleep",
             "java.util.concurrent.ForkJoinPool.managedBlock", "java.util.concurrent.ForkJoinPool.unmanagedBlock",
             "java.lang.ref.", "sun.nio.ch.", "java.net.", "java.io.")


def descriptive_frame(t):
    """First frame that says what the thread is doing, skipping park/wait/sleep and I/O primitives."""
    for i in range(len(t.frames)):
        if not t.method(i).startswith(LOW_LEVEL):
            return jstack_frame(t.frames[i])
    return jstack_frame(t.frames[0]) if t.frames else "(no frames)"


def is_interesting(t):
    return bool(t.notes) or any(t.method(i).startswith(SCR) for i in range(len(t.frames)))


def print_thread(out, t):
    out.write('"%s" #%d\n' % (t.name, t.tid))
    out.write("   java.lang.Thread.State: %s\n" % t.state)
    for note in t.notes:
        out.write("   >>> %s\n" % note)
    if t.lock_info != "null":
        owner = "" if t.owner_id == -1 else ' owned by "%s" #%d' % (t.owner_name, t.owner_id)
        out.write("   - waiting on %s%s\n" % (t.lock_info, owner))
    for frame in t.frames:
        out.write("\tat %s\n" % jstack_frame(frame))
    out.write("\n")


def format_dump(out, entry, index, total):
    threads = parse_threads(entry.group("body"))
    comp = COMPONENT_RE.search(entry.group("prefix"))
    failed_component = "%s (component id %s)" % (comp.group("name"), comp.group("id")) if comp else None
    annotate(threads, failed_component)

    rule = "=" * 100
    out.write(rule + "\n")
    out.write("SCR THREAD DUMP %d of %d\n" % (index, total))
    out.write(rule + "\n")
    out.write("Logged at      : %s %s (%s, written by [%s])\n" % (
        entry.group("date"), entry.group("time"), entry.group("level"), entry.group("thread")))
    out.write("Logger         : %s\n" % entry.group("logger"))
    out.write("SCR log prefix : %s\n" % entry.group("prefix"))
    if failed_component:
        out.write("Lock timed out : SCR state lock of %s\n" % failed_component)
    out.write("Threads        : %d\n\n" % len(threads))

    out.write("Thread states\n")
    for state, count in collections.Counter(t.state for t in threads).most_common():
        out.write("  %-14s %4d\n" % (state, count))
    out.write("\n")

    noted = [t for t in threads if t.notes]
    out.write("SCR lock holders / waiters\n")
    if not noted:
        out.write("  (none found)\n")
    for t in noted:
        out.write('  "%s" #%d %s\n' % (t.name, t.tid, t.state))
        for note in t.notes:
            out.write("      %s\n" % note)
    out.write("\n  Note: SCR's dump does not print locked synchronizers, so the owner of a component lock is\n"
              "  not named directly. Holders are recognised by their stack (getServiceInternal -> createComponent).\n\n")

    groups = collections.defaultdict(list)
    for t in threads:
        groups[(t.state, tuple(t.frames[:8]))].append(t)
    repeated = sorted((g for g in groups.values() if len(g) > 1), key=len, reverse=True)
    out.write("Groups of threads with the same state and top 8 frames (idle pools)\n")
    for g in repeated[:15]:
        top = descriptive_frame(g[0])
        names = ", ".join(t.name for t in g[:4]) + (", ..." if len(g) > 4 else "")
        out.write("  %3d x %-13s %s\n        e.g. %s\n" % (len(g), g[0].state, top, names))
    out.write("\n")

    interesting = [t for t in threads if is_interesting(t)]
    out.write(rule + "\nSCR-RELATED THREADS (%d)\n" % len(interesting) + rule + "\n\n")
    for t in sorted(interesting, key=lambda x: (not x.notes, x.tid)):
        print_thread(out, t)

    out.write(rule + "\nALL THREADS BY THREAD ID (%d)\n" % len(threads) + rule + "\n\n")
    for t in sorted(threads, key=lambda x: x.tid):
        print_thread(out, t)


def main():
    source = open(sys.argv[1], encoding="utf-8", errors="replace") if len(sys.argv) > 1 else sys.stdin
    entries = [m for m in (ENTRY_RE.match(line.rstrip("\n")) for line in source) if m]
    if not entries:
        sys.exit("No SCR 'Thread dump' entry found in the input.")
    for i, entry in enumerate(entries, 1):
        format_dump(sys.stdout, entry, i, len(entries))


if __name__ == "__main__":
    main()
