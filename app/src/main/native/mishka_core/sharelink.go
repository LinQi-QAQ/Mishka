package main

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/url"
	"strconv"
	"strings"

	yaml "go.yaml.in/yaml/v3"
)

// 分享链接订阅转 Clash YAML。
//
// 很多面板/机场的 VLESS 等订阅下发的不是 YAML，而是分享链接列表（明文多行，
// 或整体 base64）。mihomo 的 UnmarshalRawConfig 只认 YAML，这类订阅以前直接
// 导入失败。这里在 fetch 落盘后、Unmarshal 之前识别并转换：每一非空行都必须
// 是受支持 scheme 的链接才认定，全部解析失败则不转换、走原有错误路径，避免
// 误伤 YAML / age 密文 / 普通文本。单条链接解析失败只跳过该条。
//
// 协议字段映射参考 NekoBox 等客户端的通行做法与 mihomo 的 proxy 配置结构。

var shareLinkSchemes = map[string]bool{
	"vless": true, "vmess": true, "trojan": true,
	"ss": true, "ssr": true,
	"hysteria": true, "hysteria2": true, "hy2": true, "anytls": true,
	"tuic": true, "wireguard": true, "wg": true,
	"socks5": true, "http": true,
}

// convertShareLinkSubscription 在 raw 是分享链接列表时返回等价的 Clash YAML。
func convertShareLinkSubscription(raw []byte) ([]byte, bool) {
	lines, ok := extractShareLinkLines(string(raw))
	if !ok {
		return nil, false
	}
	proxies := make([]any, 0, len(lines))
	names := make([]string, 0, len(lines))
	usedNames := map[string]bool{}
	for _, line := range lines {
		proxy, err := parseShareLink(line)
		if err != nil || proxy == nil {
			continue
		}
		name, _ := proxy["name"].(string)
		name = uniqueProxyName(name, usedNames)
		proxy["name"] = name
		proxies = append(proxies, proxy)
		names = append(names, name)
	}
	if len(proxies) == 0 {
		return nil, false
	}
	groupProxies := append(append([]string{}, names...), "DIRECT")
	root := map[string]any{
		"proxies": proxies,
		"proxy-groups": []any{
			map[string]any{
				"name":    "Sakura",
				"type":    "select",
				"proxies": groupProxies,
			},
		},
		"rules": []string{"MATCH,Sakura"},
		"dns": map[string]any{
			"enable":        true,
			"ipv6":          false,
			"enhanced-mode": "fake-ip",
			"nameserver": []string{
				"https://dns.alidns.com/dns-query",
				"https://doh.pub/dns-query",
			},
		},
	}
	out, err := yaml.Marshal(root)
	if err != nil {
		return nil, false
	}
	return out, true
}

// extractShareLinkLines 判定 text（或其 base64 解码）是否为分享链接列表，
// 并返回去空行后的链接行。要求每一非空行都以受支持 scheme 开头。
func extractShareLinkLines(text string) ([]string, bool) {
	if lines, ok := shareLinkLinesIn(text); ok {
		return lines, true
	}
	decoded, ok := decodeBase64Text(text)
	if !ok {
		return nil, false
	}
	return shareLinkLinesIn(decoded)
}

func shareLinkLinesIn(text string) ([]string, bool) {
	var lines []string
	for _, line := range strings.Split(text, "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		scheme, _, found := strings.Cut(line, "://")
		if !found || !shareLinkSchemes[strings.ToLower(scheme)] {
			return nil, false
		}
		lines = append(lines, line)
	}
	if len(lines) == 0 {
		return nil, false
	}
	return lines, true
}

