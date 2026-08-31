#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1])
client = root / "websocket" / "client.go"
config = root / "websocket" / "config.go"

client_text = client.read_text()

marker = 'const writeDeadline = 10 * time.Second\n'
helper = r'''
func androidDNSServer() string {
	raw := strings.TrimSpace(os.Getenv("DNS"))
	host := raw
	port := "53"

	if h, p, err := net.SplitHostPort(raw); err == nil {
		host = h
		port = p
	} else {
		host = strings.Trim(raw, "[]")
	}

	ipHost := host
	if i := strings.LastIndex(ipHost, "%"); i >= 0 {
		ipHost = ipHost[:i]
	}
	ip := net.ParseIP(ipHost)
	if ip == nil || ip.IsLoopback() || ip.IsUnspecified() || ip.IsMulticast() {
		host = "9.9.9.9"
		port = "53"
	}

	return net.JoinHostPort(host, port)
}

func androidDNSDialContext(ctx context.Context, network, address string) (net.Conn, error) {
	dnsServer := androidDNSServer()
	resolver := &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, dnsNetwork, _ string) (net.Conn, error) {
			d := net.Dialer{Timeout: 10 * time.Second}
			return d.DialContext(ctx, dnsNetwork, dnsServer)
		},
	}
	d := net.Dialer{Timeout: 30 * time.Second, Resolver: resolver}
	return d.DialContext(ctx, network, address)
}
'''
if 'func androidDNSDialContext' not in client_text:
    if marker not in client_text:
        raise SystemExit("writeDeadline marker not found")
    client_text = client_text.replace(marker, marker + helper + "\n", 1)

old_http = '''\tclient := &http.Client{}\n\tif tlsConfig != nil {\n\t\tclient.Transport = &http.Transport{\n\t\t\tTLSClientConfig: tlsConfig,\n\t\t}\n\t}\n'''
new_http = '''\ttransport := http.DefaultTransport.(*http.Transport).Clone()\n\ttransport.DialContext = androidDNSDialContext\n\ttransport.TLSClientConfig = tlsConfig\n\tclient := &http.Client{Transport: transport}\n'''
if old_http not in client_text:
    raise SystemExit("getToken HTTP client block not found")
client_text = client_text.replace(old_http, new_http, 1)

old_ws = '\tdialer := websocket.DefaultDialer\n'
new_ws = '\tdialerValue := *websocket.DefaultDialer\n\tdialer := &dialerValue\n\tdialer.NetDialContext = androidDNSDialContext\n'
if old_ws not in client_text:
    raise SystemExit("websocket dialer block not found")
client_text = client_text.replace(old_ws, new_ws, 1)
client.write_text(client_text)

config_text = config.read_text()
old_prov = '''\thttpClient := &http.Client{}\n\tif tlsCfg != nil {\n\t\thttpClient.Transport = &http.Transport{TLSClientConfig: tlsCfg}\n\t}\n'''
new_prov = '''\ttransport := http.DefaultTransport.(*http.Transport).Clone()\n\ttransport.DialContext = androidDNSDialContext\n\ttransport.TLSClientConfig = tlsCfg\n\thttpClient := &http.Client{Transport: transport}\n'''
if old_prov not in config_text:
    raise SystemExit("provisioning HTTP client block not found")
config_text = config_text.replace(old_prov, new_prov, 1)
config.write_text(config_text)
