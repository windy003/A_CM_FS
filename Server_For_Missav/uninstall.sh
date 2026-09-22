#!/usr/bin/env bash
#
# 卸载 Server_For_Missav 装的所有东西。
#
# 默认保留内核调优（那些参数对任何用途都无害）；要一并还原加 PURGE_TUNE=1。
#
set -euo pipefail

log() { printf '\033[32m[+]\033[0m %s\n' "$*"; }

[ "$(id -u)" -eq 0 ] || { printf '请用 root 运行\n' >&2; exit 1; }

if systemctl list-unit-files 2>/dev/null | grep -q '^ss-missav.service'; then
  systemctl disable --now ss-missav >/dev/null 2>&1 || true
  rm -f /etc/systemd/system/ss-missav.service
  systemctl daemon-reload
  log "已停止并移除 ss-missav 服务"
fi

rm -f /usr/local/bin/ssserver && log "已删除 /usr/local/bin/ssserver"
rm -rf /etc/shadowsocks-rust && log "已删除 /etc/shadowsocks-rust（含密码）"

if [ "${PURGE_TUNE:-0}" = "1" ]; then
  rm -f /etc/sysctl.d/99-missav-tune.conf /etc/security/limits.d/99-missav.conf
  sysctl --system >/dev/null 2>&1 || true
  log "已还原内核调优"
else
  log "保留了内核调优（要删加 PURGE_TUNE=1）"
fi

log "完成。防火墙放行的端口规则未自动撤销，需要的话请手动清理。"
