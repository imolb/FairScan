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

import org.fairscan.app.ui.screens.settings.TagGroup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Proposal of the filename for the save step after a scan:
 * YYYY-MM-DD_<tag of group 1>-<tag of group 2>-…-<export folder given name>
 * Tag groups without a selected tag are skipped, separators collapse.
 * The export folder given name is omitted when null (no export folder configured).
 * Does not include the extension.
 */
object TaggedFilename {

    private val ILLEGAL_FILENAME_CHARS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)

    /** Replaces characters which are not allowed in a filename by "-". */
    fun sanitize(segment: String): String =
        ILLEGAL_FILENAME_CHARS.replace(segment.trim(), "-")

    fun proposal(
        date: Date,
        tagGroups: List<TagGroup>,
        selectedTags: Map<String, String>,
        exportFolderName: String?,
    ): String {
        val dateSegment = DATE_FORMAT.format(date)
        // The selected tags in group order, followed by the export folder given
        // name, all joined with "-". Empty groups are skipped.
        val parts = tagGroups.mapNotNull { group ->
            selectedTags[group.name]?.takeIf { it.isNotBlank() }?.let { sanitize(it) }
        }.toMutableList()
        exportFolderName?.let { name ->
            val sanitized = sanitize(name)
            if (sanitized.isNotEmpty()) {
                parts.add(sanitized)
            }
        }
        return if (parts.isEmpty()) {
            dateSegment
        } else {
            "${dateSegment}_${parts.joinToString("-")}"
        }
    }
}

/**
 * Preselection in the save step after a scan, based on the recognized text.
 *
 * For each tag group, selects the tag whose name or synonym appears first in the
 * scanned text. Matching is case-insensitive substring. The text of the pages is
 * processed in page order; within a page, the earliest occurrence wins. Tags
 * without a match get no preselection.
 */
object TagPreselection {

    /**
     * @param tagGroups the tag groups of the settings, in order
     * @param pageTexts the recognized text of the scan, one entry per page, in page order
     * @return map from tag group name to the preselected tag name. Tag groups
     *   without a match are not in the map. Only the tag name is ever selected;
     *   synonyms only influence which tag wins.
     */
    fun preselect(
        tagGroups: List<TagGroup>,
        pageTexts: List<String>,
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (group in tagGroups) {
            val labelSets = group.tags.map { listOf(it.name) + it.synonyms }
            val bestIndex = firstMatchIndex(labelSets, pageTexts)
            if (bestIndex != null) {
                result[group.name] = group.tags[bestIndex].name
            }
        }
        return result
    }

    /**
     * Index of the entry whose labels appear first in the scanned text, or null
     * if none matches. Each entry is matched against all of its labels (e.g.
     * tag name and synonyms): case-insensitive substring, the earliest
     * occurrence of any label wins. The text of the pages is processed in page
     * order and, within a page, the earliest occurrence wins. Blank labels never
     * match.
     */
    fun firstMatchIndex(labelSets: List<List<String>>, pageTexts: List<String>): Int? {
        val pageTextsLowerCase = pageTexts.map { it.lowercase() }
        var bestEntryIndex = -1
        var bestPageIndex = Int.MAX_VALUE
        var bestIndexInPage = Int.MAX_VALUE
        for ((entryIndex, labels) in labelSets.withIndex()) {
            for (label in labels) {
                val needle = label.trim().lowercase()
                if (needle.isEmpty()) continue
                for ((pageIndex, text) in pageTextsLowerCase.withIndex()) {
                    val index = text.indexOf(needle)
                    if (index < 0) continue
                    if (pageIndex < bestPageIndex ||
                        (pageIndex == bestPageIndex && index < bestIndexInPage)
                    ) {
                        bestEntryIndex = entryIndex
                        bestPageIndex = pageIndex
                        bestIndexInPage = index
                    }
                    // Earliest occurrence of this label found in the earliest
                    // possible page: later pages cannot win.
                    break
                }
            }
        }
        return if (bestEntryIndex >= 0) bestEntryIndex else null
    }
}
