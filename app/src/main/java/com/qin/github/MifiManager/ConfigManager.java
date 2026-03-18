package com.qin.github.MifiManager;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

public class ConfigManager {
    private final File configFile;
    private Properties config;

    public ConfigManager(Context context) {
        configFile = new File(context.getFilesDir(), "mifimanager.ini");
        config = new Properties();
        load();
    }

    public void load() {
        if (configFile.exists()) {
            try (FileInputStream fis = new FileInputStream(configFile)) {
                config.load(fis);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            setDefaults();
            save();
        }
    }

    private void setDefaults() {
        config.setProperty("admin_password", "admin");
        config.setProperty("wifi_ssid", "MiFi-DEFAULT");
        config.setProperty("wifi_password", "1234567890");
        config.setProperty("wifi_encryption", "WPA2");
        config.setProperty("current_sim", "1");
    }

    public void save() {
        try (FileOutputStream fos = new FileOutputStream(configFile)) {
            config.store(fos, "MifiManager Config");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String get(String key) {
        return config.getProperty(key);
    }

    public void set(String key, String value) {
        config.setProperty(key, value);
        save();
    }
}