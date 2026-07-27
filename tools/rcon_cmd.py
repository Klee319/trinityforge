#!/usr/bin/env python3
"""Minimal Minecraft RCON client for TrinityForge smoke tests."""
from __future__ import annotations

import argparse
import os
import socket
import struct
import sys
from pathlib import Path

SERVER_DIR = Path(r"D:\game\minecraft\PaperServer\TrinityForge")
ENV_FILE = SERVER_DIR / ".rcon.env"


def load_env() -> dict[str, str]:
    env = {
        "RCON_HOST": "127.0.0.1",
        "RCON_PORT": "25575",
        "RCON_PASSWORD": "",
    }
    if ENV_FILE.exists():
        for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip()
    for k in list(env):
        if k in os.environ:
            env[k] = os.environ[k]
    return env


class Rcon:
    SERVERDATA_AUTH = 3
    SERVERDATA_AUTH_RESPONSE = 2
    SERVERDATA_EXECCOMMAND = 2
    SERVERDATA_RESPONSE_VALUE = 0

    def __init__(self, host: str, port: int, password: str, timeout: float = 5.0):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.req_id = 0
        self._auth(password)

    def close(self) -> None:
        try:
            self.sock.close()
        except OSError:
            pass

    def _pack(self, req_id: int, req_type: int, body: str) -> bytes:
        payload = struct.pack("<ii", req_id, req_type) + body.encode("utf-8") + b"\x00\x00"
        return struct.pack("<i", len(payload)) + payload

    def _recv_packet(self) -> tuple[int, int, str]:
        def read_exact(n: int) -> bytes:
            buf = b""
            while len(buf) < n:
                chunk = self.sock.recv(n - len(buf))
                if not chunk:
                    raise ConnectionError("RCON connection closed")
                buf += chunk
            return buf

        (length,) = struct.unpack("<i", read_exact(4))
        data = read_exact(length)
        req_id, req_type = struct.unpack("<ii", data[:8])
        body = data[8:-2].decode("utf-8", errors="replace")
        return req_id, req_type, body

    def _auth(self, password: str) -> None:
        self.req_id += 1
        self.sock.sendall(self._pack(self.req_id, self.SERVERDATA_AUTH, password))
        # Some servers send an empty RESPONSE_VALUE before AUTH_RESPONSE.
        while True:
            rid, rtype, _ = self._recv_packet()
            if rtype == self.SERVERDATA_AUTH_RESPONSE:
                if rid == -1:
                    raise PermissionError("RCON auth failed (bad password)")
                return

    def command(self, cmd: str) -> str:
        self.req_id += 1
        req = self.req_id
        self.sock.sendall(self._pack(req, self.SERVERDATA_EXECCOMMAND, cmd))
        # Single-packet read (Paper usually fits one packet). Avoid Source-engine flush trick —
        # it confuses Paper and can desync the socket on large replies.
        rid, rtype, body = self._recv_packet()
        if rid == -1:
            raise PermissionError("RCON auth lost")
        return body.strip()


def main() -> int:
    parser = argparse.ArgumentParser(description="Send an RCON command to the Paper server")
    parser.add_argument("command", nargs="+", help="Command text (without leading /)")
    args = parser.parse_args()
    env = load_env()
    if not env["RCON_PASSWORD"]:
        print("RCON password missing in .rcon.env", file=sys.stderr)
        return 2
    client = Rcon(env["RCON_HOST"], int(env["RCON_PORT"]), env["RCON_PASSWORD"])
    try:
        out = client.command(" ".join(args.command))
        print(out if out else "(empty response)")
        return 0
    finally:
        client.close()


if __name__ == "__main__":
    raise SystemExit(main())
