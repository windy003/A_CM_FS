package anytls

import (
	"context"
	"testing"
	"time"

	"github.com/Dreamacro/clash/transport/vmess"
	M "github.com/sagernet/sing/common/metadata"
)

// 每次都新建 client(=新建会话)，量化「冷启动一次代理连接」的耗时
func TestColdStartBaseline(t *testing.T) {
	for i := 0; i < 10; i++ {
		start := time.Now()
		c := NewClient(context.Background(), ClientConfig{
			Password:  "d4c5c395-6c79-47c6-8792-ff750431a1d5",
			Server:    M.ParseSocksaddrHostPort("yz.myzxdy.top", 40101), // 香港11
			Dialer:    plainDialer{},
			TLSConfig: &vmess.TLSConfig{Host: "gateway.icloud.com", SkipCertVerify: true},
		})
		ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
		conn, err := c.CreateProxy(ctx, M.ParseSocksaddrHostPort("www.google.com", 443))
		if err != nil {
			t.Logf("第 %2d 次: 建流失败 %v (%v)", i+1, time.Since(start).Round(time.Millisecond), err)
			cancel(); c.Close(); continue
		}
		conn.SetDeadline(time.Now().Add(10 * time.Second))
		tc, err := vmess.StreamTLSConn(ctx, conn, &vmess.TLSConfig{Host: "www.google.com"})
		d := time.Since(start).Round(time.Millisecond)
		if err != nil {
			t.Logf("第 %2d 次: TLS 失败 %v (%v)", i+1, d, err)
		} else {
			t.Logf("第 %2d 次: OK %v", i+1, d)
			tc.Close()
		}
		conn.Close(); cancel(); c.Close()
		time.Sleep(500 * time.Millisecond)
	}
}
