// Package mobile provides APIs for Android/iOS apps
package mobile

import (
	"context"
	"encoding/json"
	"fmt"
	"net/netip"
	"os"
	"path/filepath"
	"time"

	"github.com/Dreamacro/clash/adapter"
	"github.com/Dreamacro/clash/adapter/inbound"
	"github.com/Dreamacro/clash/adapter/outboundgroup"
	C "github.com/Dreamacro/clash/constant"
	"github.com/Dreamacro/clash/hub/executor"
	"github.com/Dreamacro/clash/listener/sing"
	"github.com/Dreamacro/clash/log"
	"github.com/Dreamacro/clash/tunnel"
	"github.com/Dreamacro/clash/tunnel/statistic"

	tun "github.com/metacubex/sing-tun"
)

var (
	isRunning  bool
	homeDir    string
	tunStack   tun.Stack
	tunDevice  tun.Tun
)

// Init 初始化 Clash，设置主目录
func Init(home string) error {
	homeDir = home

	// 确保目录存在
	if err := os.MkdirAll(home, 0755); err != nil {
		return fmt.Errorf("create home dir failed: %w", err)
	}

	// 设置 Clash 的主目录
	C.SetHomeDir(home)

	return nil
}

// SetConfig 设置配置文件路径
func SetConfig(configPath string) {
	C.SetConfig(configPath)
}

// StartWithPath 使用配置文件路径启动 Clash
func StartWithPath(configPath string) error {
	if isRunning {
		return fmt.Errorf("clash is already running")
	}

	cfg, err := executor.ParseWithPath(configPath)
	if err != nil {
		return fmt.Errorf("parse config failed: %w", err)
	}

	executor.ApplyConfig(cfg, true)
	isRunning = true

	return nil
}

// StartWithConfig 使用配置内容启动 Clash
func StartWithConfig(configContent string) error {
	if isRunning {
		return fmt.Errorf("clash is already running")
	}

	cfg, err := executor.ParseWithBytes([]byte(configContent))
	if err != nil {
		return fmt.Errorf("parse config failed: %w", err)
	}

	executor.ApplyConfig(cfg, true)
	isRunning = true

	return nil
}

// Stop 停止 Clash
func Stop() {
	if !isRunning {
		return
	}

	executor.Shutdown()
	isRunning = false
}

// IsRunning 返回 Clash 是否正在运行
func IsRunning() bool {
	return isRunning
}

// SetLogLevel 设置日志级别
// 可选值: debug, info, warning, error, silent
func SetLogLevel(level string) {
	logLevel := log.LogLevelMapping[level]
	log.SetLevel(logLevel)
}

// TrafficStats 流量统计结构
type TrafficStats struct {
	Up        int64 `json:"up"`
	Down      int64 `json:"down"`
	UpTotal   int64 `json:"upTotal"`
	DownTotal int64 `json:"downTotal"`
}

// GetTrafficStats 获取流量统计
func GetTrafficStats() string {
	snapshot := statistic.DefaultManager.Snapshot()
	up, down := statistic.DefaultManager.Now()
	stats := TrafficStats{
		Up:        up,
		Down:      down,
		UpTotal:   snapshot.UploadTotal,
		DownTotal: snapshot.DownloadTotal,
	}
	data, _ := json.Marshal(stats)
	return string(data)
}

// ResetTrafficStats 重置流量统计
func ResetTrafficStats() {
	statistic.DefaultManager.ResetStatistic()
}

// GetMode 获取当前模式 (Rule/Global/Direct)
func GetMode() string {
	return tunnel.Mode().String()
}

// SetMode 设置模式
// 可选值: Rule, Global, Direct
func SetMode(mode string) error {
	m, exist := tunnel.ModeMapping[mode]
	if !exist {
		return fmt.Errorf("invalid mode: %s", mode)
	}
	tunnel.SetMode(m)
	return nil
}

// VerifyConfig 验证配置文件是否有效
func VerifyConfig(configPath string) error {
	_, err := executor.ParseWithPath(configPath)
	return err
}

// VerifyConfigContent 验证配置内容是否有效
func VerifyConfigContent(configContent string) error {
	_, err := executor.ParseWithBytes([]byte(configContent))
	return err
}

