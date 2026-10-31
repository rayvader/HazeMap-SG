"""Write the "What's new" list for one release to stdout.

  release_notes.py TAG [--sha SHA] [--prerelease]

What counts as "changed since the last release":

- A main release lists the pull requests merged since the previous main
  release (by PR title), plus any commit pushed to main on its own.
- A branch pre-release lists the commits since the previous release of any kind.

TAG may not exist yet (CI runs this before it creates the release); pass --sha
then. Needs `git` with full history and tags, and `gh` (GH_TOKEN) to read PR
titles and the release list. Ported from rayvader/KiroCrew-Android; the pure
parts are split out so test_release_notes.py can check them without git or gh.
"""
import argparse
import json
import re
import subprocess
import sys

MERGE_PR = re.compile(r"Merge pull request #(\d+)")
# CI markers say nothing to someone reading the notes.
CI_MARKER = re.compile(r"\s*\[(?:skip ci|ci skip|no ci|skip actions|actions skip)\]", re.IGNORECASE)


# ---- Pure logic ----


def version(tag):
    """(1, 0, 120) for "v1.0.120"; None for anything that is not a version tag."""
    m = re.fullmatch(r"v(\d+(?:\.\d+)*)", tag or "")
    return tuple(int(x) for x in m.group(1).split(".")) if m else None


def previous(tag, releases, prerelease):
    """The newest release older than TAG. releases: (tagName, isPrerelease) pairs.

    A main release compares with the last main release; a pre-release with the last release of any kind.
    None when there is no older one (the first release).
    """
    me = version(tag)
    older = [
        (version(name), name)
        for name, pre in releases
        if version(name) and version(name) < me and (prerelease or not pre)
    ]
    return max(older)[1] if older else None


def clean(subject):
    return CI_MARKER.sub("", subject).strip()


def main_entries(subjects, pr_title):
    """First-parent subjects of main -> one line per merged PR (by its title) or direct commit."""
    out = []
    for subject in subjects:
        m = MERGE_PR.match(subject)
        if m:
            title = pr_title(m.group(1))
            out.append(f"{clean(title)} (#{m.group(1)})" if title else clean(subject))
        elif subject and not subject.startswith("Merge "):
            out.append(clean(subject))
    return [e for e in out if e]


def branch_entries(subjects):
    return [clean(s) for s in subjects if clean(s)]


def render(tag, prev, items, repo):
    """The release body. Items keep their order; repeats are dropped."""
    items = list(dict.fromkeys(items))
    lines = ["## What's new", ""]
    if prev is None:
        lines.append("- First release.")
    elif items:
        lines += [f"- {i}" for i in items]
    else:
        lines.append("- Maintenance build. No changes to the app.")
    if prev and repo:
        lines += ["", f"**Full changelog:** https://github.com/{repo}/compare/{prev}...{tag}"]
    return "\n".join(lines) + "\n"


# ---- git and gh ----


def run(*cmd):
    return subprocess.run(cmd, capture_output=True, text=True, check=True).stdout.strip()


def releases():
    raw = run("gh", "release", "list", "--limit", "300", "--json", "tagName,isPrerelease")
    return [(r["tagName"], r["isPrerelease"]) for r in json.loads(raw)]


def pr_title(number):
    try:
        return run("gh", "pr", "view", number, "--json", "title", "-q", ".title")
    except subprocess.CalledProcessError:
        return None


def entries(prev, head, prerelease):
    if prerelease:
        base = run("git", "merge-base", prev, head) if prev else None
        rng = f"{base}..{head}" if base else head
        return branch_entries(run("git", "log", "--no-merges", "--format=%s", rng).splitlines())
    rng = f"{prev}..{head}" if prev else head
    return main_entries(run("git", "log", "--first-parent", "--format=%s", rng).splitlines(), pr_title)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("tag")
    ap.add_argument("--sha")
    ap.add_argument("--prerelease", action="store_true")
    a = ap.parse_args()
    if not version(a.tag):
        sys.exit(f"not a version tag: {a.tag}")

    prev = previous(a.tag, releases(), a.prerelease)
    items = entries(prev, a.sha or a.tag, a.prerelease) if prev else []
    repo = run("gh", "repo", "view", "--json", "nameWithOwner", "-q", ".nameWithOwner") if prev else None
    sys.stdout.write(render(a.tag, prev, items, repo))


if __name__ == "__main__":
    main()
