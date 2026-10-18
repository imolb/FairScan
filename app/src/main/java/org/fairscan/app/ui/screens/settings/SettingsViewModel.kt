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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.fairscan.app.AppContainer
import org.fairscan.app.data.OcrLanguage
import org.fairscan.app.domain.ExportQuality

data class SettingsUiState(
    val defaultColorMode: DefaultColorMode = DefaultColorMode.AUTO,
    val export: ExportSettingsUiState = ExportSettingsUiState(),
    val installedOcrLanguages: Set<String> = emptySet(),
    val enabledOcrLanguages: Set<String> = emptySet(),
    val currentDownload: OcrDownloadUiState? = null
)

data class ExportSettingsUiState(
    val folders: List<ExportFolder> = emptyList(),
    // Display name of the folder on the device, per folder URI. Null: not resolvable
    // (permission lost). Not yet resolved: entry missing.
    val folderNames: Map<String, String?> = emptyMap(),
    // Path of the folder on the device, per folder URI. Null: opaque or not resolvable.
    val folderPaths: Map<String, String?> = emptyMap(),
    val format: ExportFormat = ExportFormat.PDF,
    val quality: ExportQuality = ExportQuality.BALANCED,
    val filenameStyle: DefaultFilenameStyle = DefaultFilenameStyle.SCAN_DATE_TIME,
    val tagGroups: List<TagGroup> = emptyList(),
    // URI of a folder picked with the system folder picker, waiting for its given name
    val pendingNewFolderUri: String? = null,
)

data class OcrDownloadUiState(
    val language: OcrLanguage,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val failed: Boolean = false,
)

data class SettingsActions(
    /** Opens the system folder picker. */
    val onAddFolderClick: () -> Unit,
    val onFolderNameConfirmed: (uri: String, name: String) -> Unit,
    /** Called when the naming of the newly picked folder is cancelled. */
    val onCancelAddFolder: (uri: String) -> Unit,
    /** Called after the folder was removed from the settings. */
    val onDeleteFolder: (uri: String) -> Unit,
    val onRenameFolder: (uri: String, name: String) -> Unit,
    val onMoveFolder: (uri: String, up: Boolean) -> Unit,
    val onAddTagGroup: (name: String) -> Unit,
    val onDeleteTagGroup: (name: String) -> Unit,
    val onMoveTagGroup: (name: String, up: Boolean) -> Unit,
    val onAddTag: (groupName: String, tag: String) -> Unit,
    val onDeleteTag: (groupName: String, tagName: String) -> Unit,
    val onMoveTag: (groupName: String, tagName: String, up: Boolean) -> Unit,
    val onAddSynonym: (groupName: String, tagName: String, synonym: String) -> Unit,
    val onDeleteSynonym: (groupName: String, tagName: String, synonym: String) -> Unit,
)

class SettingsViewModel(container: AppContainer) : ViewModel() {

    private val repo = container.settingsRepository
    private val ocrLanguageRepo = container.ocrLanguageRepository
    private val logger = container.logger

    private val _installedLanguages = MutableStateFlow<Set<String>>(emptySet())
    private val _ocrDownload = MutableStateFlow<OcrDownloadUiState?>(null)
    private var downloadJob: Job? = null

    private val _folderNames = MutableStateFlow<Map<String, String?>>(emptyMap())
    private val _folderPaths = MutableStateFlow<Map<String, String?>>(emptyMap())
    private val _pendingNewFolderUri = MutableStateFlow<String?>(null)

    private val exportSettingsBase =
        combine(
            repo.exportFolders,
            _folderNames,
            _folderPaths,
            repo.tagGroups,
            _pendingNewFolderUri,
        ) { folders, names, paths, tagGroups, pendingNewFolderUri ->
            ExportSettingsUiState(
                folders = folders,
                folderNames = names,
                folderPaths = paths,
                tagGroups = tagGroups,
                pendingNewFolderUri = pendingNewFolderUri,
            )
        }

