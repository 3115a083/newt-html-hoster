package main

import (
    "context"
    "net"
    "os"
    "strings"
)

func init() {
    dns := strings.TrimSpace(os.Getenv("DNS"))
    if dns == "" {
        dns = "9.9.9.9"
    }

    net.DefaultResolver = &net.Resolver{
        PreferGo: true,
        Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
            d := net.Dialer{}
            return d.DialContext(ctx, network, net.JoinHostPort(dns, "53"))
        },
    }
}
