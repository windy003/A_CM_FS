package tls

import (
	"crypto/tls"
	"runtime"

	"golang.org/x/sys/cpu"
)

// aesgcmPreferred 复刻 crypto/tls 内部的同名判断：是否「本机有 AES-GCM 硬件加速，
// 且对端偏好列表里第一个已知套件就是 AES-GCM」。REALITY 用它决定用 AES-GCM 还是
// ChaCha20-Poly1305 加密 ClientHello 的 SessionId，必须与服务端(xray)的判断一致。
//
// 原先这里用 `//go:linkname aesgcmPreferred crypto/tls.aesgcmPreferred` 直接借用标准库的
// 私有函数。该函数在较新的 Go 里被改名为 isAESGCMPreferred，符号不再存在，链接期直接报
// "relocation target crypto/tls.aesgcmPreferred not defined"——**整个 aar 都编不出来**，
// 且报错位置与 REALITY 毫无关系，极难定位。故改为本地实现，不再依赖标准库私有符号。
//
// 逻辑与 go/src/crypto/tls/cipher_suites.go 的 isAESGCMPreferred 一致：
//   - 硬件支持判定照抄其 hasAESGCMHardwareSupport；
//   - 「已知套件」用 tls.CipherSuites()+tls.InsecureCipherSuites() 这两个导出 API 枚举，
//     等价于标准库内部的 cipherSuiteByID/cipherSuiteTLS13ByID 查表。
func aesgcmPreferred(ciphers []uint16) bool {
	if !hasAESGCMHardwareSupport {
		return false
	}
	for _, cID := range ciphers {
		if _, known := knownCipherSuites[cID]; known {
			return aesgcmCiphers[cID]
		}
	}
	return false
}

var (
	hasGCMAsmAMD64 = cpu.X86.HasAES && cpu.X86.HasPCLMULQDQ && cpu.X86.HasSSE41 && cpu.X86.HasSSSE3
	hasGCMAsmARM64 = cpu.ARM64.HasAES && cpu.ARM64.HasPMULL
	hasGCMAsmS390X = cpu.S390X.HasAES && cpu.S390X.HasAESCTR && cpu.S390X.HasGHASH
	hasGCMAsmPPC64 = runtime.GOARCH == "ppc64" || runtime.GOARCH == "ppc64le"

	hasAESGCMHardwareSupport = hasGCMAsmAMD64 || hasGCMAsmARM64 || hasGCMAsmS390X || hasGCMAsmPPC64
)

var aesgcmCiphers = map[uint16]bool{
	// TLS 1.2
	tls.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256:   true,
	tls.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384:   true,
	tls.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256: true,
	tls.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384: true,
	// TLS 1.3
	tls.TLS_AES_128_GCM_SHA256: true,
	tls.TLS_AES_256_GCM_SHA384: true,
}

// knownCipherSuites 对应标准库内部的套件表：只有出现在表里的 id 才算「已知套件」，
// 未知 id（如 GREASE 值）要跳过继续往后找。
var knownCipherSuites = func() map[uint16]struct{} {
	m := make(map[uint16]struct{})
	for _, s := range tls.CipherSuites() {
		m[s.ID] = struct{}{}
	}
	for _, s := range tls.InsecureCipherSuites() {
		m[s.ID] = struct{}{}
	}
	return m
}()