// decodeBase64Text 容忍空白、缺 padding 与 URL-safe 字母表的整体 base64 解码；
// 解码结果必须是可打印文本才接受（二进制内容直接放弃）。
func decodeBase64Text(text string) (string, bool) {
	compact := strings.Map(func(r rune) rune {
		switch r {
		case ' ', '\t', '\r', '\n':
			return -1
		}
		return r
	}, strings.TrimSpace(text))
	if compact == "" {
		return "", false
	}
	for _, enc := range []*base64.Encoding{
		base64.StdEncoding, base64.URLEncoding,
		base64.RawStdEncoding, base64.RawURLEncoding,
	} {
		decoded, err := enc.DecodeString(compact)
		if err != nil {
			continue
		}
		s := string(decoded)
		if strings.ContainsRune(s, '�') {
			continue
		}
		return s, true
	}
	return "", false
}

func decodeBase64Loose(s string) ([]byte, error) {
	compact := strings.Map(func(r rune) rune {
		switch r {
		case ' ', '\t', '\r', '\n':
			return -1
		}
		return r
	}, strings.TrimSpace(s))
	var lastErr error
	for _, enc := range []*base64.Encoding{
		base64.StdEncoding, base64.URLEncoding,
		base64.RawStdEncoding, base64.RawURLEncoding,
	} {
		decoded, err := enc.DecodeString(compact)
		if err == nil {
			return decoded, nil
		}
		lastErr = err
	}
	return nil, lastErr
}

func uniqueProxyName(name string, used map[string]bool) string {
	if name == "" {
		name = "proxy"
	}
	if !used[name] {
		used[name] = true
		return name
	}
	for i := 2; ; i++ {
		candidate := fmt.Sprintf("%s %d", name, i)
		if !used[candidate] {
			used[candidate] = true
			return candidate
		}
	}
}

func parseShareLink(line string) (map[string]any, error) {
	scheme, _, _ := strings.Cut(line, "://")
	switch strings.ToLower(scheme) {
	case "vless":
		return parseVLESS(line)
	case "vmess":
		return parseVMess(line)
	case "trojan":
		return parseTrojan(line)
	case "ss":
		return parseSS(line)
	case "ssr":
		return parseSSR(line)
	case "hysteria2", "hy2":
		return parseHysteria2(line)
	case "hysteria":
		return parseHysteria(line)
	case "anytls":
		return parseAnyTLS(line)
	case "tuic":
		return parseTUIC(line)
	case "wireguard", "wg":
		return parseWireGuard(line)
	case "socks5":
		return parseSocksHTTP(line, "socks5")
	case "http":
		return parseSocksHTTP(line, "http")
	}
	return nil, fmt.Errorf("unsupported scheme %q", scheme)
}

// baseProxy 组装各协议共有的 name/type/server/port/udp。
func baseProxy(name, typ, server string, port int) map[string]any {
	return map[string]any{
		"name":   name,
		"type":   typ,
		"server": server,
		"port":   port,
		"udp":    true,
	}
}

func linkName(u *url.URL, fallback string) string {
	if u.Fragment != "" {
		return u.Fragment
	}
	return fallback
}

func parseHostPort(u *url.URL) (string, int, error) {
	host := u.Hostname()
	if host == "" {
		return "", 0, fmt.Errorf("missing host")
	}
	port, err := strconv.Atoi(u.Port())
	if err != nil || port <= 0 || port > 65535 {
		return "", 0, fmt.Errorf("invalid port %q", u.Port())
	}
	return host, port, nil
}

func queryFirst(q url.Values, keys ...string) string {
	for _, key := range keys {
		if v := q.Get(key); v != "" {
			return v
		}
	}
	return ""
}

func insecureFlag(q url.Values) bool {
	return queryFirst(q, "allowInsecure", "allow_insecure", "insecure") == "1"
}

