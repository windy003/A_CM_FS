#!/usr/bin/env bash
#
# Server_For_Missav —— VPS 出口节点一键安装
#
# 装的是 shadowsocks-rust 的 ssserver，协议 2022-blake3-aes-128-gcm。
#
# 为什么是 ss2022 而不是 hysteria2/vless：
#   这台 VPS 是链路的**第二跳**（手机 → 机场节点 → 本机 → missav），由机场节点中转过来。
#   hysteria2/tuic 走 QUIC(UDP)，要求机场那一跳能可靠转发 UDP，很多机场做不到或做得很差；
#   ss2022 是 TCP 承载，任何机场节点都能中转，且无需域名和证书（VPS 只有 IP 也能用）。
#   UDP(QUIC) 由 udp-over-tcp 兜底，见 README。
#
# 用法：
#   sudo bash install.sh                 # 端口随机
#   sudo PORT=8388 bash install.sh       # 指定端口
#   sudo REGEN=1 bash install.sh         # 强制重新生成密码
#
set -euo pipefail

REPO="shadowsocks/shadowsocks-rust"
CONF_DIR="/etc/shadowsocks-rust"
CONF="$CONF_DIR/config.json"
BIN="/usr/local/bin/ssserver"
UNIT="/etc/systemd/system/ss-missav.service"
METHOD="2022-blake3-aes-128-gcm"
FALLBACK_VER="v1.23.5"

log()  { printf '\033[32m[+]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[!]\033[0m %s\n' "$*"; }
die()  { printf '\033[31m[x]\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "请用 root 运行：sudo bash install.sh"

# ---------- 依赖 ----------
need() { command -v "$1" >/dev/null 2>&1; }
if ! need curl || ! need tar || ! need openssl; then
  log "安装依赖 curl/tar/openssl/xz..."
  if need apt-get; then
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq curl tar openssl xz-utils >/dev/null
  elif need dnf; then
    dnf install -y -q curl tar openssl xz >/dev/null
  elif need yum; then
    yum install -y -q curl tar openssl xz >/dev/null
  else
    die "未知的包管理器，请手动安装 curl tar openssl xz"
  fi
fi

# ---------- 架构 ----------
case "$(uname -m)" in
  x86_64|amd64)  ARCH="x86_64-unknown-linux-gnu" ;;
  aarch64|arm64) ARCH="aarch64-unknown-linux-gnu" ;;
  *) die "不支持的架构：$(uname -m)" ;;
esac

# ---------- 下载 ssserver ----------
VER="$(curl -fsSL --max-time 15 "https://api.github.com/repos/$REPO/releases/latest" 2>/dev/null \
        | grep -m1 '"tag_name"' | cut -d'"' -f4 || true)"
[ -n "$VER" ] || { VER="$FALLBACK_VER"; warn "取最新版本号失败，回退到 $VER"; }

TARBALL="shadowsocks-${VER}.${ARCH}.tar.xz"
URL="https://github.com/$REPO/releases/download/${VER}/${TARBALL}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

log "下载 shadowsocks-rust ${VER} (${ARCH})..."
curl -fL --retry 3 --max-time 180 -o "$TMP/$TARBALL" "$URL" \
  || die "下载失败：$URL"

tar -xJf "$TMP/$TARBALL" -C "$TMP" ssserver || die "解压失败（包内无 ssserver？）"
install -m 755 "$TMP/ssserver" "$BIN"
log "已安装到 $BIN"

# ---------- 端口与密码 ----------
mkdir -p "$CONF_DIR"
chmod 700 "$CONF_DIR"

OLD_PORT=""; OLD_PASS=""
if [ -f "$CONF" ]; then
  OLD_PORT="$(grep -o '"server_port"[[:space:]]*:[[:space:]]*[0-9]*' "$CONF" | grep -o '[0-9]*$' || true)"
  OLD_PASS="$(sed -n 's/.*"password"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$CONF" | head -n1 || true)"
fi

