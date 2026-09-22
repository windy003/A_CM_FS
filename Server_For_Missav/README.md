# Server_For_Missav

把「指定域名」的流量经由**自己的 VPS**出去，而不是直接走机场节点。

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

# 模式二：VPS → 目标 CDN。URL 从 app 的连接列表里抓（见第四节）
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

2. **设置链路**：进 app 的「设置 → 指定域名走自己的 VPS」：

   | 项目 | 填什么 |
   |---|---|
   | 出口节点 | `MyVPS` |
   | 前置跳 | 机场的**自动选择组**（如 `♻️ 自动选择`） |
   | 域名 | 一行一个 |

   **前置跳一定要选「组」而不是具体节点。** 填具体节点，那个节点被封整条链就断了；
   填自动选择组，组会自己测速切换，前置跳跟着自动换，链路自愈。

3. 保存后 app 会把设置注入 config.yaml，订阅更新后也会自动重新注入，不用管。

---

## 四、域名填什么（这一步最关键）

**只填 `missav.ws` 基本没用。** 网页 HTML 在主域名上，但视频分片是独立 CDN 域名。
只匹配主域名，你换来的只是网页打开快一点，**视频流量还是走原来的路**，
大带宽完全体现不出来。

正确做法：

1. 手机上开着 VPN，播一段视频
2. 打开 app 的**连接列表**，找持续有大流量（几十 MB 往上）的那几条
3. 记下它们的域名，填进去

填写规则：

```
missav.ws              → 后缀匹配，同时覆盖 missav.ws 和 *.missav.ws
keyword:surrit         → 关键词匹配，覆盖一切含该词的域名
```

抓 CDN 时**关键词往往比后缀好用**，因为这类站的 CDN 经常是
`abc-1.example.com` / `abc-2.example.net` 这种换着来的一堆域名，
用关键词一条就全覆盖了。

---

## 五、UDP / QUIC 怎么办

视频 CDN 现在大量用 HTTP/3（QUIC，UDP 443）。链路里有两个坎：

**坎一：app 自己的 QUIC 规则会抢流量。**
app 在规则顶部注入了一条「境外 UDP 443 → 兜底代理组」（见 `QuicRuleManager.kt`）。
本功能注入的域名规则排在它**上面**，所以会先命中，不受影响——这是代码里保证的，
你不用管，但改动注入顺序时要注意。

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
