package com.kerim.reclip.config;

/**
 * Persistence abstraction for user preferences.
 */
public interface SettingsRepository {

    /**
     * Loads persisted settings, falling back to {@link AppSettings#defaults()}.
     *
     * @return loaded settings
     */
    AppSettings load();

    /**
     * Persists the given settings.
     *
     * @param settings settings to save
     */
    void save(AppSettings settings);
}
