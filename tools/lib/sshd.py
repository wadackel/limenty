"""A test SSH server: password auth and a real shell in a PTY. Started through tools/sshd."""

import argparse
import asyncio
import fcntl
import os
import pty
import secrets
import shutil
import signal
import struct
import subprocess
import tempfile
import termios

import asyncssh


def parse():
    p = argparse.ArgumentParser(prog="tools/sshd")
    p.add_argument("--port", type=int, default=2222)
    p.add_argument("--user", default="tester")
    # Random unless given: a password fixed in the repository would hand this machine's user to
    # anything that reaches its loopback (another local user, a container, an app on the emulator).
    # Hex, so it can be typed on a device through `adb shell input text`.
    p.add_argument("--password", default=None, help="default: a new random one, printed at start")
    p.add_argument(
        "--key",
        default=os.path.expanduser("~/.cache/limenty/sshd-hostkey"),
        help="host key; created (ed25519) when missing, kept so a saved host stays trusted",
    )
    args = p.parse_args()
    args.password = args.password or secrets.token_hex(8)
    return args


ARGS = parse()
WORKDIR = tempfile.mkdtemp(prefix="limenty-sshd-")
SHELL = ["/bin/bash", "--noprofile", "--norc"]


class Server(asyncssh.SSHServer):
    def connection_made(self, conn):
        print(f"conn from {conn.get_extra_info('peername')}", flush=True)

    def connection_lost(self, exc):
        print(f"conn lost {exc!r}", flush=True)

    def begin_auth(self, username):
        return True

    def session_requested(self):
        return ShellSession()

    def password_auth_supported(self):
        return True

    def validate_password(self, username, password):
        ok = username == ARGS.user and password == ARGS.password
        # repr: the name arrives before authentication and could carry terminal escapes.
        print(f"auth user={username!r} ok={ok}", flush=True)
        return ok


def set_winsize(fd, cols, rows):
    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))


