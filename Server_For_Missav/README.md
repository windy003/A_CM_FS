# Server_For_Missav

把**全部流量**经由自己的 VPS 出去，而不是直接走机场节点。一个总开关，开了就全走。

```
手机 ──► 机场节点 ──► 你的 VPS ──► 目标站点
      (翻墙/保活)   (大带宽出口)
```

第一跳用机场，是因为 VPS 的 IP 可能被 GFW 封掉、直连不通；第二跳用自己的 VPS，
是为了拿到机场给不了的带宽和稳定线路。

实现靠 mihomo 内核的 `dialer-proxy`：给 VPS 节点加一行，告诉它「你自己的出站连接
要先从机场节点钻出去」。核心对应实现见 `clash-core/component/proxydialer/proxydialer.go`。

---

## 一、先做这一步：判断值不值得做

整条链路的速度是**木桶效应**：

```
最终速度 = min( 手机→机场 , 机场→VPS , VPS→目标站 , VPS 带宽上限 )
```

套了机场做第一跳，机场就一定在木桶里。所以：

- 如果机场本身只有 30Mbps，那不管 VPS 多大带宽，最终也就 30Mbps 左右
- 这条链路能改善的主要是**线路质量和稳定性**（机场→VPS→目标 可能比 机场→目标 绕得少），
  以及避开机场对某些站点的限速/劣化
- 只有在**手机能直连 VPS** 的情况下，你才能真正吃满 VPS 的大带宽（那就别套机场，
  在 app 里把「前置跳」留空即可）

先在 VPS 上把公式里属于 VPS 的那两项测清楚，心里有数再动手：

```bash
# 模式一：VPS 带宽上限。用 speedtest-cli 打最近的 Ookla 节点
bash speedtest.sh

# 模式二：VPS → 目标 CDN。URL 这么抓：手机开着 VPN 播一段视频，
#         打开 app 的连接列表，找持续有大流量（几十 MB 往上）的那条，记下它的 URL
bash speedtest.sh 'https://那个CDN域名/xxx.ts'
```

两个测的不是同一件事，别只跑模式一。VPS 标称 1Gbps、Ookla 也跑满，
但它到目标 CDN 只有 20Mbps 的情况很常见——**决定这条链路值不值得做的是模式二**。

`speedtest-cli` 没装的话脚本会自动装（优先发行版包，pip 兜底并处理 PEP 668），
实在装不上就退回 curl 粗测。

---

## 二、安装

把这个文件夹传到 VPS（或者直接把三个 .sh 内容贴过去），然后：

```bash
sudo bash install.sh
```

可选参数：

```bash
sudo PORT=8388 bash install.sh    # 指定端口，默认随机 20000-50000
sudo REGEN=1   bash install.sh    # 强制换密码（旧节点作废）
```

脚本做的事：

1. 装 `shadowsocks-rust` 的 ssserver 到 `/usr/local/bin/ssserver`
2. 生成密码，配置写到 `/etc/shadowsocks-rust/config.json`（权限 600）
3. 注册 systemd 服务 `ss-missav`，开机自启
4. 调用 `tune.sh` 做大带宽内核调优（BBR + 缓冲区放大）
5. 放行防火墙端口（ufw / firewalld）
6. **打印一段 YAML 节点配置**——这就是你要的东西

> 云服务器还要去控制台的**安全组**里放行那个端口的 **TCP 和 UDP**，
> 脚本改不了云厂商的安全组。

### 忘了节点配置怎么办

安装脚本只在末尾打印一次那段 YAML。事后想再看，不用重跑安装：

```bash
sudo bash show.sh
```

它从 `/etc/shadowsocks-rust/config.json` 读出端口/密码/加密方式，配上公网 IP
重新拼出同一个节点（自动取 IP 失败时可以 `sudo IP=1.2.3.4 bash show.sh`）。

**别为了拿配置去跑 `REGEN=1`。** 不加 `REGEN=1` 重跑 `install.sh` 也会复用旧密码、
打印同一个节点，但加了就换密码、旧节点当场作废。

真把 `config.json` 删了（跑过 `uninstall.sh`、或者 VPS 重装），密码就找不回来了——
它是安装时 `openssl rand` 现生成的，服务端这一份是唯一的。这时候要么去翻手机上的
`/sdcard/1/clashMeta_View/config.yaml`（app 的配置放在外置目录，卸载 app 都还在，
里面那条 `MyVPS` 是完整拷贝），要么重跑 `install.sh` 换新节点重新导入。

### 为什么用 Shadowsocks 2022 而不是 Hysteria2 / VLESS

这台 VPS 是链路的**第二跳**，流量由机场节点中转过来。

- hysteria2 / tuic 走 QUIC(UDP)。要求机场那一跳能可靠转发 UDP——很多机场做不到，
  或者 UDP 限速得很惨。第二跳用 UDP 协议是在给自己找麻烦。