// applyTransport 把 ws/grpc/h2/http 传输层参数按 mihomo 的 *-opts 结构写入 proxy。
// vless / trojan / vmess 共用（参数名在各生态里略有差异，这里取通行别名）。
func applyTransport(p map[string]any, network string, q url.Values, pathOverride string) {
	switch network {
	case "", "tcp":
		// 裸 TCP，无附加字段
	case "ws":
		opts := map[string]any{}
		path := queryFirst(q, "path")
		if pathOverride != "" {
			path = pathOverride
		}
		if path == "" {
			path = "/"
		}
		opts["path"] = path
		if host := queryFirst(q, "host"); host != "" {
			opts["headers"] = map[string]any{"Host": host}
		}
		p["network"] = "ws"
		p["ws-opts"] = opts
	case "grpc":
		p["network"] = "grpc"
		if svc := queryFirst(q, "serviceName", "service-name", "path"); svc != "" {
			p["grpc-opts"] = map[string]any{"grpc-service-name": svc}
		}
	case "h2":
		opts := map[string]any{}
		if host := queryFirst(q, "host"); host != "" {
			opts["host"] = strings.Split(host, ",")
		}
		if path := queryFirst(q, "path"); path != "" {
			opts["path"] = path
		}
		p["network"] = "h2"
		p["h2-opts"] = opts
	case "http":
		opts := map[string]any{}
		if path := queryFirst(q, "path"); path != "" {
			opts["path"] = []string{path}
		}
		if host := queryFirst(q, "host"); host != "" {
			opts["headers"] = map[string]any{"Host": []string{host}}
		}
		p["network"] = "http"
		p["http-opts"] = opts
	case "xhttp":
		opts := map[string]any{}
		if path := queryFirst(q, "path"); path != "" {
			opts["path"] = path
		}
		if host := queryFirst(q, "host"); host != "" {
			opts["host"] = host
		}
		if mode := queryFirst(q, "mode"); mode != "" {
			opts["mode"] = mode
		}
		p["network"] = "xhttp"
		p["xhttp-opts"] = opts
	default:
		p["network"] = network
	}
}

func parseVLESS(line string) (map[string]any, error) {
	u, err := url.Parse(line)
	if err != nil {
		return nil, err
	}
	host, port, err := parseHostPort(u)
	if err != nil {
		return nil, err
	}
	uuid := u.User.Username()
	if uuid == "" {
		return nil, fmt.Errorf("vless: missing uuid")
	}
	q := u.Query()
	p := baseProxy(linkName(u, "VLESS "+u.Host), "vless", host, port)
	p["uuid"] = uuid
	if enc := q.Get("encryption"); enc != "" && enc != "none" {
		p["encryption"] = enc
	}
	security := strings.ToLower(q.Get("security"))
	switch security {
	case "tls", "reality":
		p["tls"] = true
	}
	if security == "reality" {
		reality := map[string]any{}
		if pbk := q.Get("pbk"); pbk != "" {
			reality["public-key"] = pbk
		}
		if sid := q.Get("sid"); sid != "" {
			reality["short-id"] = sid
		}
		if spx := q.Get("spx"); spx != "" {
			reality["spider-x"] = spx
		}
		if len(reality) > 0 {
			p["reality-opts"] = reality
		}
	}
	if sni := queryFirst(q, "sni", "peer"); sni != "" {
		p["servername"] = sni
	}
	if flow := q.Get("flow"); flow != "" {
		p["flow"] = flow
	}
	if fp := q.Get("fp"); fp != "" {
		p["client-fingerprint"] = fp
	}
	if alpn := q.Get("alpn"); alpn != "" {
		p["alpn"] = strings.Split(alpn, ",")
	}
	if insecureFlag(q) {
		p["skip-cert-verify"] = true
	}
	applyTransport(p, strings.ToLower(q.Get("type")), q, "")
	return p, nil
}

func parseTrojan(line string) (map[string]any, error) {
	u, err := url.Parse(line)
	if err != nil {
		return nil, err
	}
	host, port, err := parseHostPort(u)
	if err != nil {
		return nil, err
	}
	password := u.User.Username()
	if password == "" {
		return nil, fmt.Errorf("trojan: missing password")
	}
	q := u.Query()
	p := baseProxy(linkName(u, "Trojan "+u.Host), "trojan", host, port)
	p["password"] = password
	if sni := queryFirst(q, "sni", "peer"); sni != "" {
		p["sni"] = sni
	}
	if alpn := q.Get("alpn"); alpn != "" {
		p["alpn"] = strings.Split(alpn, ",")
	}
	if fp := q.Get("fp"); fp != "" {
		p["client-fingerprint"] = fp
	}
	if insecureFlag(q) {
		p["skip-cert-verify"] = true
	}
	applyTransport(p, strings.ToLower(q.Get("type")), q, "")
	return p, nil
}

