package ai.chat2db.community.jcef.utils;

import ai.chat2db.community.tools.runtime.ProductRuntimeIdentity;
import ai.chat2db.community.tools.runtime.ProductRuntimeIdentityProvider;
import org.cef.OS;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.IOException;


public class UrlProtocolRegistrarUtil {

    private static String APP_NAME;
    private static String PROTOCOL_NAME;
    private static String REGISTRY_KEY_PATH = "HKEY_CURRENT_USER\\Software\\Classes\\" + PROTOCOL_NAME;


    public static void register() {
        ProductRuntimeIdentity product = ProductRuntimeIdentityProvider.current();
        APP_NAME = product.displayName();
        PROTOCOL_NAME = product.protocolScheme();
        REGISTRY_KEY_PATH = "HKEY_CURRENT_USER\\Software\\Classes\\" + PROTOCOL_NAME;
        if (!OS.isWindows()) {
            System.out.println("Non-Windows system; skipping registry registration.");
            return;
        }

        File tempRegFile = null;
        try {
            String exePath = findExecutablePath();
            if (exePath == null) {
                System.err.println("Unable to locate the application executable; registration failed.");
                return;
            }
            String regContent = generateRegFileContent(exePath);
            tempRegFile = File.createTempFile("chat2db_protocol_", ".reg");
            try (FileWriter writer = new FileWriter(tempRegFile)) {
                writer.write(regContent);
            }
            System.out.println("Attempting to import dynamically generated registry file: " + tempRegFile.getAbsolutePath());
            executeCommand("reg", "import", tempRegFile.getAbsolutePath());

        } catch (Exception e) {
            System.err.println("An error occurred while registering the URL protocol.");
            e.printStackTrace();
        } finally {
            if (tempRegFile != null && tempRegFile.exists()) {
                if (tempRegFile.delete()) {
                    System.out.println("Deleted temporary registry file: " + tempRegFile.getAbsolutePath());
                } else {
                    System.err.println("Failed to delete temporary registry file: " + tempRegFile.getAbsolutePath());
                }
            }
        }
    }


    private static boolean checkRegistryKeyExists() {
        try {
            ProcessBuilder pb = new ProcessBuilder("reg", "query", REGISTRY_KEY_PATH);

            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);

            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            System.err.println("Error while checking the registry: " + e.getMessage());
            return false;
        }
    }


    private static String generateRegFileContent(String exePath) {
        String escapedExePath = exePath.replace("\\", "\\\\");

        return "Windows Registry Editor Version 5.00\r\n" +
                "\r\n" +
                "; This file was generated dynamically by " + APP_NAME + "\r\n" +
                "\r\n" +
                "[HKEY_CURRENT_USER\\Software\\Classes\\" + PROTOCOL_NAME + "]\r\n" +
                "@=\"URL:" + PROTOCOL_NAME + " Protocol\"\r\n" +
                "\"URL Protocol\"=\"\"\r\n" +
                "\r\n" +
                "[HKEY_CURRENT_USER\\Software\\Classes\\" + PROTOCOL_NAME + "\\DefaultIcon]\r\n" +
                "@=\"" + escapedExePath + ",0\"\r\n" +
                "\r\n" +
                "[HKEY_CURRENT_USER\\Software\\Classes\\" + PROTOCOL_NAME + "\\shell]\r\n" +
                "@=\"open\"\r\n" +
                "\r\n" +
                "[HKEY_CURRENT_USER\\Software\\Classes\\" + PROTOCOL_NAME + "\\shell\\open]\r\n" +
                "@=\"Open with " + APP_NAME + "\"\r\n" +
                "\r\n" +
                "[HKEY_CURRENT_USER\\Software\\Classes\\" + PROTOCOL_NAME + "\\shell\\open\\command]\r\n" +
                "@=\"\\\"" + escapedExePath + "\\\" \\\"%1\\\"\"\r\n";
    }


    private static String findExecutablePath() {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isEmpty()) {
            System.err.println("The java.home property is not set.");
            return null;
        }

        File runtimeDir = new File(javaHome);
        File parentDir = runtimeDir.getParentFile();

        if (parentDir != null && parentDir.isDirectory()) {
            File exeFile = new File(parentDir, APP_NAME + ".exe");
            if (exeFile.exists() && !exeFile.isDirectory()) {
                System.out.println("Found application executable: " + exeFile.getAbsolutePath());
                return exeFile.getAbsolutePath();
            }
        }

        String searchDir = (parentDir != null) ? parentDir.getAbsolutePath() : "null";
        System.err.println("Application executable not found in directory " + searchDir + ": '" + APP_NAME + ".exe'");
        return null;
    }


    private static void executeCommand(String... command) throws IOException, InterruptedException {
        System.out.println("Executing command: " + String.join(" ", command));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
            }
        }

        int exitCode = process.waitFor();

        System.out.println("Command completed with exit code: " + exitCode);
        System.out.println("Command output:\n" + output);

        if (exitCode != 0) {
            System.err.println("Command failed.");
        }
    }
}
