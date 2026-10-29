#!/usr/bin/env python3
"""Minimal RFC6455 echo server for gateway WebSocket E2E tests."""
import base64
import hashlib
import socket
import struct
import sys
import threading

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 18100
PREFIX = sys.argv[2] if len(sys.argv) > 2 else ""


def handle(conn):
    try:
        data = b""
        while b"\r\n\r\n" not in data:
            chunk = conn.recv(4096)
            if not chunk:
                return
            data += chunk
        headers = {}
        for line in data.decode("latin1").split("\r\n")[1:]:
            if ":" in line:
                key, value = line.split(":", 1)
                headers[key.lower()] = value.strip()
        accept = base64.b64encode(
            hashlib.sha1((headers.get("sec-websocket-key", "") + GUID).encode()).digest()
        ).decode()
        conn.sendall((
            "HTTP/1.1 101 Switching Protocols\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Accept: {accept}\r\n\r\n"
        ).encode())
        while True:
            head = conn.recv(2)
            if len(head) < 2:
                return
            opcode = head[0] & 0x0F
            masked = head[1] & 0x80
            length = head[1] & 0x7F
            if length == 126:
                length = struct.unpack("!H", conn.recv(2))[0]
            elif length == 127:
                length = struct.unpack("!Q", conn.recv(8))[0]
            mask = conn.recv(4) if masked else b"\0\0\0\0"
            payload = b""
            while len(payload) < length:
                payload += conn.recv(length - len(payload))
            if masked:
                payload = bytes(value ^ mask[i % 4] for i, value in enumerate(payload))
            if opcode == 8:
                conn.sendall(b"\x88\x00")
                return
            if opcode == 9:
                conn.sendall(bytes([0x8A, len(payload)]) + payload)
                continue
            if opcode in (1, 2):
                body = payload
                if opcode == 1 and PREFIX:
                    body = f"{PREFIX}:".encode() + payload
                conn.sendall(bytes([0x80 | opcode, len(body)]) + body)
    except Exception:
        pass
    finally:
        conn.close()


def main():
    server = socket.socket()
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", PORT))
    server.listen()
    print(f"ws echo ready port={PORT} prefix={PREFIX}", flush=True)
    while True:
        conn, _ = server.accept()
        threading.Thread(target=handle, args=(conn,), daemon=True).start()


if __name__ == "__main__":
    main()
