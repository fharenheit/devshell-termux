package com.termux.terminal;

/** The shell reports its directory with OSC 7, separately from the window title. */
public class CurrentWorkingDirectoryTest extends TerminalTestCase {

    public void testRemoteShellIdentityIsKeptPerTerminalAndClearedBeforeReconnect() {
        withTerminalSized(20, 2);
        assertEquals(0, mTerminal.getRemoteShellPid());
        enterString("\033]1337;DevShellShellPid=12345\007");
        assertEquals(12345, mTerminal.getRemoteShellPid());
        enterString("\033]7;file://server/old\007");
        enterString("\033]1337;DevShellShellPid=0\007");
        assertEquals(0, mTerminal.getRemoteShellPid());
        assertNull(mTerminal.getCurrentWorkingDirectory());
        enterString("\033]1337;DevShellShellPid=67890\033\\");
        TerminalEmulator first = mTerminal;
        withTerminalSized(20, 2);
        assertEquals(0, mTerminal.getRemoteShellPid());
        assertEquals(67890, first.getRemoteShellPid());
    }

    public void testRemoteShellIdentityRejectsNonNumericAndOverflowValues() {
        withTerminalSized(20, 2);
        for (String invalid : new String[]{"-1", "1/../../etc", "2147483648", "123;pwd", ""}) {
            enterString("\033]1337;DevShellShellPid=123\007");
            enterString("\033]1337;DevShellShellPid=" + invalid + "\007");
            assertEquals(invalid, 0, mTerminal.getRemoteShellPid());
        }
    }

    public void testDirectoryReportDoesNotPrintOrChangeTheTitle() {
        withTerminalSized(20, 2);
        enterString("\033]2;server\007");
        assertNull(mTerminal.getCurrentWorkingDirectory());
        enterString("\033]7;file://server/srv/project\007");
        assertEquals("/srv/project", mTerminal.getCurrentWorkingDirectory());
        assertEquals("server", mTerminal.getTitle());
        assertCursorAt(0, 0);
        assertEquals(0, mOutput.bellsRung);
    }

    public void testDirectoryIsDecodedOnceAndPlusIsNotASpace() {
        withTerminalSized(20, 2);
        enterString("\033]7;file://server/srv/%ED%95%9C%EA%B8%80%20+%25%23%3F/%2520\033\\");
        assertEquals("/srv/한글 +%#?/%20", mTerminal.getCurrentWorkingDirectory());
        enterString("\033]7;file:///\007");
        assertEquals("/", mTerminal.getCurrentWorkingDirectory());
    }

    public void testSplitReportReplacesTheDirectoryOnlyWhenComplete() {
        withTerminalSized(20, 2);
        enterString("\033]7;file://server/first\007");
        enterString("\033]7;file://server/sec");
        assertEquals("/first", mTerminal.getCurrentWorkingDirectory());
        enterString("ond\033\\");
        assertEquals("/second", mTerminal.getCurrentWorkingDirectory());
    }

    public void testInvalidReportClearsTheOldDirectory() {
        withTerminalSized(20, 2);
        String[] invalid = {"", "/relative", "file:relative", "https://server/path", "file://server",
            "file://server/path?query", "file://server/path#fragment", "file://server/%zz",
            "file://server/%00", "file://server/%0A", "file://server/%1B"};
        for (String report : invalid) {
            enterString("\033]7;file://server/old\007");
            enterString("\033]7;" + report + "\007");
            assertNull(report, mTerminal.getCurrentWorkingDirectory());
        }
    }

    public void testDirectoryBelongsToOneTerminalAndResetForgetsIt() {
        withTerminalSized(20, 2);
        enterString("\033]7;file://server/first\007");
        TerminalEmulator first = mTerminal;
        withTerminalSized(20, 2);
        assertNull(mTerminal.getCurrentWorkingDirectory());
        enterString("\033]7;file://server/second\007");
        assertEquals("/first", first.getCurrentWorkingDirectory());
        assertEquals("/second", mTerminal.getCurrentWorkingDirectory());
        mTerminal.reset();
        assertNull(mTerminal.getCurrentWorkingDirectory());
    }
}