class ShellSession(asyncssh.SSHServerSession):
    def __init__(self):
        self.chan = None
        self.master = None
        self.proc = None
        self.lost = False
        self.paused = False
        self.size = (80, 24)
        self.term = "xterm-256color"

    def connection_made(self, chan):
        self.chan = chan

    def pty_requested(self, term_type, term_size, term_modes):
        self.term = term_type or self.term
        self.size = (term_size[0] or 80, term_size[1] or 24)
        print(f"pty term={self.term!r} size={self.size[0]}x{self.size[1]}", flush=True)
        return True

    def shell_requested(self):
        return True

    def exec_requested(self, command):
        return False

    def session_started(self):
        asyncio.get_running_loop().create_task(self.start())

    async def start(self):
        # SshOnAndroidTest waits for "ready" in the first output, as the embedded echo server
        # (core/transport TestSshServer) sends it; a shell prompt alone would not match.
        self.chan.write(b"limenty test sshd ready\r\n")
        # HOME and the working directory are a throwaway directory: the shell runs as the real
        # user of this machine, so a stray `rm` or a dotfile write lands somewhere disposable.
        # The caller's environment is not passed on, since it may hold tokens.
        env = {
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "USER": os.environ.get("USER", ""),
            "LOGNAME": os.environ.get("USER", ""),
            "HOME": WORKDIR,
            "TERM": self.term,
            "LANG": "en_US.UTF-8",
            "LC_ALL": "en_US.UTF-8",
            # macOS's /bin/bash otherwise opens every session with a notice to switch to zsh.
            "BASH_SILENCE_DEPRECATION_WARNING": "1",
        }
        slave = None
        try:
            # A session may have removed it (`rm -rf ~`), and every later shell would fail to start.
            os.makedirs(WORKDIR, mode=0o700, exist_ok=True)
            self.master, slave = pty.openpty()
            set_winsize(self.master, *self.size)
            self.proc = await asyncio.create_subprocess_exec(
                *SHELL, stdin=slave, stdout=slave, stderr=slave,
                cwd=WORKDIR, env=env, start_new_session=True,
                preexec_fn=lambda: fcntl.ioctl(0, termios.TIOCSCTTY, 0),
            )
        except (OSError, subprocess.SubprocessError) as e:
            print(f"shell failed to start: {e!r}", flush=True)
            if slave is not None:
                os.close(slave)
            self.close_master()
            self.chan.exit(1)
            return
        os.close(slave)
        if not self.paused:
            asyncio.get_running_loop().add_reader(self.master, self.pump)
        # The client may have gone while the shell was being spawned, before there was a
        # process for connection_lost to hang up.
        if self.lost:
            self.hang_up()
        rc = await self.proc.wait()
        self.drain()
        self.close_master()
        print(f"shell exit rc={rc}", flush=True)
        try:
            self.chan.exit(rc)
        except Exception:
            pass

    def pump(self):
        try:
            data = os.read(self.master, 65536)
        except OSError:
            data = b""
        if data:
            try:
                self.chan.write(data)
                return
            except BrokenPipeError:
                pass
        self.close_master()

    def drain(self):
        # The exit can be seen before the reader has run, and what the shell wrote last
        # (`echo done; exit`) would otherwise be dropped with the PTY. Bounded, because a job left
        # running in the background (`yes & exit`) keeps the PTY full and would hold the loop.
        if self.master is None:
            return
        os.set_blocking(self.master, False)
        for _ in range(16):
            try:
                data = os.read(self.master, 65536)
                if not data:
                    return
                self.chan.write(data)
            except (OSError, BrokenPipeError):
                return

    # Stop reading the PTY while the channel's window is full, as a real sshd does: otherwise
    # output piles up here without limit and keeps flowing long after Ctrl-C stopped the program.
    def pause_writing(self):
        self.paused = True
        if self.master is not None:
            asyncio.get_running_loop().remove_reader(self.master)

    def resume_writing(self):
        self.paused = False
        if self.master is not None:
            asyncio.get_running_loop().add_reader(self.master, self.pump)

    def data_received(self, data, datatype):
        if self.master is not None:
            os.write(self.master, data)

    def terminal_size_changed(self, width, height, pixwidth, pixheight):
        print(f"resize {width}x{height}", flush=True)
        self.size = (width, height)
        if self.master is not None:
            set_winsize(self.master, width, height)

    def eof_received(self):
        return False

    def connection_lost(self, exc):
        self.lost = True
        if self.proc:
            self.hang_up()

    def close_master(self):
        if self.master is not None:
            asyncio.get_running_loop().remove_reader(self.master)
            os.close(self.master)
            self.master = None

    def hang_up(self):
        # What a real sshd does when the client goes: close the PTY and send SIGHUP. An
        # interactive shell ignores SIGTERM, and SIGHUP alone left a shell behind when the client
        # went during its start-up; with the master closed, its next terminal read fails.
        self.close_master()
        if self.proc.returncode is None:
            self.proc.send_signal(signal.SIGHUP)


def ensure_host_key(path):
    # Private to this user: with the key, anyone could stand in for this server on the port
    # while it is down, and a saved host would trust them.
    os.makedirs(os.path.dirname(os.path.abspath(path)), mode=0o700, exist_ok=True)
    if not os.path.exists(path):
        old = os.umask(0o077)
        try:
            asyncssh.generate_private_key("ssh-ed25519").write_private_key(path)
        finally:
            os.umask(old)
    os.chmod(path, 0o600)


async def main():
    ensure_host_key(ARGS.key)
    # Loopback only: an emulator reaches the host's loopback as 10.0.2.2, and binding wider
    # would put a shell behind a password on whatever network this machine is on.
    server = await asyncssh.create_server(
        Server, "127.0.0.1", ARGS.port, server_host_keys=[ARGS.key], encoding=None,
    )
    fingerprint = asyncssh.read_private_key(ARGS.key).get_fingerprint()
    print(f"listening 127.0.0.1:{ARGS.port} user={ARGS.user} password={ARGS.password}", flush=True)
    print(f"  fp={fingerprint} workdir={WORKDIR}", flush=True)
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    # SIGHUP too: closing the terminal it runs in should still remove the work directory.
    for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
        loop.add_signal_handler(sig, stop.set)
    await stop.wait()
    server.close()


try:
    asyncio.run(main())
finally:
    shutil.rmtree(WORKDIR, ignore_errors=True)
    # A session may have left a file, a link or a FIFO in its place, which rmtree refuses.
    if os.path.islink(WORKDIR) or (os.path.lexists(WORKDIR) and not os.path.isdir(WORKDIR)):
        os.remove(WORKDIR)
