package com.example.mobilemathstudio.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLanguageTest {

    @Test
    fun localeTagsMapToEnglishAndRussian() {
        assertNull(AppLanguage.System.localeTag())
        assertEquals("en", AppLanguage.English.localeTag())
        assertEquals("ru", AppLanguage.Russian.localeTag())
    }
}