// GetConfigDir 获取配置目录
func GetConfigDir() string {
	return filepath.Dir(C.Path.Config())
}

// ProxyInfo 代理信息
type ProxyInfo struct {
	Name  string `json:"name"`
	Type  string `json:"type"`
	Alive bool   `json:"alive"`
}

// GetProxies 获取所有代理信息 (JSON 格式)
func GetProxies() string {
	proxies := tunnel.Proxies()
	result := make(map[string]ProxyInfo)

	for name, proxy := range proxies {
		result[name] = ProxyInfo{
			Name:  proxy.Name(),
			Type:  proxy.Type().String(),
			Alive: proxy.Alive(),
		}
	}

	data, _ := json.Marshal(result)
	return string(data)
}

// ProviderInfo 代理提供者信息
type ProviderInfo struct {
	Name    string   `json:"name"`
	Type    string   `json:"type"`
	Proxies []string `json:"proxies"`
}

// GetProxyProviders 获取代理提供者信息 (JSON 格式)
func GetProxyProviders() string {
	providers := tunnel.Providers()
	result := make(map[string]ProviderInfo)

	for name, provider := range providers {
		proxies := provider.Proxies()
		proxyNames := make([]string, 0, len(proxies))
		for _, p := range proxies {
			proxyNames = append(proxyNames, p.Name())
		}
		result[name] = ProviderInfo{
			Name:    name,
			Type:    provider.VehicleType().String(),
			Proxies: proxyNames,
		}
	}

	data, _ := json.Marshal(result)
	return string(data)
}

// SelectProxy 在代理组中选择指定代理
func SelectProxy(group, proxyName string) error {
	proxies := tunnel.Proxies()
	p, exist := proxies[group]
	if !exist {
		return fmt.Errorf("proxy group %s not found", group)
	}

	adp, ok := p.(*adapter.Proxy)
	if !ok {
		return fmt.Errorf("proxy group %s is not an adapter proxy", group)
	}

	selector, ok := adp.ProxyAdapter.(outboundgroup.SelectAble)
	if !ok {
		return fmt.Errorf("proxy group %s is not selectable", group)
	}

	return selector.Set(proxyName)
}

// GetSelectedProxy 获取代理组当前选中的代理名称
func GetSelectedProxy(group string) string {
	proxies := tunnel.Proxies()
	p, exist := proxies[group]
	if !exist {
		return ""
	}

	adp, ok := p.(*adapter.Proxy)
	if !ok {
		return ""
	}

	// 尝试获取当前选中的代理
	if nowProxy, ok := adp.ProxyAdapter.(interface{ Now() string }); ok {
		return nowProxy.Now()
	}

	return ""
}

// ReloadConfig 重新加载配置
func ReloadConfig() error {
	configPath := C.Path.Config()
	cfg, err := executor.ParseWithPath(configPath)
	if err != nil {
		return err
	}

	executor.ApplyConfig(cfg, true)
	return nil
}

// GetVersion 获取 Clash 版本
func GetVersion() string {
	return C.Version
}

// GeneralConfig 通用配置结构
type GeneralConfig struct {
	Port      int    `json:"port"`
	SocksPort int    `json:"socks-port"`
	MixedPort int    `json:"mixed-port"`
	AllowLan  bool   `json:"allow-lan"`
	Mode      string `json:"mode"`
	LogLevel  string `json:"log-level"`
}

// GetGeneral 获取通用配置
func GetGeneral() string {
	general := executor.GetGeneral()
	cfg := GeneralConfig{
		Port:      general.Port,
		SocksPort: general.SocksPort,
		MixedPort: general.MixedPort,
		AllowLan:  general.AllowLan,
		Mode:      tunnel.Mode().String(),
		LogLevel:  log.Level().String(),
	}
	data, _ := json.Marshal(cfg)
	return string(data)
}

// TestDelay 测试代理延迟 (毫秒)
// url: 测试用的 URL，如 "https://www.gstatic.com/generate_204"
// timeout: 超时时间（毫秒）
func TestDelay(proxyName string, url string, timeout int) (int, error) {
	proxies := tunnel.Proxies()
	proxy, exist := proxies[proxyName]
	if !exist {
		return 0, fmt.Errorf("proxy %s not found", proxyName)
	}

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeout)*time.Millisecond)
	defer cancel()

	delay, err := proxy.URLTest(ctx, url, nil, C.DropHistory)
	if err != nil {
		return 0, err
	}

	return int(delay), nil
}