func parseVMess(line string) (map[string]any, error) {
	raw, err := decodeBase64Loose(strings.TrimPrefix(line, "vmess://"))
	if err != nil {
		// 少数实现是明文 JSON 或 URL 参数形式，这里只支持通行的 base64 JSON
		return nil, fmt.Errorf("vmess: decode: %w", err)
	}
	var fields map[string]any
	if err := json.Unmarshal(raw, &fields); err != nil {
		return nil, fmt.Errorf("vmess: json: %w", err)
	}
	str := func(key string) string {
		switch v := fields[key].(type) {
		case string:
			return v
		case float64:
			return strconv.FormatFloat(v, 'f', -1, 64)
		}
		return ""
	}
	intOf := func(key string) int {
		n, _ := strconv.Atoi(str(key))
		return n
	}
	host := str("add")
	port := intOf("port")
	if host == "" || port <= 0 {
		return nil, fmt.Errorf("vmess: missing add/port")
	}
	name := str("ps")
	if name == "" {
		name = "VMess " + host
	}
	p := baseProxy(name, "vmess", host, port)
	p["uuid"] = str("id")
	p["alterId"] = intOf("aid")
	if cipher := str("scy"); cipher != "" {
		p["cipher"] = cipher
	} else {
		p["cipher"] = "auto"
	}
	if str("tls") == "tls" {
		p["tls"] = true
	}
	if sni := str("sni"); sni != "" {
		p["servername"] = sni
	}
	if fp := str("fp"); fp != "" {
		p["client-fingerprint"] = fp
	}
	// vmess JSON 的 host/path 是标量字段，借道 url.Values 喂给共用传输层逻辑
	q := url.Values{}
	if v := str("host"); v != "" {
		q.Set("host", v)
	}
	if v := str("path"); v != "" {
		q.Set("path", v)
	}
	applyTransport(p, strings.ToLower(str("net")), q, "")
	if alpn := str("alpn"); alpn != "" {
		p["alpn"] = strings.Split(alpn, ",")
	}
	return p, nil
}

func parseSS(line string) (map[string]any, error) {
	u, err := url.Parse(line)
	if err != nil {
		return nil, err
	}
	var method, password string
	if u.User == nil || u.Host == "" {
		// 旧式整体 base64：ss://base64(method:password@host:port)#name
		body := strings.TrimPrefix(line, "ss://")
		if frag := strings.Index(body, "#"); frag >= 0 {
			body = body[:frag]
		}
		decoded, derr := decodeBase64Loose(body)
		if derr != nil {
			return nil, fmt.Errorf("ss: legacy decode: %w", derr)
		}
		rebuilt, perr := url.Parse("ss://" + string(decoded))
		if perr != nil {
			return nil, perr
		}
		u = rebuilt
	}
	host, port, err := parseHostPort(u)
	if err != nil {
		return nil, err
	}
	if u.User != nil {
		if pw, hasPw := u.User.Password(); hasPw {
			method = u.User.Username()
			password = pw
		} else {
			// SIP002：userinfo 是 base64(method:password)，也可能是明文 method:password
			ui := u.User.Username()
			if decoded, derr := decodeBase64Loose(ui); derr == nil && strings.Contains(string(decoded), ":") {
				method, password, _ = strings.Cut(string(decoded), ":")
			} else if m, pw, found := strings.Cut(ui, ":"); found {
				method, password = m, pw
			} else {
				method = ui
			}
		}
	}
	if method == "" {
		return nil, fmt.Errorf("ss: missing method")
	}
	p := baseProxy(linkName(u, "SS "+u.Host), "ss", host, port)
	p["cipher"] = method
	p["password"] = password
	if plugin := u.Query().Get("plugin"); plugin != "" {
		applySSPlugin(p, plugin)
	}
	return p, nil
}