    private val exportSettingsState =
        combine(
            exportSettingsBase,
            repo.exportFormat,
            repo.exportQuality,
            repo.defaultFilenameStyle,
        ) { export, format, quality, filenameStyle ->
            export.copy(
                format = format,
                quality = quality,
                filenameStyle = filenameStyle,
            )
        }
    val uiState = combine(
        repo.defaultColorMode,
        exportSettingsState,
        _installedLanguages,
        ocrLanguageRepo.enabledLanguages,
        _ocrDownload,
    ) { colorMode, exportSettings, installed, enabled, download ->
        SettingsUiState(
            defaultColorMode = colorMode,
            export = exportSettings,
            installedOcrLanguages = installed,
            enabledOcrLanguages = enabled,
            currentDownload = download,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        SettingsUiState()
    )

    private suspend fun refreshInstalledLanguages() {
        _installedLanguages.value =
            ocrLanguageRepo.getInstalledLanguages()
    }

    init {
        viewModelScope.launch {
            refreshInstalledLanguages()
        }
    }

    fun setDefaultColorMode(pref: DefaultColorMode) {
        viewModelScope.launch {
            repo.setDefaultColorMode(pref)
        }
    }

    /** Called with the URI picked by the system folder picker. */
    fun beginAddExportFolder(uri: String) {
        _pendingNewFolderUri.value = uri
    }

    fun cancelAddExportFolder() {
        _pendingNewFolderUri.value = null
    }

    fun addExportFolder(uri: String, givenName: String) {
        _pendingNewFolderUri.value = null
        val name = givenName.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            val folders = repo.exportFolders.first()
            if (folders.none { it.uri == uri }) {
                repo.setExportFolders(folders + ExportFolder(uri, name))
                refreshFolderNames()
            }
        }
    }

    fun deleteExportFolder(uri: String) {
        viewModelScope.launch {
            repo.setExportFolders(
                repo.exportFolders.first().filterNot { it.uri == uri }
            )
            _folderNames.update { it - uri }
            _folderPaths.update { it - uri }
        }
    }

