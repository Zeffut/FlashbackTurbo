package fr.zeffut.flashbackturbo.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TurboConfigSettingTest {

    @Test
    void settingMapsAutoUpdateKeysWithDefaults() {
        TurboConfig c = TurboConfig.current();
        assertEquals("true", c.setting("auto_update", "true"));
        assertEquals("false", c.setting("update_all", "false"));
        assertEquals("Zeffut", c.setting("update_owner", "x"));
        assertEquals("", c.setting("update_exclude", "fallback"));
    }

    @Test
    void settingReturnsFallbackForUnknownKey() {
        assertEquals("def", TurboConfig.current().setting("nope", "def"));
    }
}
