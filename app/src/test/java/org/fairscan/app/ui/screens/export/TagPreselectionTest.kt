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

class TagPreselectionTest {

    @Test
    fun matching_is_case_insensitive_substring() {
        val groups = listOf(TagGroup("Typ", listOf(Tag("Rechnung"))))
        val result = TagPreselection.preselect(groups, listOf("Dies ist eine RECHNUNG 2026"))
        assertThat(result).isEqualTo(mapOf("Typ" to "Rechnung"))
    }

    @Test
    fun earliest_position_in_page_order_wins() {
        val groups = listOf(
            TagGroup("Typ", listOf(Tag("Lieferschein"), Tag("Rechnung")))
        )
        val result = TagPreselection.preselect(
            groups,
            listOf("Hier steht irgendwo eine Rechnung", "Lieferschein vom Vortag"),
        )
        assertThat(result).isEqualTo(mapOf("Typ" to "Rechnung"))
    }

    @Test
    fun groups_without_match_are_not_preselected() {
        val groups = listOf(
            TagGroup("Typ", listOf(Tag("Rechnung"))),
            TagGroup("Projekt", listOf(Tag("Alpha"))),
        )
        val result = TagPreselection.preselect(groups, listOf("Rechnung ohne Projektangabe"))
        assertThat(result).isEqualTo(mapOf("Typ" to "Rechnung"))
    }

    @Test
    fun no_match_at_all() {
        val groups = listOf(TagGroup("Typ", listOf(Tag("Rechnung"))))
        val result = TagPreselection.preselect(groups, listOf("Kein Treffer hier"))
        assertThat(result).isEmpty()
    }

    @Test
    fun each_group_is_matched_independently() {
        val groups = listOf(
            TagGroup("Typ", listOf(Tag("Rechnung"), Tag("Lieferschein"))),
            TagGroup("Status", listOf(Tag("Bezahlt"), Tag("Offen"))),
        )
        val result = TagPreselection.preselect(
            groups,
            listOf("Lieferschein bezahlt", "Rechnung noch offen"),
        )
        assertThat(result).isEqualTo(mapOf("Typ" to "Lieferschein", "Status" to "Bezahlt"))
    }

    @Test
    fun synonym_matches_the_tag() {
        val groups = listOf(
            TagGroup("Typ", listOf(Tag("Rechnung", listOf("Invoice", "Bill"))))
        )
        val result = TagPreselection.preselect(groups, listOf("Please pay this invoice soon"))
        assertThat(result).isEqualTo(mapOf("Typ" to "Rechnung"))
    }

    @Test
    fun tag_name_wins_when_earlier_than_synonym_of_other_tag() {
        val groups = listOf(
            TagGroup(
                "Typ",
                listOf(
                    Tag("Rechnung", listOf("Invoice")),
                    Tag("Lieferschein", listOf("Delivery note")),
                ),
            )
        )
        val result = TagPreselection.preselect(
            groups,
            listOf("Rechnung mit Delivery note hinten"),
        )
        assertThat(result).isEqualTo(mapOf("Typ" to "Rechnung"))
    }

    @Test
    fun earliest_occurrence_wins_between_name_and_synonym() {
        val groups = listOf(
            TagGroup(
                "Typ",
                listOf(
                    Tag("Rechnung"),
                    Tag("Lieferschein", listOf("Lieferscheinnummer")),
                ),
            )
        )
        // "Rechnung" appears later than "Lieferscheinnummer" in the text
        val result = TagPreselection.preselect(
            groups,
            listOf("Lieferscheinnummer 47, dann Rechnung"),
        )
        assertThat(result).isEqualTo(mapOf("Typ" to "Lieferschein"))
    }

    @Test
    fun first_match_index_returns_earliest_matching_entry() {
        val index = TagPreselection.firstMatchIndex(
            listOf(listOf("Finanzen"), listOf("Privat")),
            listOf("blah", "Privater Bereich hier"),
        )
        assertThat(index).isEqualTo(1)
    }

    @Test
    fun first_match_index_case_insensitive_and_position_based() {
        val index = TagPreselection.firstMatchIndex(
            listOf(listOf("Beta"), listOf("Alpha")),
            listOf("ALPHA am Anfang", "Beta danach"),
        )
        assertThat(index).isEqualTo(1)
    }

    @Test
    fun first_match_index_no_match_returns_null() {
        val index = TagPreselection.firstMatchIndex(
            listOf(listOf("Beta"), listOf("Alpha")),
            listOf("nichts passendes"),
        )
        assertThat(index).isNull()
    }

    @Test
    fun first_match_index_blank_labels_never_match() {
        val index = TagPreselection.firstMatchIndex(
            listOf(listOf(""), listOf("  ")),
            listOf("beliebiger Text"),
        )
        assertThat(index).isNull()
    }

    @Test
    fun first_match_index_considers_all_labels_of_an_entry() {
        val index = TagPreselection.firstMatchIndex(
            listOf(listOf("Finanzen"), listOf("Privat", "Persönlich")),
            listOf("Hier: persönliche Dokumente", "Finanzen"),
        )
        assertThat(index).isEqualTo(1)
    }
}
