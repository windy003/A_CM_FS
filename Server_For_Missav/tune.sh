#!/usr/bin/env bash
#
# 大带宽内核调优。
#
# 这台 VPS 的活是「中转大流量视频」：单连接长时间高速传输、并发的 HLS 分片请求、
# 外加 QUIC 的 UDP 流量。默认的内核参数是为普通 web 服务准备的，缓冲区太小，
# 在高带宽 × 高延迟（跨国链路，BDP 很大）下会成为瓶颈——带宽再大也跑不满。
#
# 独立可重跑，install.sh 会自动调用。
#
set -euo pipefail

SYSCTL_FILE="/etc/sysctl.d/99-missav-tune.conf"
LIMITS_FILE="/etc/security/limits.d/99-missav.conf"

log()  { printf '\033[32m[+]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[!]\033[0m %s\n' "$*"; }

[ "$(id -u)" -eq 0 ] || { printf '请用 root 运行\n' >&2; exit 1; }

# BBR 需要内核 >= 4.9，且 tcp_bbr 模块可用
BBR_OK=0
if modprobe tcp_bbr 2>/dev/null || grep -q bbr /proc/sys/net/ipv4/tcp_available_congestion_control 2>/dev/null; then
  BBR_OK=1
else
  warn "内核不支持 BBR（$(uname -r)），跳过拥塞控制切换，其余参数照常生效"
fi

cat > "$SYSCTL_FILE" <<EOF
# ---- Server_For_Missav 大带宽调优 ----

# 拥塞控制：BBR 在跨国高丢包链路上比默认的 cubic 快得多。
# fq 队列是 BBR 的配套，缺了它 BBR 的发包节奏控制会失效。
$( [ "$BBR_OK" = 1 ] && echo "net.core.default_qdisc = fq" )
$( [ "$BBR_OK" = 1 ] && echo "net.ipv4.tcp_congestion_control = bbr" )

# TCP 缓冲区：默认上限（约 6MB）撑不住「大带宽 × 跨国高延迟」。
# 举例：200Mbps × 200ms 往返 = 5MB 在途数据，缓冲区小于它就永远跑不满带宽。
# 这里放到 64MB，留足余量；是上限不是预分配，闲时不占内存。
net.core.rmem_max = 67108864
net.core.wmem_max = 67108864
net.ipv4.tcp_rmem = 4096 87380 67108864
net.ipv4.tcp_wmem = 4096 65536 67108864
net.ipv4.tcp_moderate_rcvbuf = 1

# UDP 缓冲区：QUIC(HTTP/3) 走 UDP，收发缓冲太小会直接丢包，表现为视频卡顿重连。
net.core.rmem_default = 26214400
net.core.wmem_default = 26214400

# 连接与队列：HLS 视频是大量短连接并发拉分片，默认队列会溢出。
net.core.somaxconn = 8192
net.core.netdev_max_backlog = 16384
net.ipv4.tcp_max_syn_backlog = 8192
net.ipv4.tcp_syncookies = 1

# 握手与回收：降低分片请求的建连开销。
net.ipv4.tcp_fastopen = 3
net.ipv4.tcp_tw_reuse = 1
net.ipv4.tcp_fin_timeout = 15
net.ipv4.tcp_slow_start_after_idle = 0

# MTU 探测：中转链路常有 MTU 不一致，开启后避免黑洞导致的卡死。
net.ipv4.tcp_mtu_probing = 1

# 转发中转需要更大的 conntrack 表（有 nf_conntrack 时生效）
net.netfilter.nf_conntrack_max = 262144

# 文件句柄总量
fs.file-max = 1048576
EOF

# 不是所有系统都加载了 nf_conntrack，缺失时 sysctl 会报错，先探测再决定保不保留
if [ ! -e /proc/sys/net/netfilter/nf_conntrack_max ]; then
  sed -i '/nf_conntrack_max/d' "$SYSCTL_FILE"
fi

sysctl --system >/dev/null 2>&1 || sysctl -p "$SYSCTL_FILE" >/dev/null
log "已应用 $SYSCTL_FILE"

cat > "$LIMITS_FILE" <<'EOF'
* soft nofile 1048576
* hard nofile 1048576
root soft nofile 1048576
root hard nofile 1048576
EOF
log "已应用 $LIMITS_FILE（重新登录后对交互 shell 生效）"

CC="$(sysctl -n net.ipv4.tcp_congestion_control 2>/dev/null || echo '?')"
QD="$(sysctl -n net.core.default_qdisc 2>/dev/null || echo '?')"
log "当前拥塞控制：$CC / 队列：$QD"
[ "$CC" = "bbr" ] || warn "拥塞控制不是 bbr，跨国速度可能打折"
