package com.tashichi.clipflow.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tashichi.clipflow.data.model.Project
import com.tashichi.clipflow.data.repository.ProjectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ProjectListViewModel - プロジェクト一覧画面のViewModel
 *
 * iOS版参考実装:
 * - ProjectListView.swift (@ObservedObject var projectManager: ProjectManager)
 * - ProjectManager.swift (プロジェクト管理ロジック)
 *
 * 主な機能:
 * - プロジェクト一覧の取得・表示
 * - 新規プロジェクト作成
 * - プロジェクトの選択
 * - プロジェクトの削除
 * - プロジェクト名の変更
 * - UIStateの管理（ローディング、エラー等）
 */
class ProjectListViewModel(application: Application) : AndroidViewModel(application) {

    // ProjectRepository
    private val repository = ProjectRepository(application)

    /**
     * プロジェクト一覧
     * iOS版: @Published var projects: [Project]
     */
    val projects: StateFlow<List<Project>> = repository.getAllProjects()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /**
     * ローディング状態
     */
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * エラーメッセージ
     */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * 削除確認ダイアログの表示状態
     */
    private val _showDeleteDialog = MutableStateFlow(false)
    val showDeleteDialog: StateFlow<Boolean> = _showDeleteDialog.asStateFlow()

    /**
     * 削除対象のプロジェクト
     */
    private val _projectToDelete = MutableStateFlow<Project?>(null)
    val projectToDelete: StateFlow<Project?> = _projectToDelete.asStateFlow()

    /**
     * 名前変更ダイアログの表示状態
     */
    private val _showRenameDialog = MutableStateFlow(false)
    val showRenameDialog: StateFlow<Boolean> = _showRenameDialog.asStateFlow()

    /**
     * 名前変更対象のプロジェクト
     */
    private val _projectToRename = MutableStateFlow<Project?>(null)
    val projectToRename: StateFlow<Project?> = _projectToRename.asStateFlow()

    /**
     * 選択されたプロジェクト
     */
    private val _selectedProject = MutableStateFlow<Project?>(null)
    val selectedProject: StateFlow<Project?> = _selectedProject.asStateFlow()

    /**
     * 新規プロジェクトを作成
     * iOS版参考: ProjectManager.swift:21-30 (createNewProject)
     *
     * @param name プロジェクト名（指定しない場合は自動採番）
     */
    fun createNewProject(name: String? = null) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _errorMessage.value = null

                val newProject = repository.createProject(name)

                // 作成したプロジェクトを選択状態にする
                _selectedProject.value = newProject

            } catch (e: Exception) {
                _errorMessage.value = "Failed to create project: ${e.message}"
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * プロジェクトを選択
     *
     * @param project 選択するプロジェクト
     */
    fun selectProject(project: Project) {
        _selectedProject.value = project
    }

    /**
     * プロジェクトの削除確認ダイアログを表示
     * iOS版参考: ProjectListView.swift (削除アクション)
     *
     * @param project 削除対象のプロジェクト
     */
    fun showDeleteConfirmation(project: Project) {
        _projectToDelete.value = project
        _showDeleteDialog.value = true
    }

    /**
     * 削除確認ダイアログを閉じる
     */
    fun dismissDeleteDialog() {
        _showDeleteDialog.value = false
        _projectToDelete.value = null
    }

    /**
     * プロジェクトを削除
     * iOS版参考: ProjectManager.swift:435-449 (deleteProject)
     *
     * プロジェクトに紐づく動画ファイルも物理削除します
     */
    fun deleteProject() {
        val project = _projectToDelete.value ?: return

        viewModelScope.launch {
            try {
                _isLoading.value = true
                _errorMessage.value = null

                repository.deleteProject(project.id)

                // 削除が成功したらダイアログを閉じる
                dismissDeleteDialog()

            } catch (e: Exception) {
                _errorMessage.value = "Failed to delete project: ${e.message}"
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * プロジェクト名変更ダイアログを表示
     * iOS版参考: ProjectManager.swift:42-55 (renameProject)
     *
     * @param project 名前変更対象のプロジェクト
     */
    fun showRenameDialog(project: Project) {
        _projectToRename.value = project
        _showRenameDialog.value = true
    }

    /**
     * 名前変更ダイアログを閉じる
     */
    fun dismissRenameDialog() {
        _showRenameDialog.value = false
        _projectToRename.value = null
    }

    /**
     * プロジェクト名を変更
     * iOS版参考: ProjectManager.swift:42-55 (renameProject)
     *
     * @param newName 新しいプロジェクト名
     */
    fun renameProject(newName: String) {
        val project = _projectToRename.value ?: return

        // 名前が空の場合は変更しない
        if (newName.isBlank()) {
            _errorMessage.value = "Project name cannot be empty"
            return
        }

        viewModelScope.launch {
            try {
                _isLoading.value = true
                _errorMessage.value = null

                // プロジェクト名を更新
                val updatedProject = project.copy(
                    name = newName,
                    lastModified = System.currentTimeMillis()
                )
                repository.updateProject(updatedProject)

                // 名前変更が成功したらダイアログを閉じる
                dismissRenameDialog()

            } catch (e: Exception) {
                _errorMessage.value = "Failed to rename project: ${e.message}"
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * エラーメッセージをクリア
     */
    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    /**
     * 選択されたプロジェクトをクリア
     */
    fun clearSelectedProject() {
        _selectedProject.value = null
    }
}
