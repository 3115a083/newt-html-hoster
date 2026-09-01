#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1])
client = root / "websocket" / "client.go"
config = root / "websocket" / "config.go"
util = root / "util" / "util.go"

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


util_text = util.read_text()
if '"os"' not in util_text:
    import_marker = '"net"\n'
    if import_marker not in util_text:
        raise SystemExit("util net import marker not found")
    util_text = util_text.replace(import_marker, import_marker + '\t"os"\n', 1)

old_lookup = '''\t// Lookup IP addresses
\tips, err := net.LookupIP(host)
\tif err != nil {
\t\treturn "", fmt.Errorf("DNS lookup failed: %v", err)
\t}
'''
new_lookup = '''\t// Resolve through the Android-selected DNS server. Android's libc resolver may
\t// point Go at ::1 even when no local DNS daemon is available.
\tdnsServer := strings.TrimSpace(os.Getenv("DNS"))
\tdnsHost := dnsServer
\tdnsPort := "53"
\tif h, p, splitErr := net.SplitHostPort(dnsServer); splitErr == nil {
\t\tdnsHost = h
\t\tdnsPort = p
\t} else {
\t\tdnsHost = strings.Trim(dnsServer, "[]")
\t}
\tcleanDNSHost := dnsHost
\tif i := strings.LastIndex(cleanDNSHost, "%"); i >= 0 {
\t\tcleanDNSHost = cleanDNSHost[:i]
\t}
\tdnsIP := net.ParseIP(cleanDNSHost)
\tif dnsIP == nil || dnsIP.IsLoopback() || dnsIP.IsUnspecified() || dnsIP.IsMulticast() {
\t\tdnsHost = "9.9.9.9"
\t\tdnsPort = "53"
\t}
\tdnsAddr := net.JoinHostPort(dnsHost, dnsPort)
\tresolver := &net.Resolver{
\t\tPreferGo: true,
\t\tDial: func(ctx context.Context, network, _ string) (net.Conn, error) {
\t\t\td := net.Dialer{}
\t\t\treturn d.DialContext(ctx, network, dnsAddr)
\t\t},
\t}
\tips, err := resolver.LookupIP(context.Background(), "ip", host)
\tif err != nil {
\t\treturn "", fmt.Errorf("DNS lookup failed: %v", err)
\t}
'''
if old_lookup not in util_text:
    raise SystemExit("ResolveDomain lookup block not found")
util_text = util_text.replace(old_lookup, new_lookup, 1)
util.write_text(util_text)