PORT="${PORT:-${OLD_PORT:-$(shuf -i 20000-50000 -n 1)}}"
if [ "${REGEN:-0}" = "1" ] || [ -z "$OLD_PASS" ]; then
  # 2022-blake3-aes-128-gcm 要求 16 字节密钥的 base64
  PASS="$(openssl rand -base64 16)"
  [ -n "$OLD_PASS" ] && warn "已按 REGEN=1 重新生成密码，旧节点作废，需在 app 里重新导入"
else
  PASS="$OLD_PASS"
  log "复用已有密码（要换请加 REGEN=1）"
fi

cat > "$CONF" <<EOF
{
    "server": "::",
    "server_port": $PORT,
    "password": "$PASS",
    "method": "$METHOD",
    "mode": "tcp_and_udp",
    "timeout": 300,
    "fast_open": true,
    "no_delay": true
}
EOF
chmod 600 "$CONF"
log "配置写入 $CONF （端口 $PORT）"

# ---------- systemd ----------
cat > "$UNIT" <<EOF
[Unit]
Description=Shadowsocks-rust server for Server_For_Missav
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=$BIN -c $CONF
Restart=on-failure
RestartSec=3
# 大带宽场景：放开 fd 上限，避免高并发分片请求打满
LimitNOFILE=1048576
AmbientCapabilities=CAP_NET_BIND_SERVICE
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now ss-missav >/dev/null 2>&1 || systemctl restart ss-missav
sleep 1
systemctl is-active --quiet ss-missav || {
  journalctl -u ss-missav -n 30 --no-pager || true
  die "服务启动失败，日志见上"
}
log "服务已启动（systemctl status ss-missav）"

# ---------- 内核调优 ----------
if [ -f "$(dirname "$0")/tune.sh" ]; then
  bash "$(dirname "$0")/tune.sh"
else
  warn "同目录没有 tune.sh，跳过内核调优（大带宽下建议执行）"
fi

# ---------- 防火墙 ----------
if need ufw && ufw status 2>/dev/null | grep -q "Status: active"; then
  ufw allow "$PORT"/tcp >/dev/null && ufw allow "$PORT"/udp >/dev/null
  log "ufw 已放行 $PORT/tcp,udp"
elif need firewall-cmd && firewall-cmd --state >/dev/null 2>&1; then
  firewall-cmd --permanent --add-port="$PORT"/tcp >/dev/null
  firewall-cmd --permanent --add-port="$PORT"/udp >/dev/null
  firewall-cmd --reload >/dev/null
  log "firewalld 已放行 $PORT/tcp,udp"
else
  warn "未检测到启用的本机防火墙。若是云服务器，记得在控制台安全组放行 $PORT 的 TCP 和 UDP"
fi

# ---------- 输出节点 ----------
IP=""
for u in "https://api.ipify.org" "https://ifconfig.me/ip" "https://ipinfo.io/ip"; do
  IP="$(curl -fsS --max-time 8 "$u" 2>/dev/null | tr -d '[:space:]')" && [ -n "$IP" ] && break
done
[ -n "$IP" ] || { IP="<你的VPS_IP>"; warn "自动获取公网 IP 失败，请手动替换"; }

cat <<EOF

========================================================================
 安装完成。把下面这段 YAML 复制到手机剪贴板，在 app 里用「粘贴节点」导入。
========================================================================

name: "MyVPS"
type: ss
server: $IP
port: $PORT
cipher: $METHOD
password: "$PASS"
udp: true

------------------------------------------------------------------------
 分享链接形式（等价，任选其一）：
 ss://$METHOD:$PASS@$IP:$PORT#MyVPS
------------------------------------------------------------------------

 导入后在 app「设置 → 指定域名走自己的 VPS」里：
   出口节点   选 MyVPS
   前置跳     选机场的自动选择组（这样机场节点被封时会自动换，链路自愈）
   域名       一行一个，先填 missav.ws，视频 CDN 域名见 README

 如果视频卡（QUIC 被机场那一跳吃掉），给节点加一行 udp-over-tcp: true，
 详见 README 的「UDP / QUIC 怎么办」。
========================================================================
EOF
