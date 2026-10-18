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

import android.content.Context
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.fairscan.app.R
import org.fairscan.app.domain.ExportQuality
import org.fairscan.imageprocessing.ColorMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsRepository(
    private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
) {

    private val DEFAULT_COLOR_MODE = stringPreferencesKey("default_color_mode")
    private val DEFAULT_FILENAME_STYLE = stringPreferencesKey("default_filename_style")
    private val EXPORT_DIR_URI = stringPreferencesKey("export_dir_uri")
    private val EXPORT_FOLDERS = stringPreferencesKey("export_folders")
    private val TAG_GROUPS = stringPreferencesKey("tag_groups")
    private val EXPORT_FORMAT = stringPreferencesKey("export_format")
    private val EXPORT_QUALITY = stringPreferencesKey("export_quality")

    val defaultColorMode: Flow<DefaultColorMode> =
        dataStore.data.map { prefs ->
            when (prefs[DEFAULT_COLOR_MODE]) {
                "AUTO" -> DefaultColorMode.AUTO
                "COLOR" -> DefaultColorMode.COLOR
                "GRAYSCALE" -> DefaultColorMode.GRAYSCALE
                "BLACK_AND_WHITE" -> DefaultColorMode.BLACK_AND_WHITE
                else -> DefaultColorMode.AUTO
            }
        }

    /**
     * The export folder list. Migrates the legacy single export folder
     * (export_dir_uri) as first entry, named after the folder display name.
     * The legacy key is removed with the first write of the list.
     */
    val exportFolders: Flow<List<ExportFolder>> =
        dataStore.data.map { prefs ->
            val stored = prefs[EXPORT_FOLDERS]
            if (stored != null) {
                ExportFoldersCodec.decode(stored)
            } else {
                val legacyUri = prefs[EXPORT_DIR_URI]
                if (legacyUri != null) {
                    val name = withContext(Dispatchers.IO) {
                        resolveExportDirName(legacyUri)
                    } ?: context.getString(R.string.export_folder_default_name)
                    listOf(ExportFolder(legacyUri, name))
                } else {
                    emptyList()
                }
            }
        }

    val tagGroups: Flow<List<TagGroup>> =
        dataStore.data.map { prefs ->
            TagGroupsCodec.decode(prefs[TAG_GROUPS])
        }

    fun resolveExportDirName(uri: String): String? {
        return DocumentFile.fromTreeUri(context, uri.toUri())?.name
    }

    /**
     * Path of the folder on the device, derived from the SAF tree document ID
     * (e.g. "primary:Documents/Scans" -> "Documents/Scans"). Null if the
     * document ID is opaque (e.g. cloud providers) or cannot be resolved.
     */
    fun resolveExportDirPath(uri: String): String? {
        return try {
            val documentId = DocumentsContract.getTreeDocumentId(uri.toUri())
                ?: return null
            val path = documentId.substringAfter(':', "")
            path.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    val exportFormat: Flow<ExportFormat> =
        dataStore.data.map { prefs ->
            when (prefs[EXPORT_FORMAT]) {
                "JPEG" -> ExportFormat.JPEG
                "PDF", null -> ExportFormat.PDF
                else -> ExportFormat.PDF
            }
        }

    val exportQuality: Flow<ExportQuality> =
        dataStore.data.map { prefs ->
            when (prefs[EXPORT_QUALITY]) {
                "LOW" -> ExportQuality.LOW
                "HIGH" -> ExportQuality.HIGH
                "BALANCED", null -> ExportQuality.BALANCED
                else -> ExportQuality.BALANCED
            }
        }

    val defaultFilenameStyle: StateFlow<DefaultFilenameStyle> =
        dataStore.data
            .map { prefs ->
                when (prefs[DEFAULT_FILENAME_STYLE]) {
                    "DATE" -> DefaultFilenameStyle.DATE
                    "SCAN_DATE_TIME" -> DefaultFilenameStyle.SCAN_DATE_TIME
                    else -> DefaultFilenameStyle.SCAN_DATE_TIME
                }
            }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = DefaultFilenameStyle.SCAN_DATE_TIME
            )

    suspend fun setDefaultColorMode(mode: DefaultColorMode) {
        dataStore.edit { prefs ->
            prefs[DEFAULT_COLOR_MODE] = mode.name
        }
    }

    suspend fun setExportFolders(folders: List<ExportFolder>) {
        dataStore.edit { prefs ->
            prefs[EXPORT_FOLDERS] = ExportFoldersCodec.encode(folders)
            // The legacy single export folder is replaced by the list
            prefs.remove(EXPORT_DIR_URI)
        }
    }

    suspend fun setTagGroups(groups: List<TagGroup>) {
        dataStore.edit { prefs ->
            prefs[TAG_GROUPS] = TagGroupsCodec.encode(groups)
        }
    }

    suspend fun setExportFormat(format: ExportFormat) {
        dataStore.edit { prefs ->
            prefs[EXPORT_FORMAT] = format.name
        }
    }

    suspend fun setExportQuality(quality: ExportQuality) {
        dataStore.edit { prefs ->
            prefs[EXPORT_QUALITY] = quality.name
        }
    }

    suspend fun setDefaultFilenameStyle(style: DefaultFilenameStyle) {
        dataStore.edit { prefs ->
            prefs[DEFAULT_FILENAME_STYLE] = style.name
        }
    }
}

enum class DefaultColorMode(val colorMode: ColorMode?, val labelResource: Int) {
    AUTO(null, R.string.color_mode_auto),
    COLOR(ColorMode.COLOR, R.string.color_mode_color),
    GRAYSCALE(ColorMode.GRAYSCALE, R.string.color_mode_grayscale),
    BLACK_AND_WHITE(ColorMode.BLACK_AND_WHITE, R.string.color_mode_black_and_white),
}

enum class ExportFormat(val mimeType: String) {
    PDF("application/pdf"),
    JPEG("image/jpeg"),
}

enum class DefaultFilenameStyle(val pattern: String) {
    SCAN_DATE_TIME("'Scan' yyyy-MM-dd HH.mm.ss"),
    DATE("yyyy-MM-dd");

    fun filename(): String = filename(Date())

    fun filename(date: Date): String =
        SimpleDateFormat(pattern, Locale.ENGLISH).format(date)
}
