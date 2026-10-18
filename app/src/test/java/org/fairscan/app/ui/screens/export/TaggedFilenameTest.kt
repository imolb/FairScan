/*
 * Copyright 2025-2026 The FairScan authors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.fairscan.app.ui.screens.export

import org.assertj.core.api.Assertions.assertThat
import org.fairscan.app.ui.screens.settings.Tag
import org.fairscan.app.ui.screens.settings.TagGroup
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TaggedFilenameTest {

    private val date: Date = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).parse("2026-10-16")!!

    private val groups = listOf(
        TagGroup(
            "Dokumenttyp",
            listOf(Tag("Rechnung"), Tag("Lieferschein")),
        ),
        TagGroup(
            "Projekt",
            listOf(Tag("Alpha"), Tag("Beta")),
        ),
    )

    @Test
    fun full_selection() {
        val filename = TaggedFilename.proposal(
            date,
            groups,
            mapOf("Dokumenttyp" to "Rechnung", "Projekt" to "Alpha"),
            "Finanzen",
        )
        assertThat(filename).isEqualTo("2026-10-16_Rechnung-Alpha-Finanzen")
    }

    @Test
    fun empty_groups_are_skipped() {
        val filename = TaggedFilename.proposal(
            date,
            groups,
            mapOf("Dokumenttyp" to "Rechnung"),
            "Finanzen",
        )
        assertThat(filename).isEqualTo("2026-10-16_Rechnung-Finanzen")
    }

    @Test
    fun no_tags_at_all() {
        val filename = TaggedFilename.proposal(
            date,
            groups,
            emptyMap(),
            "Finanzen",
        )
        assertThat(filename).isEqualTo("2026-10-16_Finanzen")
    }

    @Test
    fun no_export_folder_configured() {
        val filename = TaggedFilename.proposal(
            date,
            groups,
            mapOf("Dokumenttyp" to "Lieferschein"),
            null,
        )
        assertThat(filename).isEqualTo("2026-10-16_Lieferschein")
    }

    @Test
    fun nothing_selected() {
        val filename = TaggedFilename.proposal(date, groups, emptyMap(), null)
        assertThat(filename).isEqualTo("2026-10-16")
    }

    @Test
    fun illegal_characters_are_replaced() {
        val filename = TaggedFilename.proposal(
            date,
            listOf(TagGroup("G", listOf(Tag("Rechnung/2026:*")))),
            mapOf("G" to "Rechnung/2026:*"),
            "Vor<stand>?",
        )
        assertThat(filename).isEqualTo("2026-10-16_Rechnung-2026---Vor-stand--")
    }

    @Test
    fun sanitize() {
        assertThat(TaggedFilename.sanitize("a/b\\c:d*e?f\"g<h>i|j")).isEqualTo("a-b-c-d-e-f-g-h-i-j")
        assertThat(TaggedFilename.sanitize("  Rechnung  ")).isEqualTo("Rechnung")
        assertThat(TaggedFilename.sanitize("")).isEqualTo("")
    }
}
