"""Tests for release_notes.py. Run: python3 -m unittest discover -s .github/scripts -p 'test_*.py'"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import release_notes as rn  # noqa: E402

RELEASES = [
    ("v1.0.120", False),
    ("v1.0.116", True),
    ("v1.0.115", True),
    ("v1.0.106", False),
    ("v1.0.9", False),
    ("nightly", False),
]


class Previous(unittest.TestCase):
    def test_main_release_skips_pre_releases(self):
        self.assertEqual(rn.previous("v1.0.121", RELEASES, prerelease=False), "v1.0.120")
        self.assertEqual(rn.previous("v1.0.120", RELEASES, prerelease=False), "v1.0.106")

    def test_pre_release_compares_with_any_release(self):
        self.assertEqual(rn.previous("v1.0.120", RELEASES, prerelease=True), "v1.0.116")

    def test_numbers_not_text(self):
        self.assertEqual(rn.previous("v1.0.10", RELEASES, prerelease=False), "v1.0.9")

    def test_first_release_has_none(self):
        self.assertIsNone(rn.previous("v1.0.1", RELEASES, prerelease=False))
        self.assertIsNone(rn.previous("v1.0.1", [], prerelease=True))

    def test_version(self):
        self.assertEqual(rn.version("v1.0.120"), (1, 0, 120))
        self.assertIsNone(rn.version("nightly"))
        self.assertIsNone(rn.version("1.0.1"))
        self.assertIsNone(rn.version(None))


class Entries(unittest.TestCase):
    TITLES = {"23": "feat: Check for updates on the Now tab", "22": "feat: bold widget clock [skip ci]"}

    def test_main_lists_pr_titles_and_direct_commits(self):
        subjects = [
            "docs: improve README (#24) [skip ci]",
            "Merge pull request #23 from rayvader/feat/check-for-updates",
            "Merge pull request #22 from rayvader/feat/bold-widget-clock",
            "Merge branch 'main' into x",
            "",
        ]
        self.assertEqual(
            rn.main_entries(subjects, self.TITLES.get),
            [
                "docs: improve README (#24)",
                "feat: Check for updates on the Now tab (#23)",
                "feat: bold widget clock (#22)",
            ],
        )

    def test_unknown_pr_falls_back_to_the_subject(self):
        self.assertEqual(
            rn.main_entries(["Merge pull request #99 from rayvader/fix/x"], lambda n: None),
            ["Merge pull request #99 from rayvader/fix/x"],
        )

    def test_branch_lists_commit_subjects(self):
        self.assertEqual(
            rn.branch_entries(["fix: a", "", "docs: b [ci skip]", "[skip ci]"]),
            ["fix: a", "docs: b"],
        )


class Render(unittest.TestCase):
    REPO = "rayvader/HazeMap-SG"

    def test_list_and_compare_link(self):
        body = rn.render("v1.0.121", "v1.0.120", ["feat: a (#25)", "fix: b", "feat: a (#25)"], self.REPO)
        self.assertEqual(
            body,
            "## What's new\n\n- feat: a (#25)\n- fix: b\n\n"
            "**Full changelog:** https://github.com/rayvader/HazeMap-SG/compare/v1.0.120...v1.0.121\n",
        )

    def test_first_release_does_not_crash(self):
        self.assertEqual(rn.render("v1.0.1", None, [], None), "## What's new\n\n- First release.\n")

    def test_nothing_changed(self):
        body = rn.render("v1.0.121", "v1.0.120", [], self.REPO)
        self.assertIn("- Maintenance build. No changes to the app.", body)
        self.assertIn("compare/v1.0.120...v1.0.121", body)


if __name__ == "__main__":
    unittest.main()
