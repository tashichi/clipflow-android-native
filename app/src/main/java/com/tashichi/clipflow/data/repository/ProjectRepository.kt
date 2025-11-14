package com.tashichi.clipflow.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tashichi.clipflow.data.model.Project
import com.tashichi.clipflow.data.model.VideoSegment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * ProjectRepository - プロジェクトのデータ永続化を管理するリポジトリ
 *
 * iOS版参考実装:
 * - ProjectManager.swift (saveProjects, loadProjects, createNewProject, updateProject, deleteProject)
 * - UserDefaultsを使用した永続化 → Android版はDataStore + JSON
 *
 * 主な機能:
 * - プロジェクトのCRUD操作
 * - DataStoreを使用したデータ永続化
 * - Flowによるリアクティブなデータ更新
 * - セグメント管理（追加・削除）
 */
class ProjectRepository(private val context: Context) {

    companion object {
        // DataStoreのキー名（iOS版: "JourneyMoments_Projects"）
        private const val PROJECTS_KEY = "clipflow_projects"

        // DataStore拡張プロパティ
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
            name = "clipflow_preferences"
        )
    }

    private val projectsKey = stringPreferencesKey(PROJECTS_KEY)

    private val dataStore: DataStore<Preferences> = context.dataStore

    // JSON serialization設定
    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

    /**
     * 全プロジェクトを取得（Flow）
     * iOS版参考: ProjectManager.swift:501-514 (loadProjects)
     *
     * @return プロジェクトリストのFlow
     */
    fun getAllProjects(): Flow<List<Project>> {
        return dataStore.data.map { preferences ->
            val jsonString = preferences[projectsKey] ?: "[]"
            try {
                json.decodeFromString<List<Project>>(jsonString)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /**
     * プロジェクトIDで取得
     *
     * @param id プロジェクトID
     * @return プロジェクト（見つからない場合はnull）
     */
    suspend fun getProjectById(id: Long): Project? {
        val projects = getAllProjects().first()
        return projects.find { it.id == id }
    }

    /**
     * 新規プロジェクトを作成
     * iOS版参考: ProjectManager.swift:21-30 (createNewProject)
     *
     * 命名規則: "Project {count + 1}"
     * 例: Project 1, Project 2, Project 3...
     *
     * @param name プロジェクト名（指定しない場合は自動採番）
     * @return 作成されたプロジェクト
     */
    suspend fun createProject(name: String? = null): Project {
        val projects = getAllProjects().first()

        // プロジェクト名の決定
        val projectName = name ?: "Project ${projects.size + 1}"

        // 新規プロジェクト作成
        val newProject = Project(
            id = System.currentTimeMillis(),
            name = projectName,
            segments = emptyList(),
            createdAt = System.currentTimeMillis(),
            lastModified = System.currentTimeMillis()
        )

        // プロジェクトリストに追加して保存
        val updatedProjects = projects + newProject
        saveProjects(updatedProjects)

        return newProject
    }

    /**
     * プロジェクトを更新
     * iOS版参考: ProjectManager.swift:33-39 (updateProject)
     *
     * @param project 更新するプロジェクト
     */
    suspend fun updateProject(project: Project) {
        val projects = getAllProjects().first()
        val updatedProjects = projects.map {
            if (it.id == project.id) {
                project.copy(lastModified = System.currentTimeMillis())
            } else {
                it
            }
        }
        saveProjects(updatedProjects)
    }

    /**
     * プロジェクトを削除
     * iOS版参考: ProjectManager.swift:435-449 (deleteProject)
     *
     * プロジェクトに紐づく動画ファイルも物理削除します
     *
     * @param id 削除するプロジェクトID
     */
    suspend fun deleteProject(id: Long) {
        val projects = getAllProjects().first()
        val projectToDelete = projects.find { it.id == id } ?: return

        // プロジェクトに紐づく動画ファイルを削除
        projectToDelete.segments.forEach { segment ->
            deleteSegmentFile(segment)
        }

        // プロジェクトリストから削除
        val updatedProjects = projects.filter { it.id != id }
        saveProjects(updatedProjects)
    }

    /**
     * プロジェクトにセグメントを追加
     * iOS版参考: MainView.swift:78-87 (onRecordingComplete)
     *
     * @param projectId プロジェクトID
     * @param segment 追加するセグメント
     */
    suspend fun addSegmentToProject(projectId: Long, segment: VideoSegment) {
        val projects = getAllProjects().first()
        val updatedProjects = projects.map { project ->
            if (project.id == projectId) {
                project.addSegment(segment)
            } else {
                project
            }
        }
        saveProjects(updatedProjects)
    }

    /**
     * プロジェクトからセグメントを削除
     * iOS版参考: ProjectManager.swift:58-94 (deleteSegment)
     *
     * 重要: 最後の1セグメントは削除不可
     * 削除後、orderを自動リナンバリング（連番を維持）
     *
     * @param projectId プロジェクトID
     * @param segmentId 削除するセグメントID
     * @throws IllegalStateException 最後の1セグメントを削除しようとした場合
     */
    suspend fun deleteSegmentFromProject(projectId: Long, segmentId: Long) {
        val projects = getAllProjects().first()
        val project = projects.find { it.id == projectId } ?: return

        // 最後の1セグメントは削除不可
        if (project.segments.size <= 1) {
            throw IllegalStateException("Cannot delete the last segment")
        }

        // セグメントファイルを削除
        val segmentToDelete = project.segments.find { it.id == segmentId }
        segmentToDelete?.let { deleteSegmentFile(it) }

        // プロジェクトからセグメントを削除
        val updatedProjects = projects.map { proj ->
            if (proj.id == projectId) {
                proj.deleteSegment(segmentToDelete!!)
            } else {
                proj
            }
        }
        saveProjects(updatedProjects)
    }

    /**
     * プロジェクトリストを保存
     * iOS版参考: ProjectManager.swift:490-498 (saveProjects)
     *
     * @param projects 保存するプロジェクトリスト
     */
    private suspend fun saveProjects(projects: List<Project>) {
        dataStore.edit { preferences ->
            val jsonString = json.encodeToString(projects)
            preferences[projectsKey] = jsonString
        }
    }

    /**
     * セグメントの動画ファイルを削除
     *
     * @param segment 削除するセグメント
     */
    private fun deleteSegmentFile(segment: VideoSegment) {
        try {
            val file = File(context.filesDir, segment.uri)
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            // ファイル削除に失敗してもエラーを投げない
            e.printStackTrace()
        }
    }
}
