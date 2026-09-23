#!/usr/bin/env bash
#
# 重新打印已安装节点的配置。
#
# install.sh 只在安装末尾打印一次那段 YAML，事后想再看就没入口了。但节点的身份
# 无非 IP + 端口 + 加密方式 + 密码四个值，后三个一直躺在 config.json 里，随时能
# 重新拼出来——不用重跑 install.sh（更不能顺手带 REGEN=1，那会换密码、旧节点作废）。
#
# 用法：
#   sudo bash show.sh
#   sudo IP=1.2.3.4 bash show.sh    # 自动取公网 IP 失败，或想用别的入口 IP 时手动指定
#
set -euo pipefail

CONF="/etc/shadowsocks-rust/config.json"

warn() { printf '\033[33m[!]\033[0m %s\n' "$*"; }
die()  { printf '\033[31m[x]\033[0m %s\n' "$*" >&2; exit 1; }

# config.json 权限是 600，非 root 读不到
[ "$(id -u)" -eq 0 ] || die "请用 root 运行：sudo bash show.sh"

[ -f "$CONF" ] || die "找不到 $CONF —— 没装过，或者跑过 uninstall.sh（它会删掉整个目录）。

密码是安装时 openssl 随机生成的，服务端这一份删了就没了，拼不回来。两条路：
  1) 手机上 /sdcard/1/clashMeta_View/config.yaml 里那条 MyVPS 是一份完整拷贝，去翻；
  2) 重跑 sudo bash install.sh 生成新节点，再在 app 里重新粘贴导入。"

# 用 grep/sed 而不是 jq：VPS 上不一定有 jq，而这份 config.json 是 install.sh 自己
# 写的、格式固定，正则足够可靠
PORT="$(grep -o '"server_port"[[:space:]]*:[[:space:]]*[0-9]*' "$CONF" | grep -o '[0-9]*$' || true)"
PASS="$(sed -n 's/.*"password"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$CONF" | head -n1 || true)"
METHOD="$(sed -n 's/.*"method"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$CONF" | head -n1 || true)"

[ -n "$PORT" ] && [ -n "$PASS" ] && [ -n "$METHOD" ] \
  || die "$CONF 解析失败（端口/密码/加密方式缺一），直接打开这个文件看看吧"

# IP 取不到不算致命错误，留个占位让用户手填，别把已经拼好的配置憋回去
IP="${IP:-}"
if [ -z "$IP" ]; then
  for u in "https://api.ipify.org" "https://ifconfig.me/ip" "https://ipinfo.io/ip"; do
    IP="$(curl -fsS --max-time 8 "$u" 2>/dev/null | tr -d '[:space:]' || true)"
    if [ -n "$IP" ]; then break; fi
  done
fi
if [ -z "$IP" ]; then
  IP="<你的VPS_IP>"
  warn "自动获取公网 IP 失败，请手动替换，或改用 sudo IP=x.x.x.x bash show.sh"
fi

# 配置没问题但服务趴了，手机侧表现是「连不上」，先说一声免得白排查
if ! systemctl is-active --quiet ss-missav 2>/dev/null; then
  warn "ss-missav 服务当前不在运行，节点配置对也连不上（systemctl status ss-missav）"
fi

cat <<EOF

========================================================================
 当前节点配置。复制到手机剪贴板，在 app 的节点页用「粘贴节点」导入。
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

 这段和安装时打印的是同一个节点，重复导入不会产生新密码。
 要换密码得显式 sudo REGEN=1 bash install.sh，换完旧节点作废、必须重新导入。
========================================================================
EOF
