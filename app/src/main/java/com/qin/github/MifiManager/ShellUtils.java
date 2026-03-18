package com.qin.github.MifiManager;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;

public class ShellUtils {

    public static String execCommand(String command, boolean isRoot) {
        StringBuilder output = new StringBuilder();
        Process process = null;
        DataOutputStream os = null;
        BufferedReader successReader = null;
        BufferedReader errorReader = null;

        try {
            process = Runtime.getRuntime().exec(isRoot ? "su" : "sh");
            os = new DataOutputStream(process.getOutputStream());
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();

            successReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));

            String line;
            while ((line = successReader.readLine()) != null) {
                output.append(line).append("\n");
            }
            while ((line = errorReader.readLine()) != null) {
                output.append(line).append("\n");
            }

            process.waitFor();
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            output.append("Error: ").append(e.getMessage());
        } finally {
            try {
                if (os != null) os.close();
                if (successReader != null) successReader.close();
                if (errorReader != null) errorReader.close();
                if (process != null) process.destroy();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return output.toString().trim();
    }

    public static boolean execCommandSuccess(String command, boolean isRoot) {
        String result = execCommand(command, isRoot);
        return !result.contains("Error") && !result.contains("not found") && !result.contains("Permission denied");
    }
}