// applySSPlugin 解析 SIP002 的 plugin 参数（obfs-local / v2ray-plugin）。
func applySSPlugin(p map[string]any, plugin string) {
	parts := strings.Split(plugin, ";")
	if len(parts) == 0 {
		return
	}
	opts := map[string]any{}
	kv := map[string]string{}
	for _, part := range parts[1:] {
		if k, v, found := strings.Cut(part, "="); found {
			kv[strings.TrimSpace(k)] = strings.TrimSpace(v)
		} else {
			kv[strings.TrimSpace(part)] = "true"
		}
	}
	switch parts[0] {
	case "obfs-local", "obfs":
		p["plugin"] = "obfs"
		if mode := kv["obfs"]; mode != "" {
			opts["mode"] = mode
		}
		if host := kv["obfs-host"]; host != "" {
			opts["host"] = host
		}
		if uri := kv["obfs-uri"]; uri != "" {
			opts["path"] = uri
		}
	case "v2ray-plugin":
		p["plugin"] = "v2ray-plugin"
		if mode := kv["mode"]; mode != "" {
			opts["mode"] = mode
		}
		if host := kv["host"]; host != "" {
			opts["host"] = host
		}
		if path := kv["path"]; path != "" {
			opts["path"] = path
		}
		if _, tls := kv["tls"]; tls {
			opts["tls"] = true
		}
	default:
		return
	}
	if len(opts) > 0 {
		p["plugin-opts"] = opts
	}
}

func parseSSR(line string) (map[string]any, error) {
	decoded, err := decodeBase64Loose(strings.TrimPrefix(line, "ssr://"))
	if err != nil {
		return nil, fmt.Errorf("ssr: decode: %w", err)
	}
	main, query, _ := strings.Cut(string(decoded), "/?")
	// 从右切分 host:port:protocol:method:obfs:passwordB64，host 可能是 IPv6（含冒号）
	segments := strings.Split(main, ":")
	if len(segments) < 6 {
		return nil, fmt.Errorf("ssr: malformed main part")
	}
	tail := segments[len(segments)-5:]
	host := strings.Join(segments[:len(segments)-5], ":")
	port, err := strconv.Atoi(tail[0])
	if err != nil || port <= 0 {
		return nil, fmt.Errorf("ssr: invalid port")
	}
	passwordRaw, _ := decodeBase64Loose(tail[4])
	params, _ := url.ParseQuery(query)
	decodeParam := func(key string) string {
		v := params.Get(key)
		if v == "" {
			return ""
		}
		if b, derr := decodeBase64Loose(v); derr == nil {
			return string(b)
		}
		return v
	}
	name := decodeParam("remarks")
	if name == "" {
		name = "SSR " + host
	}
	p := baseProxy(name, "ssr", host, port)
	p["cipher"] = tail[2]
	p["password"] = string(passwordRaw)
	p["protocol"] = tail[1]
	p["obfs"] = tail[3]
	if v := decodeParam("protoparam"); v != "" {
		p["protocol-param"] = v
	}
	if v := decodeParam("obfsparam"); v != "" {
		p["obfs-param"] = v
	}
	return p, nil
}

