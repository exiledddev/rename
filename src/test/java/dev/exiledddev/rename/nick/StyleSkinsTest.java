package dev.exiledddev.rename.nick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class StyleSkinsTest {

    @Test
    void turnsNameMcLinksAndIdsIntoImageUrls() {
        assertEquals("https://s.namemc.com/i/3d6175ba0c5f01f5.png", StyleSkins.namemcImage("https://namemc.com/skin/3d6175ba0c5f01f5"));
        assertEquals("https://s.namemc.com/i/1fe28a034bc72b5e.png", StyleSkins.namemcImage("1fe28a034bc72b5e"));
        assertEquals("https://s.namemc.com/i/462e7688f7efc115.png", StyleSkins.namemcImage("https://namemc.com/skin/462E7688F7EFC115/"));
        assertNull(StyleSkins.namemcImage("not a skin"));
    }

    @Test
    void readsMineskinV2Responses() {
        final Skin skin = StyleSkins.parseMineskin("""
            {"success": true, "skin": {"uuid": "x", "texture": {"data": {"value": "VALUE", "signature": "SIG"}, "hash": {}}}}
            """);
        assertNotNull(skin);
        assertEquals("VALUE", skin.value());
        assertEquals("SIG", skin.signature());
    }

    @Test
    void readsMineskinV1Responses() {
        final Skin skin = StyleSkins.parseMineskin("""
            {"id": 1, "data": {"uuid": "x", "texture": {"value": "VALUE", "signature": "SIG", "url": "u"}}}
            """);
        assertNotNull(skin);
        assertEquals("SIG", skin.signature());
    }

    @Test
    void returnsNullWithoutATexture() {
        assertNull(StyleSkins.parseMineskin("{\"success\": false, \"errors\": []}"));
    }
}
