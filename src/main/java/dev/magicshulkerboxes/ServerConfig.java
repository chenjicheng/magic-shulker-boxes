package dev.magicshulkerboxes;

public final class ServerConfig extends StorageConfig {
    public boolean allowPlayerSettings = false;
    public java.util.List<String> playerEditableSettings = new java.util.ArrayList<>(ConfigFile.optionNames());

    public boolean canEdit(String option) { return allowPlayerSettings && playerEditableSettings.contains(option); }
}
