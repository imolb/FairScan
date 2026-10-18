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

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One entry of the export folder list of the settings. */
@Serializable
data class ExportFolder(
    /** SAF tree URI of the folder on the device. */
    val uri: String,
    /** Name given by the user, not related to the folder/path. */
    val givenName: String,
)

/** One entry of the tag groups of the settings. Group names must be unique. */
@Serializable
data class TagGroup(
    val name: String,
    /** Tag names must be unique within the group. */
    val tags: List<Tag> = emptyList(),
)

/**
 * A tag with optional synonyms. Synonyms are only considered for matching in
 * the scanned text; the tag name always represents the tag (chips, filename).
 */
@Serializable
data class Tag(
    val name: String,
    val synonyms: List<String> = emptyList(),
)

/** Name of the given name for the folder */
fun ExportFolder.displayName(): String = givenName.ifBlank { uri }

/** Serialization of the export folder list for storage in the DataStore. */
object ExportFoldersCodec {
    fun encode(folders: List<ExportFolder>): String = Json.encodeToString(folders)

    fun decode(stored: String?): List<ExportFolder> {
        if (stored.isNullOrBlank()) return emptyList()
        return try {
            Json.decodeFromString<List<ExportFolder>>(stored)
                .filter { it.uri.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/**
 * Serialization of the tag groups for storage in the DataStore. Decodes the
 * previous format (tags as plain strings) as well as the current one.
 */
object TagGroupsCodec {
    fun encode(groups: List<TagGroup>): String = Json.encodeToString(groups)

    fun decode(stored: String?): List<TagGroup> {
        if (stored.isNullOrBlank()) return emptyList()
        return try {
            val element = Json.parseToJsonElement(stored)
            element.jsonArray.mapNotNull { groupEntry ->
                decodeGroup(groupEntry)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun decodeGroup(groupEntry: JsonElement): TagGroup? {
        val group = groupEntry.jsonObject
        val groupName = group.getString("name")?.trim().orEmpty()
        if (groupName.isEmpty()) return null
        val tags = group["tags"]?.jsonArray?.mapNotNull { tagEntry ->
            decodeTag(tagEntry)
        }.orEmpty()
        return TagGroup(groupName, tags)
    }

    private fun decodeTag(tagEntry: JsonElement): Tag? {
        return when {
            tagEntry is JsonPrimitive -> {
                val name = tagEntry.content.trim()
                if (name.isNotEmpty()) Tag(name) else null
            }
            else -> {
                val tag = tagEntry.jsonObject
                val tagName = tag.getString("name")?.trim().orEmpty()
                if (tagName.isEmpty()) return null
                val synonyms = tag["synonyms"]?.jsonArray
                    ?.mapNotNull { synonym ->
                        synonym.jsonPrimitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                    }
                    .orEmpty()
                Tag(tagName, synonyms)
            }
        }
    }

    private fun JsonObject.getString(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull
}