// CloseAllConnections 关闭所有连接
func CloseAllConnections() {
	statistic.DefaultManager.Range(func(c statistic.Tracker) bool {
		_ = c.Close()
		return true
	})
}

// GetConnections 获取当前连接信息
func GetConnections() string {
	snapshot := statistic.DefaultManager.Snapshot()
	data, _ := json.Marshal(snapshot)
	return string(data)
}

// CloseConnection 关闭指定连接
func CloseConnection(id string) bool {
	c := statistic.DefaultManager.Get(id)
	if c == nil {
		return false
	}
	_ = c.Close()
	return true
}

// UpdateProxyProvider 更新代理提供者
func UpdateProxyProvider(name string) error {
	providers := tunnel.Providers()
	provider, exist := providers[name]
	if !exist {
		return fmt.Errorf("provider %s not found", name)
	}

	return provider.Update()
}

// HealthCheckProxyProvider 健康检查代理提供者
func HealthCheckProxyProvider(name string) error {
	providers := tunnel.Providers()
	provider, exist := providers[name]
	if !exist {
		return fmt.Errorf("provider %s not found", name)
	}

	provider.HealthCheck()
	return nil
}

// StartTun 启动 TUN 设备
// fd: Android VpnService 创建的 TUN 文件描述符
// mtu: MTU 大小，通常为 9000
func StartTun(fd int, mtu int) error {
	if !isRunning {
		return fmt.Errorf("clash is not running, please start clash first")
	}

	// 设置日志级别为 debug 以便调试
	log.SetLevel(log.DEBUG)
	log.Infoln("[Mobile] Creating TUN with fd: %d, mtu: %d", fd, mtu)

	inet4Addr, _ := netip.ParsePrefix("172.19.0.1/30")

	// 直接使用 sing-tun 创建 TUN 设备，不使用 NetworkUpdateMonitor
	tunOptions := tun.Options{
		Name:           "tun0",
		MTU:            uint32(mtu),
		Inet4Address:   []netip.Prefix{inet4Addr},
		AutoRoute:      false,
		FileDescriptor: fd,
	}

	var err error
	tunDevice, err = tun.New(tunOptions)
	if err != nil {
		log.Errorln("[Mobile] Failed to create TUN device: %v", err)
		return fmt.Errorf("create TUN device failed: %w", err)
	}
	log.Infoln("[Mobile] TUN device created successfully")

	// 创建 TUN handler
	handler := &sing.ListenerHandler{
		Tunnel: tunnel.Tunnel,
		Type:   C.TUN,
		Additions: []inbound.Addition{
			inbound.WithInName("DEFAULT-TUN"),
			inbound.WithSpecialRules(""),
		},
		UDPTimeout: time.Second * 60,
	}

	// 创建 TUN stack
	stackOptions := tun.StackOptions{
		Context:      context.Background(),
		Tun:          tunDevice,
		MTU:          tunOptions.MTU,
		Name:         tunOptions.Name,
		Inet4Address: tunOptions.Inet4Address,
		UDPTimeout:   60,
		Handler:      handler,
		Logger:       log.SingLogger,
	}

	tunStack, err = tun.NewStack("gvisor", stackOptions)
	if err != nil {
		tunDevice.Close()
		tunDevice = nil
		log.Errorln("[Mobile] Failed to create TUN stack: %v", err)
		return fmt.Errorf("create TUN stack failed: %w", err)
	}

	err = tunStack.Start()
	if err != nil {
		tunStack.Close()
		tunDevice.Close()
		tunStack = nil
		tunDevice = nil
		log.Errorln("[Mobile] Failed to start TUN stack: %v", err)
		return fmt.Errorf("start TUN stack failed: %w", err)
	}

	log.Infoln("[Mobile] TUN started successfully")
	return nil
}

// StopTun 停止 TUN 设备
func StopTun() {
	if tunStack != nil {
		tunStack.Close()
		tunStack = nil
	}
	if tunDevice != nil {
		tunDevice.Close()
		tunDevice = nil
	}
	log.Infoln("[Mobile] TUN stopped")
}
