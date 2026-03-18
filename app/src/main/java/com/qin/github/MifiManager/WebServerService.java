package com.qin.github.MifiManager;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import fi.iki.elonen.NanoHTTPD;

public class WebServerService extends Service {
    private static final String TAG = "WebServerService";
    private WebServer server;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            server = new WebServer();
            server.start();
            Log.i(TAG, "Web server started on http://192.168.43.1:8080");
        } catch (IOException e) {
            Log.e(TAG, "Failed to start web server", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) {
            server.stop();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private class WebServer extends NanoHTTPD {
        private final File configFile;
        private Properties config;

        public WebServer() throws IOException {
            super(8080);
            configFile = new File(getFilesDir(), "mifimanager.ini");
            config = new Properties();
            if (configFile.exists()) {
                try (FileInputStream fis = new FileInputStream(configFile)) {
                    config.load(fis);
                }
                convertPasswordToMd5IfNeeded();
                // 兼容旧版加密方式：将 "WPA2" 转为 "WPA2-PSK"
                String enc = config.getProperty("wifi_encryption");
                if ("WPA2".equals(enc)) {
                    config.setProperty("wifi_encryption", "WPA2-PSK");
                    saveConfig();
                }
            } else {
                // 默认配置
                config.setProperty("admin_password", md5("admin"));
                config.setProperty("wifi_ssid", "MiFi-DEFAULT");
                config.setProperty("wifi_password", "1234567890");
                config.setProperty("wifi_encryption", "WPA2-PSK");
                config.setProperty("current_sim", "1");
                saveConfig();
            }
        }

        /** 将存储的密码转换为 MD5（如果当前是明文） */
        private void convertPasswordToMd5IfNeeded() {
            String pwd = config.getProperty("admin_password");
            if (pwd != null && pwd.length() != 32) {
                config.setProperty("admin_password", md5(pwd));
                saveConfig();
                Log.d(TAG, "Converted admin password to MD5");
            }
        }

        /** 计算字符串的 MD5 值（小写十六进制） */
        private String md5(String s) {
            try {
                MessageDigest digest = MessageDigest.getInstance("MD5");
                byte[] hash = digest.digest(s.getBytes());
                StringBuilder hex = new StringBuilder();
                for (byte b : hash) {
                    String h = Integer.toHexString(0xFF & b);
                    if (h.length() == 1) hex.append('0');
                    hex.append(h);
                }
                return hex.toString();
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
                return "";
            }
        }

        private void saveConfig() {
            try (FileOutputStream fos = new FileOutputStream(configFile)) {
                config.store(fos, "MifiManager Config");
            } catch (IOException e) {
                Log.e(TAG, "Save config failed", e);
            }
        }

        /** 获取在线客户端列表（通过 ARP 表） */
        private List<ClientInfo> getConnectedClients() {
            List<ClientInfo> clients = new ArrayList<>();
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new FileReader("/proc/net/arp"));
                String line;
                reader.readLine(); // 跳过标题行
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 4) {
                        String ip = parts[0];
                        String mac = parts[3];
                        if (!"00:00:00:00:00:00".equals(mac) && !mac.isEmpty()) {
                            String status = parts[2];
                            if ("0x2".equals(status)) {
                                clients.add(new ClientInfo(ip, mac));
                            }
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Error reading ARP table", e);
            } finally {
                if (reader != null) {
                    try { reader.close(); } catch (IOException ignored) {}
                }
            }
            return clients;
        }

        /** 执行 shell 命令（需要 root） */
        private String execShellCommand(String command) {
            StringBuilder output = new StringBuilder();
            try {
                Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
                int exitCode = process.waitFor();
                output.append("Exit code: ").append(exitCode);
            } catch (Exception e) {
                output.append("Error: ").append(e.getMessage());
            }
            return output.toString();
        }

        @Override
        public Response serve(IHTTPSession session) {
            String uri = session.getUri();
            Map<String, String> params = session.getParms();

            if (Method.POST.equals(session.getMethod())) {
                try {
                    session.parseBody(new HashMap<>());
                    params = session.getParms();
                } catch (IOException | ResponseException e) {
                    e.printStackTrace();
                }
            }

            String adminPwdHash = config.getProperty("admin_password");
            String inputPwd = params.get("pwd");
            String inputPwdHash = (inputPwd != null) ? md5(inputPwd) : null;
            boolean isAuthenticated = (adminPwdHash != null && adminPwdHash.equals(inputPwdHash));

            // 公开页面（不需要认证）
            if ("/login".equals(uri) || "/setup".equals(uri) || "/save_setup".equals(uri) ||
                    "/resetpwd".equals(uri) || "/power".equals(uri) || "/do_power".equals(uri)) {
                // 这些页面允许访问，但其中有些需要认证（如 power 页面已包含认证检查）
            } else {
                if (!isAuthenticated) {
                    String loginPage = "<html><head><title>登录</title><style>" +
                            "body{font-family:Arial;padding:20px;background:#f5f5f5;}" +
                            ".container{max-width:300px;margin:0 auto;background:#fff;padding:20px;border-radius:5px;box-shadow:0 2px 5px rgba(0,0,0,0.1);}" +
                            "h2{text-align:center;color:#333;}" +
                            "input[type=password]{width:100%;padding:10px;margin:10px 0;border:1px solid #ddd;border-radius:3px;box-sizing:border-box;}" +
                            "input[type=submit]{width:100%;padding:10px;background:#4CAF50;color:#fff;border:none;border-radius:3px;cursor:pointer;}" +
                            "input[type=submit]:hover{background:#45a049;}" +
                            "</style></head><body>" +
                            "<div class='container'>" +
                            "<h2>Mifi Manager 登录</h2>" +
                            "<form method='post' action='/login'>" +
                            "密码: <input type='password' name='pwd' required><br>" +
                            "<input type='submit' value='登录'>" +
                            "</form></div></body></html>";
                    return newFixedLengthResponse(loginPage);
                }
            }

            // 路由处理
            if ("/".equals(uri)) {
                return serveIndex(inputPwd);
            } else if ("/login".equals(uri)) {
                return login(params, inputPwd);
            } else if ("/setup".equals(uri)) {
                return serveSetup();
            } else if ("/save_setup".equals(uri)) {
                return saveSetup(params);
            } else if ("/resetpwd".equals(uri)) {
                return resetPassword();
            } else if ("/wifi".equals(uri)) {
                return serveWifi(params, inputPwd);
            } else if ("/sim".equals(uri)) {
                return serveSim(params, inputPwd);
            } else if ("/clients".equals(uri)) {
                return serveClients(inputPwd);
            } else if ("/blacklist".equals(uri)) {
                return serveBlacklist(params, inputPwd);
            } else if ("/power".equals(uri)) {
                return servePower(inputPwd);
            } else if ("/do_power".equals(uri)) {
                return doPower(params, inputPwd);
            } else {
                return newFixedLengthResponse("Not Found");
            }
        }

        private Response login(Map<String, String> params, String inputPwd) {
            String pwdHash = md5(inputPwd);
            String adminPwdHash = config.getProperty("admin_password");
            if (adminPwdHash != null && adminPwdHash.equals(pwdHash)) {
                if ("admin".equals(inputPwd)) {
                    String html = "<html><head><script>alert('欢迎回来！建议立即修改默认密码。');window.location.href='/?pwd=" + inputPwd + "';</script></head><body></body></html>";
                    return newFixedLengthResponse(html);
                } else {
                    String redirect = "<html><head><meta http-equiv='refresh' content='0;url=/?pwd=" + inputPwd + "'></head></html>";
                    return newFixedLengthResponse(redirect);
                }
            } else {
                String errorHtml = "<html><head><script>alert('密码错误');window.location.href='/login';</script></head><body></body></html>";
                return newFixedLengthResponse(errorHtml);
            }
        }

        private Response resetPassword() {
            config.setProperty("admin_password", md5("admin"));
            saveConfig();
            String html = "<html><head><title>密码重置</title><style>body{font-family:Arial;padding:20px;}</style></head><body>" +
                    "<h2>密码已重置为 <b>admin</b></h2>" +
                    "<p><a href='/login'>前往登录</a></p></body></html>";
            return newFixedLengthResponse(html);
        }

        private Response serveIndex(String inputPwd) {
            boolean isDefaultPwd = "admin".equals(inputPwd);
            String warningHtml = "";
            if (isDefaultPwd) {
                warningHtml = "<div style='background:#ffdddd;border-left:6px solid #f44336;padding:10px;margin-bottom:15px;'>" +
                        "<strong>警告：</strong> 您正在使用默认密码，<a href='/setup'>点击这里修改密码</a>。</div>";
            }
            String welcomeHtml = "<div style='background:#e7f3fe;border-left:6px solid #2196F3;padding:10px;margin-bottom:15px;'>" +
                    "<strong>欢迎回来，管理员！</strong></div>";

            String html = "<html><head><title>Mifi Manager</title><style>" +
                    "body{font-family:Arial;padding:20px;background:#f5f5f5;}" +
                    ".container{max-width:800px;margin:0 auto;background:#fff;padding:20px;border-radius:5px;box-shadow:0 2px 5px rgba(0,0,0,0.1);}" +
                    "h1{color:#333;border-bottom:1px solid #ddd;padding-bottom:10px;}" +
                    "ul{list-style:none;padding:0;}" +
                    "li{margin:10px 0;}" +
                    "a{display:block;padding:12px;background:#4CAF50;color:#fff;text-decoration:none;border-radius:3px;text-align:center;}" +
                    "a:hover{background:#45a049;}" +
                    ".footer{margin-top:20px;font-size:12px;color:#777;text-align:center;}" +
                    "</style></head><body>" +
                    "<div class='container'>" +
                    "<h1>Mifi Manager</h1>" +
                    welcomeHtml +
                    warningHtml +
                    "<ul>" +
                    "<li><a href='/wifi?pwd=" + inputPwd + "'>WiFi设置</a></li>" +
                    "<li><a href='/sim?pwd=" + inputPwd + "'>切换主副卡</a></li>" +
                    "<li><a href='/clients?pwd=" + inputPwd + "'>在线客户端</a></li>" +
                    "<li><a href='/blacklist?pwd=" + inputPwd + "'>黑名单管理</a></li>" +
                    "<li><a href='/setup?pwd=" + inputPwd + "'>修改管理员密码</a></li>" +
                    "<li><a href='/power?pwd=" + inputPwd + "'>关机重启</a></li>" +
                    "</ul>" +
                    "<div class='footer'>MifiManager v1.0</div>" +
                    "</div></body></html>";
            return newFixedLengthResponse(html);
        }

        private Response serveSetup() {
            String html = "<html><head><title>修改密码</title><style>" +
                    "body{font-family:Arial;padding:20px;background:#f5f5f5;}" +
                    ".container{max-width:400px;margin:0 auto;background:#fff;padding:20px;border-radius:5px;box-shadow:0 2px 5px rgba(0,0,0,0.1);}" +
                    "h2{color:#333;}" +
                    "input[type=password]{width:100%;padding:10px;margin:10px 0;border:1px solid #ddd;border-radius:3px;box-sizing:border-box;}" +
                    "input[type=submit]{width:100%;padding:10px;background:#4CAF50;color:#fff;border:none;border-radius:3px;cursor:pointer;}" +
                    "input[type=submit]:hover{background:#45a049;}" +
                    "</style></head><body>" +
                    "<div class='container'>" +
                    "<h2>修改管理员密码</h2>" +
                    "<form method='post' action='/save_setup'>" +
                    "新密码: <input type='password' name='new_pwd' required><br>" +
                    "确认密码: <input type='password' name='confirm_pwd' required><br>" +
                    "<input type='submit' value='保存'>" +
                    "</form><p><a href='/'>返回首页</a></p>" +
                    "</div></body></html>";
            return newFixedLengthResponse(html);
        }

        private Response saveSetup(Map<String, String> params) {
            String newPwd = params.get("new_pwd");
            String confirm = params.get("confirm_pwd");
            if (newPwd != null && newPwd.equals(confirm)) {
                config.setProperty("admin_password", md5(newPwd));
                saveConfig();
                String html = "<html><head><title>成功</title><style>body{font-family:Arial;padding:20px;}</style></head><body>" +
                        "<h2>密码修改成功！</h2><p><a href='/login'>重新登录</a></p></body></html>";
                return newFixedLengthResponse(html);
            } else {
                String html = "<html><head><title>错误</title><style>body{font-family:Arial;padding:20px;}</style></head><body>" +
                        "<h2>密码不匹配，请重试。</h2><p><a href='/setup'>返回</a></p></body></html>";
                return newFixedLengthResponse(html);
            }
        }

        private Response serveWifi(Map<String, String> params, String pwd) {
            if (params.containsKey("ssid") && params.containsKey("password")) {
                String newPwd = params.get("password");
                if (newPwd != null && !newPwd.isEmpty()) {
                    config.setProperty("wifi_password", newPwd);
                }
                config.setProperty("wifi_ssid", params.get("ssid"));
                config.setProperty("wifi_encryption", params.get("encryption"));
                saveConfig();

                // 启动线程执行重启（延迟1秒让响应先返回）
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Thread.sleep(1000);
                            execShellCommand("reboot");
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }).start();

                String html = "<html><head><meta charset='UTF-8'><title>设置成功</title><style>body{font-family:Arial;text-align:center;padding:50px;}</style></head><body>" +
                        "<h2>WiFi 设置已保存，设备即将重启...</h2>" +
                        "<p>请稍候，设备重启后请重新连接。</p></body></html>";
                return newFixedLengthResponse(html);
            } else {
                String currentSsid = config.getProperty("wifi_ssid", "MiFi-DEFAULT");
                String currentEnc = config.getProperty("wifi_encryption", "WPA2-PSK");
                StringBuilder encOptions = new StringBuilder();
                String[] encTypes = {"WPA2-PSK", "WPA-PSK", "none"};
                String[] encLabels = {"WPA2-PSK", "WPA-PSK", "无"};
                for (int i = 0; i < encTypes.length; i++) {
                    String selected = currentEnc.equals(encTypes[i]) ? "selected" : "";
                    encOptions.append("<option value='").append(encTypes[i]).append("' ").append(selected).append(">").append(encLabels[i]).append("</option>");
                }

                String html = "<html><head><title>WiFi设置</title><style>" +
                        "body{font-family:Arial;padding:20px;background:#f5f5f5;}" +
                        ".container{max-width:400px;margin:0 auto;background:#fff;padding:20px;border-radius:5px;box-shadow:0 2px 5px rgba(0,0,0,0.1);}" +
                        "h2{color:#333;}" +
                        "input[type=text],input[type=password],select{width:100%;padding:10px;margin:10px 0;border:1px solid #ddd;border-radius:3px;box-sizing:border-box;}" +
                        "input[type=submit]{width:100%;padding:10px;background:#4CAF50;color:#fff;border:none;border-radius:3px;cursor:pointer;}" +
                        "input[type=submit]:hover{background:#45a049;}" +
                        ".checkbox{display:flex;align-items:center;margin-bottom:10px;}" +
                        ".checkbox input{width:auto;margin-right:10px;}" +
                        "</style></head><body>" +
                        "<div class='container'>" +
                        "<h2>WiFi 设置</h2>" +
                        "<form method='post' action='/wifi'>" +
                        "SSID: <input type='text' name='ssid' value='" + currentSsid + "' required><br>" +
                        "密码: <input type='password' name='password' placeholder='新密码（留空表示不修改）'><br>" +
                        "<div class='checkbox'><input type='checkbox' onclick='togglePassword()'> 显示密码</div>" +
                        "加密方式: <select name='encryption'>" + encOptions + "</select><br>" +
                        "<input type='hidden' name='pwd' value='" + pwd + "'>" +
                        "<input type='submit' value='保存'>" +
                        "</form><p><a href='/?pwd=" + pwd + "'>返回首页</a></p>" +
                        "</div>" +
                        "<script>function togglePassword() { var x = document.getElementsByName('password')[0]; if (x.type === 'password') { x.type = 'text'; } else { x.type = 'password'; } }</script>" +
                        "</body></html>";
                return newFixedLengthResponse(html);
            }
        }

        private Response serveSim(Map<String, String> params, String pwd) {
            if (params.containsKey("sim")) {
                String sim = params.get("sim");
                config.setProperty("current_sim", sim);
                saveConfig();
                String redirect = "<html><head><meta http-equiv='refresh' content='0;url=/?pwd=" + pwd + "'></head></html>";
                return newFixedLengthResponse(redirect);
            } else {
                String current = config.getProperty("current_sim", "1");
                String html = "<html><head><title>切换SIM卡</title><style>" +
                        "body{font-family:Arial;padding:20px;background:#f5f5f5;}" +
                        ".container{max-width:400px;margin:0 auto;background:#fff;padding:20px;border-radius:5px;box-shadow:0 2px 5px rgba(0,0,0,0.1);}" +
                        "h2{color:#333;}" +
                        "p{font-size:18px;}" +
                        "a{display:inline-block;padding:10px 20px;margin:5px;background:#4CAF50;color:#fff;text-decoration:none;border-radius:3px;}" +
                        "a:hover{background:#45a049;}" +
                        "</style></head><body>" +
                        "<div class='container'>" +
                        "<h2>切换主副卡</h2>" +
                        "<p>当前使用: SIM " + current + "</p>" +
                        "<a href='/sim?sim=1&pwd=" + pwd + "'>使用主卡</a> " +
                        "<a href='/sim?sim=2&pwd=" + pwd + "'>使用副卡</a><br><br>" +
                        "<a href='/?pwd=" + pwd + "'>返回首页</a>" +
                        "</div></body></html>";
                return newFixedLengthResponse(html);
            }
        }

        private Response serveClients(String pwd) {
            List<ClientInfo> clients = getConnectedClients();
            StringBuilder listHtml = new StringBuilder();
            if (clients.isEmpty()) {
                listHtml.append("<li>暂无在线设备</li>");
            } else {
                for (ClientInfo client : clients) {
                    String displayName = client.mac.length() > 8 ? client.mac.substring(client.mac.length() - 8) : client.mac;
                    listHtml.append("<li>")
                            .append(client.ip).append(" - 设备 ").append(displayName)
                            .append(" <a href='/blacklist?add=").append(client.ip).append("&pwd=").append(pwd).append("'>拉黑</a>")
                            .append("</li>");
                }
            }
            String html = "<html><head><title>在线客户端</title><style>" +
                    "body{font-family:Arial;padding:20px;background:#f5f5f5;}" +
                    ".container{max-width:600px;margin:0 auto;background:#fff;padding:20px;border-radius:5px;box-shadow:0 2px 5px rgba(0,0,0,0.1);}" +
                    "h2{color:#333;}" +
                    "ul{list-style:none;padding:0;}" +
                    "li{padding:10px;border-bottom:1px solid #eee;}" +
                    "a{color:#4CAF50;text-decoration:none;margin-left:10px;}" +
                    "a:hover{text-decoration:underline;}" +
                    "</style></head><body>" +
                    "<div class='container'>" +
                    "<h2>在线客户端</h2>" +
                    "<ul>" + listHtml + "</ul>" +
                    "<p><a href='/?pwd=" + pwd + "'>返回首页</a></p>" +
                    "</div></body></html>";
            return newFixedLengthResponse(html);
        }

        private Response serveBlacklist(Map<String, String> params, String pwd) {
            if (params.containsKey("add")) {
                String ip = params.get("add");
                String blacklist = config.getProperty("blacklist", "");
                if (!blacklist.contains(ip)) {
                    blacklist += (blacklist.isEmpty() ? "" : ",") + ip;
                    config.setProperty("blacklist", blacklist);
                    saveConfig();
                }
                String html = "<html><head><title>成功</title><style>body{font-family:Arial;padding:20px;}</style></head><body>" +
                        "<h2>已拉黑 " + ip + "</h2><p><a href='/clients?pwd=" + pwd + "'>返回客户端列表</a></p></body></html>";
                return newFixedLengthResponse(html);
            } else if (params.containsKey("remove")) {
                String ip = params.get("remove");
                String blacklist = config.getProperty("blacklist", "");
                String[] items = blacklist.split(",");
                StringBuilder newList = new StringBuilder();
                for (String item : items) {
                    if (!item.equals(ip) && !item.isEmpty()) {
                        if (newList.length() > 0) newList.append(",");
                        newList.append(item);
                    }
                }
                config.setProperty("blacklist", newList.toString());
                saveConfig();
                String html = "<html><head><title>成功</title><style>body{font-family:Arial;padding:20px;}</style></head><body>" +
                        "<h2>已移除 " + ip + "</h2><p><a href='/blacklist?pwd=" + pwd + "'>返回黑名单</a></p></body></html>";
                return newFixedLengthResponse(html);
            } else {
                String blacklist = config.getProperty("blacklist", "");
                String[] items = blacklist.split(",");
                StringBuilder sb = new StringBuilder();
                sb.append("<html><head><title>黑名单</title><style>");
                sb.append("body{font-family:Arial;padding:20px;background:#f5f5f5;}");
                sb.append(".container{max-width:600px;margin:0 auto;background:#fff;padding:20px;border-radius:5px;box-shadow:0 2px 5px rgba(0,0,0,0.1);}");
                sb.append("h2{color:#333;}");
                sb.append("ul{list-style:none;padding:0;}");
                sb.append("li{padding:10px;border-bottom:1px solid #eee;}");
                sb.append("a{color:#4CAF50;text-decoration:none;margin-left:10px;}");
                sb.append("a:hover{text-decoration:underline;}");
                sb.append("</style></head><body>");
                sb.append("<div class='container'><h2>黑名单</h2><ul>");
                for (String ip : items) {
                    if (!ip.isEmpty()) {
                        sb.append("<li>").append(ip).append(" <a href='/blacklist?remove=").append(ip).append("&pwd=").append(pwd).append("'>移除</a></li>");
                    }
                }
                sb.append("</ul><a href='/?pwd=").append(pwd).append("'>返回首页</a></div></body></html>");
                return newFixedLengthResponse(sb.toString());
            }
        }

        /** 关机重启页面 */
        private Response servePower(String pwd) {
            String html = "<html><head><title>关机重启</title><style>" +
                    "body{font-family:Arial;padding:20px;background:#f5f5f5;}" +
                    ".container{max-width:400px;margin:0 auto;background:#fff;padding:20px;border-radius:5px;box-shadow:0 2px 5px rgba(0,0,0,0.1);}" +
                    "h2{color:#333;}" +
                    "a{display:inline-block;padding:10px 20px;margin:10px;background:#4CAF50;color:#fff;text-decoration:none;border-radius:3px;text-align:center;}" +
                    "a:hover{background:#45a049;}" +
                    "</style></head><body>" +
                    "<div class='container'>" +
                    "<h2>关机重启</h2>" +
                    "<a href='/do_power?action=shutdown&pwd=" + pwd + "'>立即关机</a> " +
                    "<a href='/do_power?action=reboot&pwd=" + pwd + "'>立即重启</a><br><br>" +
                    "<a href='/?pwd=" + pwd + "'>返回首页</a>" +
                    "</div></body></html>";
            return newFixedLengthResponse(html);
        }

        /** 执行关机或重启 */
        private Response doPower(Map<String, String> params, String pwd) {
            String action = params.get("action");
            final String command;
            if ("shutdown".equals(action)) {
                command = "reboot -p";
            } else if ("reboot".equals(action)) {
                command = "reboot";
            } else {
                return newFixedLengthResponse("无效操作");
            }

            new Thread(new Runnable() {
                @Override
                public void run() {
                    String result = execShellCommand(command);
                    Log.d(TAG, "Power command result: " + result);
                }
            }).start();

            String html = "<html><head><title>操作已执行</title><style>body{font-family:Arial;padding:20px;}</style></head><body>" +
                    "<h2>操作已执行，设备即将" + (action.equals("shutdown") ? "关机" : "重启") + "。</h2>" +
                    "<p><a href='/'>返回首页</a></p></body></html>";
            return newFixedLengthResponse(html);
        }

        private class ClientInfo {
            String ip;
            String mac;
            ClientInfo(String ip, String mac) {
                this.ip = ip;
                this.mac = mac;
            }
        }
    }
}