package com.swiftmq.mcp.config;

/** Invalid or incomplete launch settings. The message is meant for the user. */
public class SettingsException extends Exception {
    public SettingsException(String message) {
        super(message);
    }
}