func parseHysteria2(line string) (map[string]any, error) {
	u, err := url.Parse(line)
	if err != nil {
		return nil, err
	}
	host, port, err := parseHostPort(u)
	if err != nil {
		return nil, err
	}
	q := u.Query()
	p := baseProxy(linkName(u, "Hysteria2 "+u.Host), "hysteria2", host, port)
	if u.User != nil {
		if pw, hasPw := u.User.Password(); hasPw {
			// user:pass 形式两者都是凭据的一部分，原样拼回
			p["password"] = u.User.Username() + ":" + pw
		} else if auth := u.User.Username(); auth != "" {
			p["password"] = auth
		}
	}
	if sni := queryFirst(q, "sni", "peer"); sni != "" {
		p["sni"] = sni
	}
	if insecureFlag(q) {
		p["skip-cert-verify"] = true
	}
	if obfs := q.Get("obfs"); obfs != "" {
		p["obfs"] = obfs
	}
	if obfsPw := queryFirst(q, "obfs-password", "obfs_password"); obfsPw != "" {
		p["obfs-password"] = obfsPw
	}
	if pin := q.Get("pinSHA256"); pin != "" {
		p["fingerprint"] = pin
	}
	if mport := q.Get("mport"); mport != "" {
		p["ports"] = mport
	}
	if alpn := q.Get("alpn"); alpn != "" {
		p["alpn"] = strings.Split(alpn, ",")
	}
	return p, nil
}

func parseHysteria(line string) (map[string]any, error) {
	u, err := url.Parse(line)
	if err != nil {
		return nil, err
	}
	host, port, err := parseHostPort(u)
	if err != nil {
		return nil, err
	}
	q := u.Query()
	p := baseProxy(linkName(u, "Hysteria "+u.Host), "hysteria", host, port)
	if auth := queryFirst(q, "auth"); auth != "" {
		p["auth-str"] = auth
	}
	if sni := queryFirst(q, "peer", "sni"); sni != "" {
		p["sni"] = sni
	}
	if insecureFlag(q) {
		p["skip-cert-verify"] = true
	}
	if up := q.Get("upmbps"); up != "" {
		if n, err := strconv.Atoi(up); err == nil {
			p["up"] = fmt.Sprintf("%d Mbps", n)
		}
	}
	if down := q.Get("downmbps"); down != "" {
		if n, err := strconv.Atoi(down); err == nil {
			p["down"] = fmt.Sprintf("%d Mbps", n)
		}
	}
	if obfs := q.Get("obfs"); obfs != "" {
		p["obfs"] = obfs
	}
	if alpn := q.Get("alpn"); alpn != "" {
		p["alpn"] = strings.Split(alpn, ",")
	}
	if mport := queryFirst(q, "mport", "ports"); mport != "" {
		p["ports"] = mport
	}
	return p, nil
}

func parseTUIC(line string) (map[string]any, error) {
	u, err := url.Parse(line)
	if err != nil {
		return nil, err
	}
	host, port, err := parseHostPort(u)
	if err != nil {
		return nil, err
	}
	if u.User == nil || u.User.Username() == "" {
		return nil, fmt.Errorf("tuic: missing uuid")
	}
	q := u.Query()
	p := baseProxy(linkName(u, "TUIC "+u.Host), "tuic", host, port)
	p["uuid"] = u.User.Username()
	if pw, hasPw := u.User.Password(); hasPw {
		p["password"] = pw
	} else if token := q.Get("token"); token != "" {
		p["token"] = token
	}
	if cc := queryFirst(q, "congestion_control", "congestion-controller"); cc != "" {
		p["congestion-controller"] = cc
	}
	if mode := queryFirst(q, "udp_relay_mode", "udp-relay-mode"); mode != "" {
		p["udp-relay-mode"] = mode
	}
	if alpn := q.Get("alpn"); alpn != "" {
		p["alpn"] = strings.Split(alpn, ",")
	}
	if sni := queryFirst(q, "sni", "peer"); sni != "" {
		p["sni"] = sni
	}
	if insecureFlag(q) {
		p["skip-cert-verify"] = true
	}
	if q.Get("disable_sni") == "1" {
		p["disable-sni"] = true
	}
	return p, nil
}

