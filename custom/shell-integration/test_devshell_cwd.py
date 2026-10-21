"""Exercise the installed prompt hooks in real interactive shells."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("devshell-cwd.sh").resolve()
ZSH = os.environ.get("DEVSHELL_TEST_ZSH") or shutil.which("zsh")
OSC7 = re.compile(rb"\x1b\]7;(.*?)\x07")


class ShellIntegrationTest(unittest.TestCase):
    def run_shell(self, shell, setup, interactive=True, directory_command='cd -- "$DEVSHELL_CWD_TEST_DIR"'):
        with tempfile.TemporaryDirectory(prefix="devshell-cwd-") as root:
            folder = Path(root) / "한글 space+%#?"
            folder.mkdir()
            env = dict(os.environ, DEVSHELL_CWD_SCRIPT=str(SCRIPT),
                       DEVSHELL_CWD_TEST_DIR=str(folder))
            args = [shell]
            if Path(shell).name == "bash":
                args += ["--noprofile", "--norc"]
            else:
                args += ["-f"]
            if interactive:
                args += ["-i"]
            commands = setup + '''
source "$DEVSHELL_CWD_SCRIPT"
source "$DEVSHELL_CWD_SCRIPT"
''' + directory_command + '''
false
printf 'FINISH\\n'
exit
'''
            result = subprocess.run(args, input=commands.encode(), env=env,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                    timeout=10, check=True)
            return result.stdout + result.stderr

    def assert_reports(self, output):
        reports = OSC7.findall(output)
        # One report per prompt, including after a second source, without duplicate hooks.
        self.assertEqual(5, len(reports), output)
        self.assertTrue(reports[-1].startswith(b"file:///"), reports[-1])
        self.assertTrue(reports[-1].endswith(
            b"/%ED%95%9C%EA%B8%80%20space%2B%25%23%3F"), reports[-1])
        return reports

    def test_bash_preserves_existing_hook_and_exit_status(self):
        output = self.run_shell("bash", "PS1='probe> '\nPROMPT_COMMAND='printf \"OLD:%s\\n\" \"$?\"'\n")
        self.assert_reports(output)
        self.assertIn(b"OLD:1\n", output)
        self.assertIn(b"probe> ", output)

    def test_bash_preserves_prompt_command_arrays(self):
        output = self.run_shell("bash", "PS1='probe> '\nPROMPT_COMMAND=('printf \"FIRST:%s\\n\" \"$?\"' 'printf \"SECOND\\n\"')\n")
        self.assert_reports(output)
        self.assertIn(b"FIRST:1\nSECOND\n", output)

    def test_bash_reports_directory_after_existing_hooks_change_it(self):
        for hooks in [
            "PROMPT_COMMAND='cd -- \"$DEVSHELL_CWD_TEST_DIR\"'",
            "PROMPT_COMMAND='cd -- \"$DEVSHELL_CWD_TEST_DIR\" # existing comment'",
            "PROMPT_COMMAND=('true' 'cd -- \"$DEVSHELL_CWD_TEST_DIR\"')",
        ]:
            with self.subTest(hooks=hooks):
                output = self.run_shell("bash", "PS1='probe> '\n" + hooks + "\n", directory_command="cd /")
                reports = self.assert_reports(output)
                # The existing hook has returned to the fixture directory before every prompt.
                self.assertEqual([reports[-1]] * 5, reports)

    @unittest.skipUnless(ZSH, "zsh is not installed")
    def test_zsh_preserves_existing_precmd_hooks(self):
        output = self.run_shell(ZSH, "PS1='probe> '\nold_hook() { printf 'OLD\\n'; }\nprecmd_functions=(old_hook)\n")
        self.assert_reports(output)
        self.assertIn(b"OLD\n", output)

    def test_noninteractive_shell_does_not_emit_directory_reports(self):
        output = self.run_shell("bash", "", interactive=False)
        self.assertEqual([], OSC7.findall(output))
        self.assertNotIn(b"not found", output)


if __name__ == "__main__":
    unittest.main()
