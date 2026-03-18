package com.qin.github.MifiManager;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.PopupMenu;
import android.widget.Toast;

import java.lang.reflect.Method;

public class MainActivity extends Activity {

    private ConfigManager configManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        configManager = new ConfigManager(this); // 初始化配置管理器

        // 启动 Web 后台服务
        startService(new Intent(this, WebServerService.class));

        // 自动设置默认热点（在子线程执行）
        setupDefaultHotspot();

        Button btnSystemSettings = findViewById(R.id.btn_system_settings);
        Button btnShutdownRestart = findViewById(R.id.btn_shutdown_restart);

        btnSystemSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
            }
        });


        btnShutdownRestart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showShutdownRestartMenu(v);
            }
        });
    }

    // ==================== 自动设置热点 ====================
    private void setupDefaultHotspot() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                // 从配置文件中读取已保存的 SSID 和密码
                String ssid = configManager.get("wifi_ssid");
                String password = configManager.get("wifi_password");

                // 如果配置不存在，则生成默认值并保存
                if (ssid == null || ssid.isEmpty() || "MiFi-DEFAULT".equals(ssid)) {
                    ssid = generateHotspotName().toUpperCase();
                    configManager.set("wifi_ssid", ssid);
                }
                if (password == null || password.isEmpty()) {
                    password = "1234567890";
                    configManager.set("wifi_password", password);
                }

                boolean success = setHotspotViaReflection(ssid, password);
                if (!success) {
                    // 可在此处扩展 root 命令方式
                }

                final boolean finalSuccess = success;
                final String finalSsid = ssid; // 创建 final 副本供内部类使用
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (finalSuccess) {
                            Toast.makeText(MainActivity.this, "热点已设置为: " + finalSsid, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(MainActivity.this, "设置热点失败，请手动配置", Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    private String generateHotspotName() {
        String mac = getMacAddress();
        if (mac != null && mac.length() >= 4) {
            String cleanMac = mac.replace(":", "").replace("-", "");
            if (cleanMac.length() >= 4) {
                return "MiFi-" + cleanMac.substring(0, 4);
            }
        }
        return "MiFi-0000"; // 后备
    }

    private String getMacAddress() {
        WifiManager wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wifiManager.getConnectionInfo();
        if (info != null) {
            String mac = info.getMacAddress();
            // 避免默认虚拟 MAC
            if (mac != null && !"02:00:00:00:00:00".equals(mac)) {
                return mac;
            }
        }
        // 备选：尝试读取系统属性（可能需要 root）
        try {
            Class<?> clazz = Class.forName("android.os.SystemProperties");
            Method method = clazz.getMethod("get", String.class);
            String mac = (String) method.invoke(null, "ro.boot.wifimac");
            if (mac != null && !mac.isEmpty()) return mac;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    @SuppressWarnings("JavaReflectionMemberAccess")
    private boolean setHotspotViaReflection(String ssid, String password) {
        try {
            WifiManager wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);

            // 关闭 Wi-Fi 客户端模式（如果开启）
            try {
                Method setWifiEnabled = wifiManager.getClass().getMethod("setWifiEnabled", boolean.class);
                setWifiEnabled.invoke(wifiManager, false);
                Thread.sleep(500);
            } catch (Exception e) {
                // 忽略
            }

            // 创建热点配置
            WifiConfiguration config = new WifiConfiguration();
            config.SSID = ssid;
            config.preSharedKey = password; // 直接使用密码，不加引号
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
            config.allowedProtocols.set(WifiConfiguration.Protocol.RSN);

            // 反射调用 setWifiApEnabled
            Method setWifiApEnabled = wifiManager.getClass().getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
            return (Boolean) setWifiApEnabled.invoke(wifiManager, config, true);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    // ==================== 原有功能 ====================
    private void enableUsbTethering() {
        boolean apiSuccess = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Method method = cm.getClass().getMethod("setUsbTethering", boolean.class);
            method.invoke(cm, true);
            apiSuccess = true;
            Toast.makeText(this, "通过 API 尝试开启 USB 网络共享", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (!apiSuccess) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    boolean success = ShellUtils.execCommandSuccess("svc usb setFunctions rndis", true);
                    if (!success) {
                        success = ShellUtils.execCommandSuccess("setprop sys.usb.config rndis,adb", true);
                    }
                    final boolean finalSuccess = success;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (finalSuccess) {
                                Toast.makeText(MainActivity.this, "通过 root 开启 USB 网络共享成功", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(MainActivity.this, "所有方法均失败", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                }
            }).start();
        }
    }

    private void showShutdownRestartMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenuInflater().inflate(R.menu.shutdown_menu, popup.getMenu());
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                int id = item.getItemId();
                if (id == R.id.action_shutdown) {
                    shutdown();
                    return true;
                } else if (id == R.id.action_restart) {
                    restart();
                    return true;
                }
                return false;
            }
        });
        popup.show();
    }

    private void shutdown() {
        try {
            Intent intent = new Intent(Intent.ACTION_SHUTDOWN);
            sendBroadcast(intent);
            Toast.makeText(this, "发送关机广播", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean success = ShellUtils.execCommandSuccess("reboot -p", true);
                if (!success) {
                    success = ShellUtils.execCommandSuccess("halt", true);
                }
                final boolean finalSuccess = success;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (finalSuccess) {
                            Toast.makeText(MainActivity.this, "执行关机命令", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this, "关机失败", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }).start();
    }

    private void restart() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            pm.reboot(null);
        } catch (Exception e) {
            e.printStackTrace();
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean success = ShellUtils.execCommandSuccess("reboot", true);
                final boolean finalSuccess = success;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (finalSuccess) {
                            Toast.makeText(MainActivity.this, "执行重启命令", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this, "重启失败", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }).start();
    }
}