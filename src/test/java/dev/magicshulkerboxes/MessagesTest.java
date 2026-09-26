package dev.magicshulkerboxes;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MessagesTest {
    @Test
    void bothLanguagesCoverEveryOptionWithReadableFallbacks() {
        assertEquals(Messages.keys("en_us"), Messages.keys("zh_cn"));
        assertTrue(Messages.pattern("zh_cn", "title").contains("潜影盒"));
        assertTrue(Messages.pattern("en_us", "title").contains("Shulker"));
        assertEquals(Messages.pattern("en_us", "title"), Messages.pattern("de_de", "title"));
        for (var option : ConfigFile.optionNames()) {
            assertTrue(Messages.keys("zh_cn").contains("option." + option), option);
        }
        for (var key : Messages.keys("en_us")) {
            assertEquals(Messages.pattern("en_us", key).split("%s", -1).length,
                    Messages.pattern("zh_cn", key).split("%s", -1).length, key);
        }
    }
}