    fun renameExportFolder(uri: String, givenName: String) {
        val name = givenName.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            repo.setExportFolders(
                repo.exportFolders.first().map {
                    if (it.uri == uri) it.copy(givenName = name) else it
                }
            )
        }
    }

    fun moveExportFolder(uri: String, up: Boolean) {
        viewModelScope.launch {
            val folders = repo.exportFolders.first().toMutableList()
            val index = folders.indexOfFirst { it.uri == uri }
            val target = if (up) index - 1 else index + 1
            if (index in folders.indices && target in folders.indices) {
                val folder = folders.removeAt(index)
                folders.add(target, folder)
                repo.setExportFolders(folders)
            }
        }
    }

    fun refreshFolderNames() {
        viewModelScope.launch {
            val folders = repo.exportFolders.first()
            _folderNames.value = folders.associate { it.uri to repo.resolveExportDirName(it.uri) }
            _folderPaths.value = folders.associate { it.uri to repo.resolveExportDirPath(it.uri) }
        }
    }

    fun addTagGroup(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val groups = repo.tagGroups.first()
            if (groups.none { it.name == trimmed }) {
                repo.setTagGroups(groups + TagGroup(trimmed, emptyList()))
            }
        }
    }

    fun deleteTagGroup(name: String) {
        viewModelScope.launch {
            repo.setTagGroups(
                repo.tagGroups.first().filterNot { it.name == name }
            )
        }
    }

    fun moveTagGroup(name: String, up: Boolean) {
        viewModelScope.launch {
            val groups = repo.tagGroups.first().toMutableList()
            val index = groups.indexOfFirst { it.name == name }
            val target = if (up) index - 1 else index + 1
            if (index in groups.indices && target in groups.indices) {
                val group = groups.removeAt(index)
                groups.add(target, group)
                repo.setTagGroups(groups)
            }
        }
    }

    fun addTag(groupName: String, tag: String) {
        val trimmed = tag.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val groups = repo.tagGroups.first().map { group ->
                if (group.name == groupName && group.tags.none { it.name == trimmed }) {
                    group.copy(tags = group.tags + Tag(trimmed))
                } else {
                    group
                }
            }
            repo.setTagGroups(groups)
        }
    }

    fun deleteTag(groupName: String, tagName: String) {
        viewModelScope.launch {
            repo.setTagGroups(
                repo.tagGroups.first().map { group ->
                    if (group.name == groupName) {
                        group.copy(tags = group.tags.filterNot { it.name == tagName })
                    } else {
                        group
                    }
                }
            )
        }
    }

    fun moveTag(groupName: String, tagName: String, up: Boolean) {
        viewModelScope.launch {
            val groups = repo.tagGroups.first().map { group ->
                if (group.name != groupName) {
                    group
                } else {
                    val tags = group.tags.toMutableList()
                    val index = tags.indexOfFirst { it.name == tagName }
                    val target = if (up) index - 1 else index + 1
                    if (index in tags.indices && target in tags.indices) {
                        val tag = tags.removeAt(index)
                        tags.add(target, tag)
                        group.copy(tags = tags)
                    } else {
                        group
                    }
                }
            }
            repo.setTagGroups(groups)
        }
    }

    fun addSynonym(groupName: String, tagName: String, synonym: String) {
        val trimmed = synonym.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val groups = repo.tagGroups.first()
            val group = groups.firstOrNull { it.name == groupName } ?: return@launch
            // Synonyms are unique within the tag group: the new word must not
            // exist there as a tag name or another synonym
            val taken = group.tags.any { tag ->
                tag.name == trimmed || tag.synonyms.any { it == trimmed }
            }
            if (taken) return@launch
            val updated = group.copy(
                tags = group.tags.map { tag ->
                    if (tag.name == tagName) {
                        tag.copy(synonyms = tag.synonyms + trimmed)
                    } else {
                        tag
                    }
                }
            )
            repo.setTagGroups(groups.map { if (it.name == groupName) updated else it })
        }
    }

    fun deleteSynonym(groupName: String, tagName: String, synonym: String) {
        viewModelScope.launch {
            repo.setTagGroups(
                repo.tagGroups.first().map { group ->
                    if (group.name == groupName) {
                        group.copy(
                            tags = group.tags.map { tag ->
                                if (tag.name == tagName) {
                                    tag.copy(synonyms = tag.synonyms.filterNot { it == synonym })
                                } else {
                                    tag
                                }
                            }
                        )
                    } else {
                        group
                    }
                }
            )
        }
    }

    fun setExportFormat(format: ExportFormat) {
        viewModelScope.launch {
            repo.setExportFormat(format)
        }
    }

    fun setExportQuality(quality: ExportQuality) {
        viewModelScope.launch {
            repo.setExportQuality(quality)
        }
    }

    fun setDefaultFilenameStyle(style: DefaultFilenameStyle) {
        viewModelScope.launch {
            repo.setDefaultFilenameStyle(style)
        }
    }

    fun refreshExportDirName() {
        refreshFolderNames()
    }

    fun onLanguageClick(code: String) {
        if (uiState.value.installedOcrLanguages.contains(code)) {
            setOcrLanguageEnabled(code, !uiState.value.enabledOcrLanguages.contains(code))
        } else {
            installLanguage(code)
        }
    }

    fun installLanguage(code: String) {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            _ocrDownload.value = OcrDownloadUiState(OcrLanguage(code))
            try {
                ocrLanguageRepo.downloadLanguage(code) { progress ->
                    _ocrDownload.value =
                        _ocrDownload.value?.copy(
                            downloadedBytes = progress.downloadedBytes,
                            totalBytes = progress.totalBytes,
                        )
                }
                ocrLanguageRepo.setLanguageEnabled(code, true)
                refreshInstalledLanguages()
                _ocrDownload.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("OcrDownload", "Download failed for $code", e)
                _ocrDownload.value = _ocrDownload.value?.copy(failed = true)
            }
        }
    }

    fun cancelOcrDownload() {
        downloadJob?.cancel()
        _ocrDownload.value = null
    }

    fun setOcrLanguageEnabled(code: String, enabled: Boolean) {
        viewModelScope.launch {
            ocrLanguageRepo.setLanguageEnabled(code, enabled)
        }
    }

    fun onRemoveLanguage(code: String) {
        viewModelScope.launch {
            ocrLanguageRepo.deleteLanguage(code)
            refreshInstalledLanguages()
        }
    }
}
