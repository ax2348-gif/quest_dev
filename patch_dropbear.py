#!/usr/bin/env python3
"""Adapt upstream Dropbear to one Android app UID; public-key verification is unchanged."""
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
p = root / "src/common-session.c"
s = p.read_text()
start = s.index("\tpw = getpwnam(username);", s.index("void fill_passwd"))
end = s.index("\n}\n", start)
s = s[:start] + '''
    /* Android has no configurable /etc/passwd. Only our one app identity exists.
       Reject every other login name; never switch to root or another Android UID. */
    const char *home = getenv("QUEST_RUNTIME_HOME");
    if (strcmp(username, "quest") != 0 || !home || home[0] != '/' || getuid() == 0) {
        return;
    }
    ses.authstate.pw_uid = getuid();
    ses.authstate.pw_gid = getgid();
    ses.authstate.pw_name = m_strdup("quest");
    ses.authstate.pw_dir = m_strdup(home);
    ses.authstate.pw_shell = m_strdup("/system/bin/sh");
    ses.authstate.pw_passwd = m_strdup("*");
''' + s[end:]
s = s.replace("\tstruct passwd *pw = NULL;\n", "", 1)
p.write_text(s)
p = root / "src/svr-auth.c"
s = p.read_text()
needle = "\t/* check the shell is valid."
assert s.count(needle) == 1
s = s.replace(needle, '''    /* This is the fixed Android shell configured by fill_passwd above. */
    if (strcmp(usershell, "/system/bin/sh") == 0) goto goodshell;

''' + needle)
p.write_text(s)
p = root / "src/svr-authpubkey.c"
s = p.read_text()
# Android blocks identity-changing syscalls even when setting the current ID.
# Preserve upstream permission checks and only skip redundant identity changes.
for call, condition in (
    ("setegid(ses.authstate.pw_gid)", "getegid() != ses.authstate.pw_gid"),
    ("seteuid(ses.authstate.pw_uid)", "geteuid() != ses.authstate.pw_uid"),
    ("seteuid(origuid)", "geteuid() != origuid"),
    ("setegid(origgid)", "getegid() != origgid"),
):
    old = "(" + call + ") < 0"
    assert s.count(old) == 1, old
    s = s.replace(old, "(" + condition + " && " + call + " < 0)")
p.write_text(s)
for name, lookup in (
    ("svr-chansession.c", "getpwnam(ses.authstate.pw_name)"),
    ("loginrec.c", "getpwnam(li->username)"),
):
    p = root / "src" / name
    s = p.read_text()
    assert s.count(lookup) == 1, lookup
    # Authentication has already mapped "quest" to this app UID. Bionic knows
    # the generated Android UID name, not the friendly SSH login alias.
    p.write_text(s.replace(lookup, "getpwuid(getuid())"))
p = root / "src/sshpty.c"
s = p.read_text()
start = s.index("\n{", s.index("pty_setowner("))
end = s.index("\n}", start) + 2
s = s[:start] + r'''
{
    /* Android devpts assigns the calling app ownership and private access.
       Verify those properties rather than trying to chmod/chown the node,
       which Android SELinux forbids (and which would broaden access). */
    struct stat st;
    if (stat(tty_name, &st) != 0 ||
        st.st_uid != getuid() || pw->pw_uid != getuid() ||
        (st.st_mode & 0600) != 0600 || (st.st_mode & 0007) != 0 ||
        ((st.st_mode & 0070) != 0 && st.st_gid != getgid())) {
        dropbear_exit("Android PTY ownership/permissions are not private to this app");
    }
}''' + s[end:]
s = s.replace("pty_release(const char *tty_name)\n{",
    "pty_release(const char *tty_name)\n{\n"
    "    /* Closing the devpts descriptors releases the node on Android. */\n"
    "    if (getuid() != 0) return;")
p.write_text(s)
(root / "localoptions.h").write_text(r'''
#define DROPBEAR_SVR_PASSWORD_AUTH 0
#define DROPBEAR_SVR_PAM_AUTH 0
#define DROPBEAR_SVR_MULTIUSER 1
#define DROPBEAR_SVR_LOCALTCPFWD 0
#define DROPBEAR_SVR_REMOTETCPFWD 0
#define DROPBEAR_SVR_AGENTFWD 0
#define DROPBEAR_X11FWD 0
#define DROPBEAR_SFTPSERVER 0
#define DROPBEAR_SMALL_CODE 1
#define DEFAULT_PATH "/data/user/0/dev.quest.runtime/files/home/bin:/system/bin"
#define DEFAULT_ROOT_PATH DEFAULT_PATH
''')
