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
package org.fairscan.app.ui.screens.settings

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class ExportTargetsCodecTest {

    @Test
    fun export_folders_round_trip() {
        val folders = listOf(
            ExportFolder("content://tree/primary%3ADocuments", "Finanzen"),
            ExportFolder("content://tree/secondary%3AScans", "Privat"),
        )
        val encoded = ExportFoldersCodec.encode(folders)
        assertThat(ExportFoldersCodec.decode(encoded)).isEqualTo(folders)
    }

    @Test
    fun export_folders_decode_empty() {
        assertThat(ExportFoldersCodec.decode(null)).isEmpty()
        assertThat(ExportFoldersCodec.decode("")).isEmpty()
    }

    @Test
    fun export_folders_decode_invalid() {
        assertThat(ExportFoldersCodec.decode("not json")).isEmpty()
    }

    @Test
    fun tag_groups_round_trip() {
        val groups = listOf(
            TagGroup(
                "Dokumenttyp",
                listOf(Tag("Rechnung", listOf("Invoice")), Tag("Lieferschein")),
            ),
            TagGroup("Projekt"),
        )
        val encoded = TagGroupsCodec.encode(groups)
        assertThat(TagGroupsCodec.decode(encoded)).isEqualTo(groups)
    }

    @Test
    fun tag_groups_decode_migrates_previous_format() {
        // Tags were plain strings before synonyms existed
        val legacy = """[{"name":"Dokumenttyp","tags":["Rechnung","Lieferschein"]}]"""
        assertThat(TagGroupsCodec.decode(legacy)).isEqualTo(
            listOf(
                TagGroup(
                    "Dokumenttyp",
                    listOf(Tag("Rechnung"), Tag("Lieferschein")),
                )
            )
        )
    }

    @Test
    fun tag_groups_decode_empty_and_invalid() {
        assertThat(TagGroupsCodec.decode(null)).isEmpty()
        assertThat(TagGroupsCodec.decode("")).isEmpty()
        assertThat(TagGroupsCodec.decode("not json")).isEmpty()
    }

    @Test
    fun tag_groups_decode_drops_empty_names_and_tags() {
        val groups = listOf(
            TagGroup("  Typ  ", listOf(Tag(" Rechnung "), Tag("  "))),
            TagGroup("", listOf(Tag("X"))),
        )
        val decoded = TagGroupsCodec.decode(TagGroupsCodec.encode(groups))
        assertThat(decoded).isEqualTo(listOf(TagGroup("Typ", listOf(Tag("Rechnung")))))
    }

    @Test
    fun tag_groups_decode_trims_synonyms_and_drops_empty_ones() {
        val groups = listOf(
            TagGroup(
                "Typ",
                listOf(Tag("Rechnung", listOf(" Invoice ", "", "  "))),
            )
        )
        val decoded = TagGroupsCodec.decode(TagGroupsCodec.encode(groups))
        assertThat(decoded).isEqualTo(
            listOf(TagGroup("Typ", listOf(Tag("Rechnung", listOf("Invoice")))))
        )
    }
}