func parseWireGuard(line string) (map[string]any, error) {
	u, err := url.Parse(line)
	if err != nil {
		return nil, err
	}
	host, port, err := parseHostPort(u)
	if err != nil {
		return nil, err
	}
	privateKey := ""
	if u.User != nil {
		privateKey = u.User.Username()
		if pw, hasPw := u.User.Password(); hasPw {
			privateKey += ":" + pw
		}
	}
	if privateKey == "" {
		return nil, fmt.Errorf("wireguard: missing private key")
	}
	q := u.Query()
	p := baseProxy(linkName(u, "WireGuard "+u.Host), "wireguard", host, port)
	p["private-key"] = privateKey
	if pub := queryFirst(q, "publickey", "public-key", "peerkey"); pub != "" {
		p["public-key"] = pub
	}
	if psk := queryFirst(q, "presharedkey", "preshared-key"); psk != "" {
		p["preshared-key"] = psk
	}
	if address := queryFirst(q, "address", "ip"); address != "" {
		for _, part := range strings.Split(address, ",") {
			part = strings.TrimSpace(part)
			if part == "" {
				continue
			}
			ip := strings.Split(part, "/")[0]
			if strings.Contains(ip, ":") {
				p["ipv6"] = ip
			} else {
				p["ip"] = ip
			}
		}
	}
	if reserved := q.Get("reserved"); reserved != "" {
		var values []int
		valid := true
		for _, part := range strings.Split(reserved, ",") {
			n, err := strconv.Atoi(strings.TrimSpace(part))
			if err != nil {
				valid = false
				break
			}
			values = append(values, n)
		}
		if valid && len(values) > 0 {
			p["reserved"] = values
		}
	}
	if mtu := q.Get("mtu"); mtu != "" {
		if n, err := strconv.Atoi(mtu); err == nil {
			p["mtu"] = n
		}
	}
	return p, nil
}

func parseSocksHTTP(line string, typ string) (map[string]any, error) {
	u, err := url.Parse(line)
	if err != nil {
		return nil, err
	}
	host, port, err := parseHostPort(u)
	if err != nil {
		return nil, err
	}
	p := baseProxy(linkName(u, typ+" "+u.Host), typ, host, port)
	if u.User != nil && u.User.Username() != "" {
		p["username"] = u.User.Username()
		if pw, hasPw := u.User.Password(); hasPw {
			p["password"] = pw
		}
	}
	if typ == "http" {
		// HTTP 代理没有 UDP 语义
		delete(p, "udp")
	}
	return p, nil
}

func parseAnyTLS(line string) (map[string]any, error) {
	u, err := url.Parse(line)
	if err != nil {
		return nil, err
	}
	host, port, err := parseHostPort(u)
	if err != nil {
		return nil, err
	}
	if u.User == nil || u.User.Username() == "" {
		return nil, fmt.Errorf("anytls: missing password")
	}
	password := u.User.Username()
	if pw, hasPw := u.User.Password(); hasPw {
		password += ":" + pw
	}
	q := u.Query()
	p := baseProxy(linkName(u, "AnyTLS "+u.Host), "anytls", host, port)
	p["password"] = password
	if sni := queryFirst(q, "sni", "peer"); sni != "" {
		p["sni"] = sni
	}
	if fp := q.Get("fp"); fp != "" {
		p["client-fingerprint"] = fp
	}
	if alpn := q.Get("alpn"); alpn != "" {
		p["alpn"] = strings.Split(alpn, ",")
	}
	if insecureFlag(q) {
		p["skip-cert-verify"] = true
	}
	if q.Get("udp") == "0" {
		p["udp"] = false
	}
	// AnyTLS 的会话保活三参数：链接里常见下划线写法，mihomo 字段是连字符
	idleInt := func(linkKey, field string) {
		if v := queryFirst(q, linkKey, field); v != "" {
			if n, err := strconv.Atoi(v); err == nil {
				p[field] = n
			}
		}
	}
	idleInt("idle_session_check_interval", "idle-session-check-interval")
	idleInt("idle_session_timeout", "idle-session-timeout")
	idleInt("min_idle_session", "min-idle-session")
	return p, nil
}