- vless+TLS 需要域名和证书，VPS 只有一个 IP 的话还要先买域名解析。
- **ss2022 是 TCP 承载，任何机场节点都能中转，不需要域名和证书，加解密开销极低**
  （大带宽场景下 CPU 不会成为瓶颈）。

---

## 三、在 app 里配置

1. **导入节点**：把安装脚本输出的那段 YAML 复制到手机剪贴板，
   在 app 的节点页用「粘贴节点」导入。节点名默认 `MyVPS`。

2. **设置链路**：进 app 的「设置 → 全部流量走自己的 VPS」，填好这两项后打开开关：

   | 项目 | 填什么 |
   |---|---|
   | 出口节点 | `MyVPS` |
   | 前置跳 | 机场的**自动选择组**（如 `♻️ 自动选择`） |

   **前置跳一定要选「组」而不是具体节点。** 填具体节点，那个节点被封整条链就断了；
   填自动选择组，组会自己测速切换，前置跳跟着自动换，链路自愈。

   手机本来就能直连 VPS 的话，前置跳选「不使用」，单跳更快。

3. 保存后 app 会把设置注入 config.yaml，订阅更新后也会自动重新注入，不用管。

---

## 四、总开关意味着什么

开关一开，app 会在 config.yaml 的 `rules` 最顶端插一条 `MATCH,MyVPS`，
**订阅自带的分流规则全部失效**——不分网站、不分协议，一律从 VPS 出去。

早先这里是「按域名分流」，要求你自己填域名。那条路实际上走不通：网页 HTML 在主域名上，
视频分片却在独立 CDN 域名上，而且这类站的 CDN 经常是 `abc-1.example.com` /
`abc-2.example.net` 这种换着来的一堆域名。填主域名只让网页打开快一点，视频流量
还是走原来的路，大带宽完全体现不出来——表现就是「开了没用」。与其让人去猜域名，不如全量走。

代价得知道：

- **国内站点也会绕一圈 VPS**，延迟变高。介意就关掉开关；想保留国内直连的话，
  得在 `MATCH` 之前补一条 `GEOIP,CN,DIRECT`（目前代码里没有）。
- **VPS 流量账单会涨**，所有流量都过它了。按量计费的机器要留意。

局域网不受影响：`MATCH` 之上垫了四条私有网段直连（`127/8`、`10/8`、`172.16/12`、
`192.168/16`，都带 `no-resolve` 不触发 DNS），路由器后台、投屏、局域网共享照常。

关掉开关会把注入的这几行连同节点上的 `dialer-proxy` 一起撤干净，配置恢复订阅原样。

---

## 五、UDP / QUIC 怎么办

视频 CDN 现在大量用 HTTP/3（QUIC，UDP 443）。链路里有两个坎：

**坎一：app 自己的 QUIC 规则会抢流量。**
app 在规则顶部注入了一条「境外 UDP 443 → 兜底代理组」（见 `QuicRuleManager.kt`）。
总开关的 `MATCH` 排在它**上面**，所以 QUIC 也会先命中 `MATCH` 走 VPS，不受影响——
这靠的是注入顺序（`ChainRouteManager` 在 `QuicRuleManager` 之后才注入，插在更靠前的位置），
你不用管，但改动注入顺序时要当心。

**坎二：机场那一跳可能不转发 UDP。**
如果机场节点不支持 UDP，链路上的 QUIC 就走不通。表现是视频一直转圈或反复重连。

解决办法是给 VPS 节点开 `udp-over-tcp`，让 UDP 套在 TCP 里穿过机场那一跳：

```yaml
- name: "MyVPS"
  type: ss
  # ...
  udp: true
  udp-over-tcp: true      # ← 加这行
```

服务端已经是 `mode: tcp_and_udp`，不用改。代价是 QUIC 的抗丢包优势没了
（TCP 队头阻塞会回来），所以**只在确实卡的时候才加**，不卡就别加。

---

## 六、排查

```bash
sudo bash show.sh                   # 重新打印节点配置（顺带报告服务状态）
systemctl status ss-missav          # 服务是否在跑
journalctl -u ss-missav -f          # 实时日志
ss -lntup | grep ssserver           # 端口是否在监听（TCP + UDP 都该有）
sysctl net.ipv4.tcp_congestion_control   # 应该是 bbr
```

手机侧连不上时按这个顺序排：

1. **VPS 上服务活着吗** → `systemctl status ss-missav`
2. **端口通吗** → 先在别的机器上 `nc -vz <IP> <端口>`；不通就是安全组/防火墙
3. **节点单独能用吗** → 在 app 里**先不设前置跳**，直接选中 MyVPS 试试。
   能用说明节点没问题，问题在链式；不能用说明节点本身或网络不通。
4. **链式起不来** → 十有八九是前置跳的组名填错了。组名必须和订阅里**完全一致**，
   包括 emoji 和空格。app 里是下拉选的，正常不会错，除非订阅更新后组名变了。

---

## 七、卸载

```bash
sudo bash uninstall.sh                 # 保留内核调优
sudo PURGE_TUNE=1 bash uninstall.sh    # 连调优一起还原
```
