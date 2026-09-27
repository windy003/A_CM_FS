package outbound

import (
	"context"
	"errors"
	"net"
	"runtime"
	"strconv"
	"time"

	CN "github.com/Dreamacro/clash/common/net"
	"github.com/Dreamacro/clash/component/dialer"
	"github.com/Dreamacro/clash/component/proxydialer"
	"github.com/Dreamacro/clash/component/resolver"
	C "github.com/Dreamacro/clash/constant"
	"github.com/Dreamacro/clash/transport/anytls"
	"github.com/Dreamacro/clash/transport/vmess"

	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/common/uot"
)

type AnyTLS struct {
	*Base
	client *anytls.Client
	dialer proxydialer.SingDialer
	option *AnyTLSOption
}

type AnyTLSOption struct {
	BasicOption
	Name              string   `proxy:"name"`
	Server            string   `proxy:"server"`
	Port              int      `proxy:"port"`
	Password          string   `proxy:"password"`
	ALPN              []string `proxy:"alpn,omitempty"`
	SNI               string   `proxy:"sni,omitempty"`
	ClientFingerprint string   `proxy:"client-fingerprint,omitempty"`
	SkipCertVerify    bool     `proxy:"skip-cert-verify,omitempty"`
	Fingerprint       string   `proxy:"fingerprint,omitempty"`
	UDP               bool     `proxy:"udp,omitempty"`

	// 会话复用参数，留空走协议默认值（见 transport/anytls/session）
	IdleSessionCheckInterval int  `proxy:"idle-session-check-interval,omitempty"`
	IdleSessionTimeout       int  `proxy:"idle-session-timeout,omitempty"`
	MinIdleSession           int  `proxy:"min-idle-session,omitempty"`
	DisableReuse             bool `proxy:"disable-reuse,omitempty"`
}

// DialContext implements C.ProxyAdapter
func (t *AnyTLS) DialContext(ctx context.Context, metadata *C.Metadata, opts ...dialer.Option) (_ C.Conn, err error) {
	t.dialer.SetDialer(dialer.NewDialer(t.Base.DialOptions(opts...)...))
	c, err := t.client.CreateProxy(ctx, M.ParseSocksaddrHostPort(metadata.String(), metadata.DstPort))
	if err != nil {
		return nil, err
	}
	return NewConn(c, t), nil
}

// ListenPacketContext implements C.ProxyAdapter
//
// anytls 没有自己的 UDP 通道，走 sing-box 的 udp-over-tcp v2：开一条普通 stream，
// 目标写成 uot 的特殊地址，由服务端还原成 UDP。与 ss/tuic 的 uot 路径同款。
func (t *AnyTLS) ListenPacketContext(ctx context.Context, metadata *C.Metadata, opts ...dialer.Option) (_ C.PacketConn, err error) {
	// uot 用面向流的方式承载 udp，需要一个真实的 net.UDPAddr，所以域名必须先解析
	if !metadata.Resolved() {
		ip, err := resolver.ResolveIP(ctx, metadata.Host)
		if err != nil {
			return nil, errors.New("can't resolve ip")
		}
		metadata.DstIP = ip
	}

	t.dialer.SetDialer(dialer.NewDialer(t.Base.DialOptions(opts...)...))
	c, err := t.client.CreateProxy(ctx, uot.RequestDestination(uot.Version))
	if err != nil {
		return nil, err
	}

	destination := M.SocksaddrFromNet(metadata.UDPAddr())
	return newPacketConn(
		CN.NewThreadSafePacketConn(uot.NewLazyConn(c, uot.Request{Destination: destination})),
		t,
	), nil
}

// SupportUOT implements C.ProxyAdapter
func (t *AnyTLS) SupportUOT() bool {
	return true
}

// closeAnyTLS 由 finalizer 调用：节点被从配置里移除后关掉会话池，
// 否则复用的空闲 TLS 连接会一直挂着。与 Hysteria2 同款处理。
func closeAnyTLS(t *AnyTLS) {
	if t.client != nil {
		_ = t.client.Close()
	}
}

func NewAnyTLS(option AnyTLSOption) (*AnyTLS, error) {
	if option.Password == "" {
		return nil, errors.New("password is required")
	}

	addr := net.JoinHostPort(option.Server, strconv.Itoa(option.Port))
	outbound := &AnyTLS{
		Base: NewBase(BaseOption{
			Name:        option.Name,
			Addr:        addr,
			Type:        C.AnyTLS,
			UDP:         option.UDP,
			TFO:         option.TFO,
			MPTCP:       option.MPTCP,
			Interface:   option.Interface,
			RoutingMark: option.RoutingMark,
			Prefer:      C.NewDNSPrefer(option.IPVersion),
		}),
		option: &option,
	}

	// 走 NewByNameSingDialer 才能支持 dialer-proxy（链式代理：本节点的出站再经另一个代理拨出）
	singDialer := proxydialer.NewByNameSingDialer(option.DialerProxy, dialer.NewDialer())
	outbound.dialer = singDialer

	tlsConfig := &vmess.TLSConfig{
		Host:              option.SNI,
		SkipCertVerify:    option.SkipCertVerify,
		FingerPrint:       option.Fingerprint,
		ClientFingerprint: option.ClientFingerprint,
		NextProtos:        option.ALPN,
	}
	if tlsConfig.Host == "" {
		tlsConfig.Host = option.Server
	}

	outbound.client = anytls.NewClient(context.TODO(), anytls.ClientConfig{
		Password:                 option.Password,
		Server:                   M.ParseSocksaddrHostPort(option.Server, uint16(option.Port)),
		Dialer:                   singDialer,
		TLSConfig:                tlsConfig,
		IdleSessionCheckInterval: time.Duration(option.IdleSessionCheckInterval) * time.Second,
		IdleSessionTimeout:       time.Duration(option.IdleSessionTimeout) * time.Second,
		MinIdleSession:           option.MinIdleSession,
		DisableReuse:             option.DisableReuse,
	})

	runtime.SetFinalizer(outbound, closeAnyTLS)

	return outbound, nil
}
