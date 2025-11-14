package com.tashichi.clipflow.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tashichi.clipflow.data.model.Project
import com.tashichi.clipflow.ui.viewmodel.ProjectListViewModel
import java.text.SimpleDateFormat
import java.util.*

/**
 * ProjectListScreen - プロジェクト一覧画面
 *
 * iOS版参考実装:
 * - ProjectListView.swift (プロジェクト一覧UI)
 *
 * レイアウト:
 * - ヘッダー: アプリ名「ClipFlow」+ 新規プロジェクトボタン
 * - プロジェクト一覧: LazyColumn でリスト表示
 *   - プロジェクトカード:
 *     - プロジェクト名
 *     - 作成日時表示
 *     - セグメント数表示（例: "5 segments"）
 *     - タップで選択・Camera画面へ遷移
 *
 * @param viewModel ProjectListViewModel
 * @param onProjectSelected プロジェクト選択時のコールバック
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(
    viewModel: ProjectListViewModel = viewModel(),
    onProjectSelected: (Project) -> Unit
) {
    // State
    val projects by viewModel.projects.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val showDeleteDialog by viewModel.showDeleteDialog.collectAsState()
    val projectToDelete by viewModel.projectToDelete.collectAsState()
    val showRenameDialog by viewModel.showRenameDialog.collectAsState()
    val projectToRename by viewModel.projectToRename.collectAsState()
    val selectedProject by viewModel.selectedProject.collectAsState()

    // 選択されたプロジェクトがあれば、画面遷移
    LaunchedEffect(selectedProject) {
        selectedProject?.let { project ->
            onProjectSelected(project)
            viewModel.clearSelectedProject()
        }
    }

    // iOS版カラースキーム: 背景は黒
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // ヘッダー
            ProjectListHeader(
                onCreateNewProject = {
                    viewModel.createNewProject()
                }
            )

            // プロジェクト一覧
            if (projects.isEmpty() && !isLoading) {
                // 空の状態
                EmptyProjectList()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = projects.sortedByDescending { it.createdAt },
                        key = { it.id }
                    ) { project ->
                        ProjectCard(
                            project = project,
                            onClick = {
                                viewModel.selectProject(project)
                            },
                            onRename = {
                                viewModel.showRenameDialog(project)
                            },
                            onDelete = {
                                viewModel.showDeleteConfirmation(project)
                            }
                        )
                    }
                }
            }
        }

        // ローディングオーバーレイ
        if (isLoading) {
            LoadingOverlay()
        }
    }

    // 削除確認ダイアログ
    if (showDeleteDialog && projectToDelete != null) {
        DeleteConfirmationDialog(
            project = projectToDelete!!,
            onConfirm = {
                viewModel.deleteProject()
            },
            onDismiss = {
                viewModel.dismissDeleteDialog()
            }
        )
    }

    // 名前変更ダイアログ
    if (showRenameDialog && projectToRename != null) {
        RenameProjectDialog(
            project = projectToRename!!,
            onConfirm = { newName ->
                viewModel.renameProject(newName)
            },
            onDismiss = {
                viewModel.dismissRenameDialog()
            }
        )
    }

    // エラーメッセージ表示
    errorMessage?.let { message ->
        LaunchedEffect(message) {
            // TODO: Snackbarでエラー表示
            // 現在は簡易的にクリア
            kotlinx.coroutines.delay(3000)
            viewModel.clearErrorMessage()
        }
    }
}

/**
 * ヘッダー - アプリ名 + 新規プロジェクトボタン
 * iOS版参考: ProjectListView.swift (navigationTitle + toolbar)
 */
@Composable
fun ProjectListHeader(
    onCreateNewProject: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.9f),
                        Color.Black.copy(alpha = 0.7f)
                    )
                )
            )
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .statusBarsPadding()
    ) {
        // アプリ名
        Text(
            text = "ClipFlow",
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterStart)
        )

        // 新規プロジェクトボタン（右上、緑色）
        // iOS版: Color.green
        FloatingActionButton(
            onClick = onCreateNewProject,
            containerColor = Color(0xFF4CAF50), // 緑色
            contentColor = Color.White,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(56.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "New Project",
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

/**
 * プロジェクトカード
 * iOS版参考: ProjectListView.swift (List内のNavigationLink)
 *
 * 表示内容:
 * - プロジェクト名
 * - 作成日時
 * - セグメント数（例: "5 segments"）
 * - アクションボタン（名前変更、削除）
 */
@Composable
fun ProjectCard(
    project: Project,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E1E1E) // ダークグレー
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 4.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // プロジェクト名
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = project.name,
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // アクションボタン
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 名前変更ボタン
                    IconButton(
                        onClick = onRename,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Rename",
                            tint = Color(0xFF2196F3), // 青色
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // 削除ボタン
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = Color(0xFFF44336), // 赤色
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 作成日時
            Text(
                text = formatDate(project.createdAt),
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(4.dp))

            // セグメント数
            Text(
                text = if (project.segmentCount == 1) {
                    "1 segment"
                } else {
                    "${project.segmentCount} segments"
                },
                color = Color(0xFFFFEB3B), // 黄色（iOS版: Color.yellow）
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * 空のプロジェクト一覧
 */
@Composable
fun EmptyProjectList() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "No Projects",
                color = Color.Gray,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Tap + to create a new project",
                color = Color.Gray,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/**
 * ローディングオーバーレイ
 */
@Composable
fun LoadingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            color = Color(0xFF2196F3), // 青色
            modifier = Modifier.size(60.dp)
        )
    }
}

/**
 * 削除確認ダイアログ
 * iOS版参考: ProjectListView.swift (alert削除確認)
 */
@Composable
fun DeleteConfirmationDialog(
    project: Project,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Delete Project",
                color = Color.White
            )
        },
        text = {
            Text(
                text = "Are you sure you want to delete \"${project.name}\"? This action cannot be undone.",
                color = Color.Gray
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color(0xFFF44336) // 赤色
                )
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color.Gray
                )
            ) {
                Text("Cancel")
            }
        },
        containerColor = Color(0xFF1E1E1E) // ダークグレー
    )
}

/**
 * プロジェクト名変更ダイアログ
 * iOS版参考: ProjectManager.swift:42-55 (renameProject)
 */
@Composable
fun RenameProjectDialog(
    project: Project,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf(project.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Rename Project",
                color = Color.White
            )
        },
        text = {
            Column {
                Text(
                    text = "Enter a new name for this project:",
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF2196F3), // 青色
                        unfocusedBorderColor = Color.Gray,
                        cursorColor = Color(0xFF2196F3)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (newName.isNotBlank()) {
                        onConfirm(newName)
                    }
                },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color(0xFF2196F3) // 青色
                )
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color.Gray
                )
            ) {
                Text("Cancel")
            }
        },
        containerColor = Color(0xFF1E1E1E) // ダークグレー
    )
}

/**
 * 日時フォーマット
 */
private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
