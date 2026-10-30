#!/usr/bin/env python3
"""CheckMarkScanner 应用内更新服务 (运行在路由器 ImmortalWRT 上)：静态提供 SERVE_DIR，并按请求来源改写 version.json 的 apkUrl。

- 局域网 10.0.0.0/24      -> http://10.0.0.1:8080/<文件名>
- tailnet 100.64.0.0/10   -> http://100.120.209.31:8080/<文件名>
- 其他 (Funnel 经 tailscaled 转发到 127.0.0.1:8080，或国内内网穿透 frpc 转发到 127.0.0.1:8081)
    -> 配置了国内中转地址 (/etc/checkmark-update/frp_base) 时给国内地址，否则给 Funnel 地址
磁盘上的 version.json 只取 apkUrl 里的文件名，发版只改这一个文件。
其他应用各占一个子目录，按同样规则改写：/squircle-todo/version.json -> <上面的地址>squircle-todo/<文件名>。
frp_base 每次请求时读取，改完文件立即生效，无需重启。
"""
import ipaddress
import json
import os
import posixpath
import sys
import threading
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

SERVE_DIR = os.environ.get("SERVE_DIR", "/mnt/usb/checkmark-update")
LAN_NET = ipaddress.ip_network("10.0.0.0/24")
TAILNET = ipaddress.ip_network("100.64.0.0/10")
LAN_BASE = "http://10.0.0.1:8080/"
TAILNET_BASE = "http://100.120.209.31:8080/"
FUNNEL_BASE = "https://arthur.taild05f40.ts.net:8443/"
# 国内内网穿透 (樱花 frp) 的公网地址，例如 https://www.haa95d85b.nyat.app:19846/
FRP_BASE_FILE = "/etc/checkmark-update/frp_base"
# 内网穿透客户端转发到这个端口；只监听本机，不暴露到局域网
FRP_PORT = 8081


def frp_base():
    try:
        with open(FRP_BASE_FILE, encoding="utf-8") as f:
            base = f.read().strip()
    except OSError:
        return None
    if not base.startswith("http"):
        return None
    return base if base.endswith("/") else base + "/"


def base_for(client_ip):
    try:
        ip = ipaddress.ip_address(client_ip)
    except ValueError:
        return frp_base() or FUNNEL_BASE
    if ip.version == 6 and ip.ipv4_mapped:
        ip = ip.ipv4_mapped
    if ip in LAN_NET:
        return LAN_BASE
    if ip in TAILNET:
        return TAILNET_BASE
    # 外网请求：有国内中转就走国内 (快)，否则走 Funnel (海外，慢)
    return frp_base() or FUNNEL_BASE


class Handler(SimpleHTTPRequestHandler):
    def do_GET(self):
        path = posixpath.normpath(urlsplit(self.path).path)
        if not path.endswith("/version.json"):
            return super().do_GET()
        client_ip = self.client_address[0]
        # "" 是 CheckMarkScanner (根目录)，"squircle-todo/" 等是子目录里的应用
        app_dir = path[1:path.rindex("/") + 1]
        try:
            # translate_path 会去掉 .. 等跳出 SERVE_DIR 的路径段
            with open(self.translate_path(path), encoding="utf-8") as f:
                info = json.load(f)
        except FileNotFoundError:
            self.send_error(404, "version.json not found")
            return
        except (OSError, ValueError) as e:
            self.send_error(500, "version.json unreadable")
            self.log_message("version.json error: %s", e)
            return
        file_name = urlsplit(info.get("apkUrl", "")).path.rsplit("/", 1)[-1]
        info["apkUrl"] = base_for(client_ip) + app_dir + file_name
        body = (json.dumps(info, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        self.wfile.write(body)
        self.log_message("version.json port=%s client=%s xff=%s funnel=%s apkUrl=%s",
                         self.server.server_address[1], client_ip,
                         self.headers.get("X-Forwarded-For"), self.headers.get("Tailscale-Funnel-Request"),
                         info["apkUrl"])


def serve(host, port):
    server = ThreadingHTTPServer((host, port), partial(Handler, directory=SERVE_DIR))
    print(f"serving {SERVE_DIR} on {host}:{port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    threading.Thread(target=serve, args=("127.0.0.1", FRP_PORT), daemon=True).start()
    serve("0.0.0.0", port)
