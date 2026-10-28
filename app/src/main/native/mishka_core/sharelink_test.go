package main

import (
	"encoding/base64"
	"strings"
	"testing"
)

func mustConvert(t *testing.T, raw string) string {
	t.Helper()
	out, ok := convertShareLinkSubscription([]byte(raw))
	if !ok {
		t.Fatalf("expected conversion to succeed for %q", raw)
	}
	return string(out)
}

func TestConvertVLESSRealityAndWS(t *testing.T) {
	raw := "vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?security=reality&encryption=none&pbk=publickey123&sid=ab12&spx=%2F&sni=www.microsoft.com&fp=chrome&type=tcp&flow=xtls-rprx-vision#Reality%20Node\n" +
		"vless://b831381d-6324-4d53-ad4f-8cda48b30811@ws.example.com:8443?security=tls&type=ws&host=cdn.example.com&path=%2Fws-path&sni=ws.example.com#WS%20Node"
	out := mustConvert(t, raw)
	for _, want := range []string{
		"type: vless", "public-key: publickey123", "short-id: ab12",
		"flow: xtls-rprx-vision", "name: Reality Node", "name: WS Node",
		"ws-opts:", "MATCH,Sakura",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("converted YAML missing %q\n---\n%s", want, out)
		}
	}
}

func TestConvertBase64SubscriptionBlob(t *testing.T) {
	inner := "trojan://p4ssw0rd@trojan.example.com:443?sni=trojan.example.com&allowInsecure=1#TrojanOne\n" +
		"hysteria2://authpass@hy2.example.com:443?sni=hy2.example.com&insecure=1&obfs=salamander&obfs-password=obfspw#HY2"
	blob := base64.StdEncoding.EncodeToString([]byte(inner))
	out := mustConvert(t, blob)
	for _, want := range []string{
		"type: trojan", "password: p4ssw0rd", "skip-cert-verify: true",
		"type: hysteria2", "obfs-password: obfspw",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("converted YAML missing %q\n---\n%s", want, out)
		}
	}
}

func TestConvertVMessAndSS(t *testing.T) {
	vmessJSON := `{"v":"2","ps":"VMess 节点","add":"vm.example.com","port":"443","id":"b831381d-6324-4d53-ad4f-8cda48b30811","aid":"0","scy":"auto","net":"ws","type":"none","host":"cdn.example.com","path":"/ray","tls":"tls","sni":"vm.example.com"}`
	vmess := "vmess://" + base64.StdEncoding.EncodeToString([]byte(vmessJSON))
	// SIP002: userinfo = base64url(method:password)
	sip002 := "ss://" + base64.RawURLEncoding.EncodeToString([]byte("aes-256-gcm:sspassword")) + "@ss.example.com:8388#SSNode"
	out := mustConvert(t, vmess+"\n"+sip002)
	for _, want := range []string{
		"type: vmess", "name: VMess 节点", "alterId: 0",
		"type: ss", "cipher: aes-256-gcm", "password: sspassword",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("converted YAML missing %q\n---\n%s", want, out)
		}
	}
}

func TestConvertTUICAndWireGuard(t *testing.T) {
	raw := "tuic://b831381d-6324-4d53-ad4f-8cda48b30811:tuicpass@tuic.example.com:443?congestion_control=bbr&udp_relay_mode=native&alpn=h3&sni=tuic.example.com#TUIC\n" +
		"wireguard://AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8%3D@wg.example.com:51820?public-key=BBECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8%3D&address=10.0.0.2%2F32&mtu=1280#WG"
	out := mustConvert(t, raw)
	for _, want := range []string{
		"type: tuic", "congestion-controller: bbr", "udp-relay-mode: native",
		"type: wireguard", "ip: 10.0.0.2", "mtu: 1280",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("converted YAML missing %q\n---\n%s", want, out)
		}
	}
}

func TestConvertAnyTLS(t *testing.T) {
	raw := "anytls://anytlspass@any.example.com:443?sni=any.example.com&fp=chrome&insecure=1&idle_session_check_interval=30&idle_session_timeout=25&min_idle_session=4#AnyTLS%20Node"
	out := mustConvert(t, raw)
	for _, want := range []string{
		"type: anytls", "password: anytlspass", "sni: any.example.com",
		"client-fingerprint: chrome", "skip-cert-verify: true",
		"idle-session-check-interval: 30", "idle-session-timeout: 25", "min-idle-session: 4",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("converted YAML missing %q\n---\n%s", want, out)
		}
	}
}

func TestNonLinkContentNotConverted(t *testing.T) {
	yamlCfg := "proxies:\n  - name: x\n    type: ss\n    server: 1.2.3.4\n    port: 8388\n    cipher: aes-256-gcm\n    password: pw\nrules:\n  - MATCH,DIRECT\n"
	if _, ok := convertShareLinkSubscription([]byte(yamlCfg)); ok {
		t.Error("plain YAML config must not be treated as share links")
	}
	age := "-----BEGIN AGE ENCRYPTED FILE-----\nYWdlLWVuY3J5cHRpb24ub3JnL3YxCi0+IFgyNTUxOQpsYW5kb20=\n-----END AGE ENCRYPTED FILE-----\n"
	if _, ok := convertShareLinkSubscription([]byte(age)); ok {
		t.Error("age armor must not be treated as share links")
	}
	if _, ok := convertShareLinkSubscription([]byte("https://example.com/sub?token=abc")); ok {
		t.Error("a lone https URL is not a proxy share link list")
	}
	if _, ok := convertShareLinkSubscription([]byte("vless://not-a-valid-link")); ok {
		t.Error("all-broken links must fall through to the normal error path")
	}
}

func TestDuplicateNamesGetSuffixes(t *testing.T) {
	raw := "trojan://pw@a.example.com:443#Same\ntrojan://pw@b.example.com:443#Same"
	out := mustConvert(t, raw)
	if !strings.Contains(out, "name: Same\n") && !strings.Contains(out, "name: Same ") {
		t.Errorf("expected first name kept\n---\n%s", out)
	}
	if !strings.Contains(out, "Same 2") {
		t.Errorf("expected dedup suffix 'Same 2'\n---\n%s", out)
	}
}
