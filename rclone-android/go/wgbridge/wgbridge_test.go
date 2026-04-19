package wgbridge

import (
	"strings"
	"testing"
)

const validConfig = `
[Interface]
PrivateKey = cEJxGEJjYIL8XHiCnVAhYQGzNuSrt7ZbQXGj4Bgn7nY=
Address = 10.9.0.2/32
DNS = 1.1.1.1, 9.9.9.9

[Peer]
PublicKey = aV0TdsXGYmZBh2RM0V+g3xNkZwFxVzS3a0VqL2EXfDc=
Endpoint = vpn.example.com:51820
AllowedIPs = 0.0.0.0/0, ::/0
PersistentKeepalive = 25
`

func TestParseConfigExtractsInterfaceAndPeer(t *testing.T) {
	p, err := parseConfig(validConfig)
	if err != nil {
		t.Fatalf("expected success, got %v", err)
	}
	if len(p.addresses) != 1 || p.addresses[0].String() != "10.9.0.2" {
		t.Errorf("wrong addresses: %v", p.addresses)
	}
	if len(p.dns) != 2 {
		t.Errorf("wrong DNS count: %v", p.dns)
	}
	if !strings.Contains(p.uapi, "private_key=") {
		t.Errorf("uapi missing private_key: %s", p.uapi)
	}
	if !strings.Contains(p.uapi, "endpoint=vpn.example.com:51820") {
		t.Errorf("uapi missing endpoint: %s", p.uapi)
	}
	if !strings.Contains(p.uapi, "persistent_keepalive_interval=25") {
		t.Errorf("uapi missing keepalive: %s", p.uapi)
	}
	if !strings.Contains(p.uapi, "allowed_ip=0.0.0.0/0") {
		t.Errorf("uapi missing allowed_ip v4: %s", p.uapi)
	}
	if !strings.Contains(p.uapi, "allowed_ip=::/0") {
		t.Errorf("uapi missing allowed_ip v6: %s", p.uapi)
	}
}

func TestParseConfigRejectsMissingSection(t *testing.T) {
	cases := map[string]string{
		"missing interface": `[Peer]
PublicKey = aV0TdsXGYmZBh2RM0V+g3xNkZwFxVzS3a0VqL2EXfDc=
Endpoint = x:1`,
		"missing peer": `[Interface]
PrivateKey = cEJxGEJjYIL8XHiCnVAhYQGzNuSrt7ZbQXGj4Bgn7nY=
Address = 10.0.0.1/32`,
		"missing private key": `[Interface]
Address = 10.0.0.1/32

[Peer]
PublicKey = aV0TdsXGYmZBh2RM0V+g3xNkZwFxVzS3a0VqL2EXfDc=
Endpoint = x:1`,
	}
	for name, cfg := range cases {
		t.Run(name, func(t *testing.T) {
			if _, err := parseConfig(cfg); err == nil {
				t.Fatalf("expected error")
			}
		})
	}
}

func TestParseConfigUnknownKeysIgnored(t *testing.T) {
	// wg-quick configs may carry directives that aren't relevant to the
	// userspace netstack (PostUp, Table, MTU…). Don't choke on them.
	cfg := `
[Interface]
PrivateKey = cEJxGEJjYIL8XHiCnVAhYQGzNuSrt7ZbQXGj4Bgn7nY=
Address = 10.0.0.2/32
PostUp = iptables -A FORWARD -i %i -j ACCEPT
Table = off

[Peer]
PublicKey = aV0TdsXGYmZBh2RM0V+g3xNkZwFxVzS3a0VqL2EXfDc=
Endpoint = vpn.example.com:51820
AllowedIPs = 10.0.0.0/24
SomethingFutureWireGuardAdds = yes
`
	if _, err := parseConfig(cfg); err != nil {
		t.Fatalf("should ignore unknown keys, got %v", err)
	}
}

func TestParseConfigMultiplePeers(t *testing.T) {
	cfg := `
[Interface]
PrivateKey = cEJxGEJjYIL8XHiCnVAhYQGzNuSrt7ZbQXGj4Bgn7nY=
Address = 10.0.0.2/32

[Peer]
PublicKey = aV0TdsXGYmZBh2RM0V+g3xNkZwFxVzS3a0VqL2EXfDc=
Endpoint = peer1.example:51820
AllowedIPs = 10.0.0.0/24

[Peer]
PublicKey = M09vVwU4oVBI28YlYcRm7e+jZ3+xmJ9EqmR1V6lQlgk=
Endpoint = peer2.example:51820
AllowedIPs = 10.1.0.0/24
`
	p, err := parseConfig(cfg)
	if err != nil {
		t.Fatalf("expected success, got %v", err)
	}
	if !strings.Contains(p.uapi, "peer1.example:51820") ||
		!strings.Contains(p.uapi, "peer2.example:51820") {
		t.Errorf("uapi missing one of the peer endpoints: %s", p.uapi)
	}
}
