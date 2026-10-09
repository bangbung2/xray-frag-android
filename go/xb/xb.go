package xb

import (
	"bytes"
	"fmt"
	"os"
	"sync"

	"github.com/xjasonlyu/tun2socks/v2/engine"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"
	_ "github.com/xtls/xray-core/main/distro/all"
)

var (
	mu    sync.Mutex
	inst  *core.Instance
	tunOn bool
)

// StartXray menjalankan Xray-core dari config JSON. assetDir berisi geoip.dat & geosite.dat.
func StartXray(configJSON string, assetDir string) error {
	mu.Lock()
	defer mu.Unlock()
	if inst != nil {
		return nil
	}
	os.Setenv("xray.location.asset", assetDir)
	os.Setenv("XRAY_LOCATION_ASSET", assetDir)
	cfg, err := serial.LoadJSONConfig(bytes.NewReader([]byte(configJSON)))
	if err != nil {
		return fmt.Errorf("config: %w", err)
	}
	i, err := core.New(cfg)
	if err != nil {
		return fmt.Errorf("core.New: %w", err)
	}
	if err := i.Start(); err != nil {
		return fmt.Errorf("start: %w", err)
	}
	inst = i
	return nil
}

// StartTun menghubungkan file descriptor TUN ke SOCKS5 (inbound mixed Xray).
func StartTun(fd int32, mtu int32, socksAddr string) error {
	mu.Lock()
	defer mu.Unlock()
	if tunOn {
		return nil
	}
	engine.Insert(&engine.Key{
		MTU:      int(mtu),
		Proxy:    "socks5://" + socksAddr,
		Device:   fmt.Sprintf("fd://%d", fd),
		LogLevel: "warn",
	})
	engine.Start()
	tunOn = true
	return nil
}

func Stop() {
	mu.Lock()
	defer mu.Unlock()
	if tunOn {
		engine.Stop()
		tunOn = false
	}
	if inst != nil {
		inst.Close()
		inst = nil
	}
}

func Version() string { return core.Version() }
