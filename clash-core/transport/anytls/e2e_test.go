package anytls

import (
	"context"
	"net"
	"os"
	"strconv"
	"testing"
	"time"

	"github.com/Dreamacro/clash/transport/vmess"

	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/common/uot"
)

// 对着真实 anytls 服务器的端到端测试。默认跳过，需要时指定服务器再跑：
//
//	ANYTLS_TEST_SERVER=127.0.0.1:18443 ANYTLS_TEST_PASSWORD=testpass go test ./transport/anytls/ -v
//
// 本地起一个测试服务器（anytls 官方实现）：
//
//	go install github.com/anytls/anytls-go/cmd/server@latest
//	server -l 127.0.0.1:18443 -p testpass
//
// 覆盖：认证握手(含 padding0)、会话复用、分包/填充、udp-over-tcp v2、错误密码被拒。
func serverFromEnv(t *testing.T) (host string, port uint16, password string) {
	t.Helper()
	addr := os.Getenv("ANYTLS_TEST_SERVER")
	if addr == "" {
		t.Skip("设置 ANYTLS_TEST_SERVER=host:port 与 ANYTLS_TEST_PASSWORD 后运行")
	}
	h, p, err := net.SplitHostPort(addr)
	if err != nil {
		t.Fatalf("ANYTLS_TEST_SERVER 格式应为 host:port: %v", err)
	}
	n, err := strconv.ParseUint(p, 10, 16)
	if err != nil {
		t.Fatalf("端口非法: %v", err)
	}
	return h, uint16(n), os.Getenv("ANYTLS_TEST_PASSWORD")
}

// 最小 N.Dialer：绕开 component/dialer(tfo-go)，用标准库直连
type plainDialer struct{}

func (plainDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	var d net.Dialer
	return d.DialContext(ctx, network, destination.String())
}

func (plainDialer) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	return net.ListenPacket("udp", "")
}

func newClient(t *testing.T, password string) *Client {
	t.Helper()
	srvHost, srvPort, _ := serverFromEnv(t)
	c := NewClient(context.Background(), ClientConfig{
		Password: password,
		Server:   M.ParseSocksaddrHostPort(srvHost, srvPort),
		Dialer:   plainDialer{},
		TLSConfig: &vmess.TLSConfig{
			Host:           srvHost,
			SkipCertVerify: true,
		},
	})
	t.Cleanup(func() { c.Close() })
	return c
}

func startTCPEcho(t *testing.T) uint16 {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				buf := make([]byte, 4096)
				for {
					n, err := c.Read(buf)
					if n > 0 {
						c.Write(buf[:n])
					}
					if err != nil {
						return
					}
				}
			}(c)
		}
	}()
	return uint16(ln.Addr().(*net.TCPAddr).Port)
}

func startUDPEcho(t *testing.T) uint16 {
	t.Helper()
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { pc.Close() })
	go func() {
		buf := make([]byte, 4096)
		for {
			n, addr, err := pc.ReadFrom(buf)
			if n > 0 {
				pc.WriteTo(buf[:n], addr)
			}
			if err != nil {
				return
			}
		}
	}()
	return uint16(pc.LocalAddr().(*net.UDPAddr).Port)
}

func TestTCPEcho(t *testing.T) {
	port := startTCPEcho(t)
	_, _, pass := serverFromEnv(t)
	c := newClient(t, pass)

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	conn, err := c.CreateProxy(ctx, M.ParseSocksaddrHostPort("127.0.0.1", port))
	if err != nil {
		t.Fatalf("CreateProxy: %v", err)
	}
	defer conn.Close()

	payload := []byte("hello anytls over clash-core")
	conn.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := conn.Write(payload); err != nil {
		t.Fatalf("write: %v", err)
	}
	buf := make([]byte, len(payload))
	if _, err := conn.Read(buf); err != nil {
		t.Fatalf("read: %v", err)
	}
	if string(buf) != string(payload) {
		t.Fatalf("echo mismatch: got %q want %q", buf, payload)
	}
	t.Logf("TCP echo OK: %q", buf)
}

