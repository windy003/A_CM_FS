#!/usr/bin/env bash
#
# VPS 侧测速。测两件不同的事，别只测一件：
#
#   最终速度 = min( 手机→机场, 机场→VPS, VPS→CDN, VPS 带宽上限 )
#                                         ↑            ↑
#                                     模式二         模式一
#
#   模式一（无参数）：speedtest-cli 打 Ookla 节点，测「VPS 的管道有多粗」。
#   模式二（带 URL）：curl 拉真实分片，测「VPS 到那个 CDN 有多快」。
#
# 决定这条链路值不值得做的是**模式二**。VPS 标称 1Gbps、Ookla 也跑满，
# 但到目标 CDN 只有 20Mbps 的情况很常见，模式一完全看不出来。
#
# 用法：
#   bash speedtest.sh                          # 模式一
#   bash speedtest.sh https://xxx/video.ts     # 模式二，地址从 app 连接列表里抓
#
set -euo pipefail

DURATION=15   # 模式二每个目标最多测这么多秒

log()  { printf '\033[32m[+]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[!]\033[0m %s\n' "$*"; }
need() { command -v "$1" >/dev/null 2>&1; }

# ---------------------------------------------------------------- 模式一

# 装 speedtest-cli。新版 Debian/Ubuntu 有 PEP 668 保护，pip 直装会被拒，
# 所以优先走发行版包管理器，pip 只作为兜底。
install_speedtest() {
  log "未找到 speedtest-cli，尝试安装..."
  if need apt-get; then
    apt-get update -qq >/dev/null 2>&1 || true
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq speedtest-cli >/dev/null 2>&1 && return 0
  elif need dnf; then
    dnf install -y -q speedtest-cli >/dev/null 2>&1 && return 0
  elif need yum; then
    yum install -y -q speedtest-cli >/dev/null 2>&1 && return 0
  fi

  if need pipx; then
    pipx install speedtest-cli >/dev/null 2>&1 && return 0
  fi
  if need pip3; then
    pip3 install -q speedtest-cli >/dev/null 2>&1 && return 0
    # PEP 668：系统 Python 被标记为 externally-managed，需要显式放行
    pip3 install -q --break-system-packages speedtest-cli >/dev/null 2>&1 && return 0
  fi
  return 1
}

# speedtest-cli 装不上时的兜底：拉几个公共大文件，精度不如前者但聊胜于无
fallback_bandwidth() {
  warn "speedtest-cli 不可用，退回 curl 粗测（单线程，结果会偏低）"
  for url in \
    "https://speed.cloudflare.com/__down?bytes=104857600" \
    "https://proof.ovh.net/files/100Mb.dat"
  do
    printf '\n\033[36m目标\033[0m %s\n' "$url"
    local speed
    speed="$(curl -o /dev/null -sS --max-time "$DURATION" \
              -w '%{speed_download}' "$url" 2>/dev/null || echo 0)"
    awk -v b="$speed" 'BEGIN{
      if (b <= 0) { print "  (失败)"; exit }
      printf "  %.1f Mbps  (%.1f MB/s)\n", b*8/1000000, b/1048576
    }'
  done
}

mode_bandwidth() {
  echo "================================================================"
  echo " 模式一：VPS 带宽上限（speedtest-cli → 最近的 Ookla 节点）"
  echo "================================================================"

  if ! need speedtest-cli; then
    if ! install_speedtest; then
      fallback_bandwidth
      return
    fi
    log "speedtest-cli 安装完成"
  fi

  # --secure 走 HTTPS，部分机房会拦明文测速流量
  speedtest-cli --secure || {
    warn "speedtest-cli 运行失败（Ookla 接口偶尔抽风），退回 curl 粗测"
    fallback_bandwidth
  }

  cat <<'EOF'

----------------------------------------------------------------
这个数字是「VPS 的管道有多粗」，测的是到就近 Ookla 节点的速度，
不代表它到 missav 的 CDN 也这么快。决定性的是下面这一步。

 1. 手机上开着 VPN 播一段视频
 2. 在 app 的连接列表里找持续有大流量的那条，记下域名
 3. 复制一个 .ts / .m4s 分片的完整 URL，回到 VPS 上跑：
      bash speedtest.sh 'https://那个域名/xxx.ts'

如果那一步的速度明显高于你现在手机看视频的速度，这条链路就值得做；
差不多甚至更低，说明瓶颈不在这儿，做了也白做。
----------------------------------------------------------------
EOF
}

# ---------------------------------------------------------------- 模式二

test_url() {
  local url="$1"
  printf '\n\033[36m目标\033[0m %s\n' "$url"

  local out
  out="$(curl -o /dev/null -sS --max-time "$DURATION" \
          -w '%{size_download} %{time_total} %{speed_download} %{http_code}' \
          "$url" 2>/dev/null || true)"

  if [ -z "$out" ]; then
    printf '  连接失败（被墙 / 需要 Referer / 地址已失效）\n'
    return
  fi

  local size time speed code
  read -r size time speed code <<< "$out"

  # 000 = 到点被 --max-time 掐断，这是正常的（本来就只测固定时长）
  if [ "$code" != "200" ] && [ "$code" != "206" ] && [ "$code" != "000" ]; then
    printf '  HTTP %s —— 多半是防盗链，需要从 app 连接列表里抓带签名的真实分片 URL\n' "$code"
    return
  fi

  printf '  下载 %s 字节 / %s 秒\n' "$size" "$time"
  awk -v b="$speed" 'BEGIN{
    if (b <= 0) { print "  (失败)"; exit }
    printf "  %.1f Mbps  (%.1f MB/s)\n", b*8/1000000, b/1048576
  }'
}

mode_cdn() {
  echo "================================================================"
  echo " 模式二：VPS → 目标 CDN（每个目标最多 ${DURATION} 秒）"
  echo "================================================================"
  for u in "$@"; do test_url "$u"; done
}

# ---------------------------------------------------------------- 入口

need curl || { echo "需要 curl" >&2; exit 1; }

if [ $# -gt 0 ]; then
  mode_cdn "$@"
else
  mode_bandwidth
fi
