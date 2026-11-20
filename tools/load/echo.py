"""Disposable calibration HTTP endpoint: loopback only, no files or external calls."""
import asyncio
import json


async def handle(reader, writer):
    try:
        while True:
            headers = await asyncio.wait_for(reader.readuntil(b'\r\n\r\n'), 10)
            if not headers.startswith(b'GET / HTTP/1.1\r\n'):
                break
            writer.write(b'HTTP/1.1 200 OK\r\nContent-Length: 11\r\nContent-Type: application/json\r\n\r\n{"ok":true}')
            await writer.drain()
    except (asyncio.IncompleteReadError, asyncio.TimeoutError, ConnectionError):
        pass
    finally:
        writer.close()
        await writer.wait_closed()


async def main():
    server = await asyncio.start_server(handle, '127.0.0.1', 0)
    print(json.dumps({'port': server.sockets[0].getsockname()[1]}), flush=True)
    async with server:
        await server.serve_forever()


if __name__ == '__main__':
    asyncio.run(main())