// 协议要求客户端实现会话复用：连续开关多条 stream 都要通
func TestMultipleStreamsReuseSession(t *testing.T) {
	port := startTCPEcho(t)
	_, _, pass := serverFromEnv(t)
	c := newClient(t, pass)

	for i := 0; i < 8; i++ {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		conn, err := c.CreateProxy(ctx, M.ParseSocksaddrHostPort("127.0.0.1", port))
		if err != nil {
			cancel()
			t.Fatalf("stream %d: %v", i, err)
		}
		conn.SetDeadline(time.Now().Add(5 * time.Second))
		msg := []byte{byte('a' + i)}
		if _, err := conn.Write(msg); err != nil {
			conn.Close()
			cancel()
			t.Fatalf("stream %d write: %v", i, err)
		}
		buf := make([]byte, 1)
		if _, err := conn.Read(buf); err != nil {
			conn.Close()
			cancel()
			t.Fatalf("stream %d read: %v", i, err)
		}
		if buf[0] != msg[0] {
			conn.Close()
			cancel()
			t.Fatalf("stream %d echo mismatch", i)
		}
		conn.Close()
		cancel()
	}
	t.Log("8 条 stream 顺序收发 OK (会话复用)")
}

// 大包：跨越 padding scheme 的 stop 边界，验证分包/填充逻辑
func TestLargePayload(t *testing.T) {
	port := startTCPEcho(t)
	_, _, pass := serverFromEnv(t)
	c := newClient(t, pass)

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	conn, err := c.CreateProxy(ctx, M.ParseSocksaddrHostPort("127.0.0.1", port))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(15 * time.Second))

	payload := make([]byte, 256*1024)
	for i := range payload {
		payload[i] = byte(i)
	}
	go func() { conn.Write(payload) }()

	got := make([]byte, 0, len(payload))
	buf := make([]byte, 32*1024)
	for len(got) < len(payload) {
		n, err := conn.Read(buf)
		got = append(got, buf[:n]...)
		if err != nil {
			t.Fatalf("read after %d bytes: %v", len(got), err)
		}
	}
	for i := range payload {
		if got[i] != payload[i] {
			t.Fatalf("byte %d mismatch", i)
		}
	}
	t.Logf("256KB 回环一致 OK")
}

func TestUDPOverTCP(t *testing.T) {
	port := startUDPEcho(t)
	_, _, pass := serverFromEnv(t)
	c := newClient(t, pass)

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	stream, err := c.CreateProxy(ctx, uot.RequestDestination(uot.Version))
	if err != nil {
		t.Fatalf("CreateProxy(uot): %v", err)
	}
	dst := M.ParseSocksaddrHostPort("127.0.0.1", port)
	pc := uot.NewLazyConn(stream, uot.Request{Destination: dst})
	defer pc.Close()

	payload := []byte("hello udp over tcp")
	pc.SetDeadline(time.Now().Add(8 * time.Second))
	if _, err := pc.WriteTo(payload, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: int(port)}); err != nil {
		t.Fatalf("WriteTo: %v", err)
	}
	buf := make([]byte, 4096)
	n, _, err := pc.ReadFrom(buf)
	if err != nil {
		t.Fatalf("ReadFrom: %v", err)
	}
	if string(buf[:n]) != string(payload) {
		t.Fatalf("udp echo mismatch: got %q", buf[:n])
	}
	t.Logf("UDP(uot v2) echo OK: %q", buf[:n])
}

func TestWrongPasswordRejected(t *testing.T) {
	port := startTCPEcho(t)
	c := newClient(t, "wrong-password")

	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	conn, err := c.CreateProxy(ctx, M.ParseSocksaddrHostPort("127.0.0.1", port))
	if err == nil {
		conn.SetDeadline(time.Now().Add(3 * time.Second))
		if _, werr := conn.Write([]byte("x")); werr == nil {
			buf := make([]byte, 1)
			if _, rerr := conn.Read(buf); rerr == nil {
				conn.Close()
				t.Fatal("密码错误却能正常收发")
			}
		}
		conn.Close()
	}
	t.Logf("错误密码被拒: err=%v", err)
}
