// Package wgbridge provides a minimal Go bridge over wireguard-go and its
// built-in gVisor netstack, exposed via gomobile for Haven's per-app
// WireGuard feature (#102).
//
// The shape of the public API is constrained by gomobile's binding rules:
//   - Exported types must be structs, not interfaces.
//   - Exported methods can only take/return: primitives, strings, []byte,
//     error, pointers to other exported struct types.
//   - Read-style methods return a fresh []byte instead of filling a caller
//     buffer because gomobile copies []byte parameters across the JNI
//     boundary and the caller's buffer wouldn't be updated.
//
// A tunnel is brought up with [StartTunnel], parsing a wg-quick style
// config (subset: [Interface]: PrivateKey + Address, [Peer]: PublicKey +
// Endpoint + AllowedIPs, optional PresharedKey + PersistentKeepalive).
// Callers then [TunnelHandle.Dial] to obtain a [Conn] whose Read/Write
// methods go through the userspace netstack, not the kernel socket layer.
package wgbridge

import (
	"context"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

// TunnelHandle is a live WireGuard tunnel backed by a userspace TUN + the
// gVisor netstack. Not safe for concurrent Close, but Dial is safe to call
// concurrently.
type TunnelHandle struct {
	dev    *device.Device
	tnet   *netstack.Net
	closed bool
	mu     sync.Mutex
}

// Conn is a TCP connection through a [TunnelHandle]. Bound to gomobile;
// Read returns a fresh byte slice of up to size bytes because gomobile
// passes []byte arguments by copy.
type Conn struct {
	c net.Conn
}

// StartTunnel parses a wg-quick style config and brings a tunnel up. The
// returned handle must be closed via [TunnelHandle.Close]. Callers get a
// clear error message on parse / handshake failure.
func StartTunnel(configText string) (*TunnelHandle, error) {
	parsed, err := parseConfig(configText)
	if err != nil {
		return nil, fmt.Errorf("parse config: %w", err)
	}

	tun, tnet, err := netstack.CreateNetTUN(parsed.addresses, parsed.dns, 1420)
	if err != nil {
		return nil, fmt.Errorf("create netstack TUN: %w", err)
	}

	dev := device.NewDevice(tun, conn.NewDefaultBind(), device.NewLogger(
		device.LogLevelError,
		"haven-wg: ",
	))
	if err := dev.IpcSet(parsed.uapi); err != nil {
		dev.Close()
		return nil, fmt.Errorf("wireguard IpcSet: %w", err)
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		return nil, fmt.Errorf("wireguard Up: %w", err)
	}

	return &TunnelHandle{dev: dev, tnet: tnet}, nil
}

// Dial opens a TCP connection through the tunnel. timeoutMs <= 0 uses a
// generous default so callers don't have to special-case "no timeout".
func (t *TunnelHandle) Dial(host string, port int, timeoutMs int) (*Conn, error) {
	t.mu.Lock()
	if t.closed {
		t.mu.Unlock()
		return nil, errors.New("tunnel closed")
	}
	tnet := t.tnet
	t.mu.Unlock()

	if timeoutMs <= 0 {
		timeoutMs = 30_000
	}
	ctx, cancel := context.WithTimeout(
		context.Background(),
		time.Duration(timeoutMs)*time.Millisecond,
	)
	defer cancel()

	c, err := tnet.DialContext(ctx, "tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		return nil, fmt.Errorf("dial %s:%d: %w", host, port, err)
	}
	return &Conn{c: c}, nil
}

// Close tears down the tunnel. Idempotent.
func (t *TunnelHandle) Close() {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.closed {
		return
	}
	t.closed = true
	if t.dev != nil {
		t.dev.Close()
		t.dev = nil
	}
}

// Read returns up to size bytes from the connection. A nil slice with a
// non-nil error signals EOF or a transport failure; callers translate to
// their platform's "end of stream" convention (e.g. -1 in Java).
func (c *Conn) Read(size int) ([]byte, error) {
	if size <= 0 {
		size = 4096
	}
	buf := make([]byte, size)
	n, err := c.c.Read(buf)
	if n > 0 {
		return buf[:n], err
	}
	return nil, err
}

// Write writes all of data. gomobile copies the slice across the JNI
// boundary, so we don't need to worry about the caller mutating the
// underlying array before we're done.
func (c *Conn) Write(data []byte) error {
	_, err := c.c.Write(data)
	return err
}

// Close closes the connection. Idempotent.
func (c *Conn) Close() error {
	return c.c.Close()
}

// --- config parsing --------------------------------------------------------

type parsedConfig struct {
	addresses []netip.Addr
	dns       []netip.Addr
	uapi      string // UAPI-format text for device.IpcSet
}

// parseConfig converts the wg-quick INI subset we support into the
// parsed form needed to bring up a netstack tunnel. We only look at the
// fields we actually use; unknown keys are ignored rather than rejected
// so users can paste a full wg-quick config verbatim.
func parseConfig(text string) (*parsedConfig, error) {
	lines := strings.Split(text, "\n")
	var (
		section       string
		interfaceSeen bool
		privateKeyB64 string
		addresses     []netip.Addr
		dns           []netip.Addr
		peerBlocks    []map[string]string
		current       map[string]string
	)

	for _, raw := range lines {
		line := strings.TrimSpace(raw)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		if strings.HasPrefix(line, "[") && strings.HasSuffix(line, "]") {
			section = strings.ToLower(strings.Trim(line, "[]"))
			if section == "peer" {
				current = map[string]string{}
				peerBlocks = append(peerBlocks, current)
			} else if section == "interface" {
				interfaceSeen = true
				current = nil
			}
			continue
		}
		eq := strings.Index(line, "=")
		if eq < 0 {
			continue
		}
		key := strings.ToLower(strings.TrimSpace(line[:eq]))
		val := strings.TrimSpace(line[eq+1:])
		switch section {
		case "interface":
			switch key {
			case "privatekey":
				privateKeyB64 = val
			case "address":
				for _, a := range strings.Split(val, ",") {
					a = strings.TrimSpace(a)
					ip, err := parseAddrCIDR(a)
					if err != nil {
						return nil, fmt.Errorf("interface address %q: %w", a, err)
					}
					addresses = append(addresses, ip)
				}
			case "dns":
				for _, a := range strings.Split(val, ",") {
					a = strings.TrimSpace(a)
					if a == "" {
						continue
					}
					ip, err := netip.ParseAddr(a)
					if err != nil {
						return nil, fmt.Errorf("interface DNS %q: %w", a, err)
					}
					dns = append(dns, ip)
				}
			}
		case "peer":
			if current != nil {
				current[key] = val
			}
		}
	}

	if !interfaceSeen {
		return nil, errors.New("missing [Interface] section")
	}
	if privateKeyB64 == "" {
		return nil, errors.New("missing Interface.PrivateKey")
	}
	if len(addresses) == 0 {
		return nil, errors.New("missing Interface.Address")
	}
	if len(peerBlocks) == 0 {
		return nil, errors.New("missing [Peer] section")
	}

	privateKeyHex, err := base64ToHex(privateKeyB64)
	if err != nil {
		return nil, fmt.Errorf("decode Interface.PrivateKey: %w", err)
	}

	var uapi strings.Builder
	uapi.WriteString("private_key=" + privateKeyHex + "\n")
	for i, peer := range peerBlocks {
		pubB64 := peer["publickey"]
		if pubB64 == "" {
			return nil, fmt.Errorf("peer %d: missing PublicKey", i)
		}
		endpoint := peer["endpoint"]
		if endpoint == "" {
			return nil, fmt.Errorf("peer %d: missing Endpoint", i)
		}
		pubHex, err := base64ToHex(pubB64)
		if err != nil {
			return nil, fmt.Errorf("peer %d PublicKey: %w", i, err)
		}
		uapi.WriteString("public_key=" + pubHex + "\n")
		uapi.WriteString("endpoint=" + endpoint + "\n")
		if psk := peer["presharedkey"]; psk != "" {
			pskHex, err := base64ToHex(psk)
			if err != nil {
				return nil, fmt.Errorf("peer %d PresharedKey: %w", i, err)
			}
			uapi.WriteString("preshared_key=" + pskHex + "\n")
		}
		if ka := peer["persistentkeepalive"]; ka != "" {
			if _, err := strconv.Atoi(ka); err != nil {
				return nil, fmt.Errorf("peer %d PersistentKeepalive: %w", i, err)
			}
			uapi.WriteString("persistent_keepalive_interval=" + ka + "\n")
		}
		allowed := peer["allowedips"]
		if allowed == "" {
			// Sane default — route everything through the tunnel. Most
			// wg-quick configs set this explicitly anyway.
			allowed = "0.0.0.0/0, ::/0"
		}
		for _, a := range strings.Split(allowed, ",") {
			a = strings.TrimSpace(a)
			if a == "" {
				continue
			}
			uapi.WriteString("allowed_ip=" + a + "\n")
		}
	}

	return &parsedConfig{
		addresses: addresses,
		dns:       dns,
		uapi:      uapi.String(),
	}, nil
}

// parseAddrCIDR accepts "10.0.0.2" or "10.0.0.2/32" and returns the address
// portion. The netstack only cares about the host IP; the /mask is informational.
func parseAddrCIDR(s string) (netip.Addr, error) {
	if slash := strings.Index(s, "/"); slash >= 0 {
		prefix, err := netip.ParsePrefix(s)
		if err != nil {
			return netip.Addr{}, err
		}
		return prefix.Addr(), nil
	}
	return netip.ParseAddr(s)
}

func base64ToHex(b64 string) (string, error) {
	bytes, err := base64.StdEncoding.DecodeString(b64)
	if err != nil {
		return "", err
	}
	return hex.EncodeToString(bytes), nil
}
