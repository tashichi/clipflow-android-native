package com.tashichi.clipflow.data.model

import kotlinx.serialization.Serializable

/**
 * VideoSegment - 1秒間の動画セグメントを表すデータクラス
 *
 * @property id セグメントID (Unix timestamp in milliseconds)
 * @property uri ファイル名 (例: "segment_1234567890.mp4")
 * @property timestamp 撮影日時 (Unix timestamp in milliseconds)
 * @property facing カメラの向き ("back" または "front")
 * @property order 再生順序 (1から開始)
 */
@Serializable
data class VideoSegment(
    val id: Long = System.currentTimeMillis(),
    val uri: String,
    val timestamp: Long = System.currentTimeMillis(),
    val facing: String,  // "back" or "front"
    var order: Int
)

/**
 * Project - 複数のセグメントをまとめたプロジェクト
 *
 * @property id プロジェクトID (Unix timestamp in milliseconds)
 * @property name プロジェクト名
 * @property segments セグメントのリスト
 * @property createdAt 作成日時 (Unix timestamp in milliseconds)
 * @property lastModified 最終更新日時 (Unix timestamp in milliseconds)
 */
@Serializable
data class Project(
    val id: Long = System.currentTimeMillis(),
    var name: String,
    var segments: List<VideoSegment> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    var lastModified: Long = System.currentTimeMillis()
) {
    /**
     * セグメント数を取得
     */
    val segmentCount: Int get() = segments.size

    /**
     * セグメントを追加して新しいProjectを返す
     *
     * @param segment 追加するセグメント
     * @return 更新されたProject
     */
    fun addSegment(segment: VideoSegment): Project {
        return copy(
            segments = segments + segment,
            lastModified = System.currentTimeMillis()
        )
    }

    /**
     * セグメントを削除して新しいProjectを返す
     * 最後の1セグメントは削除できない
     *
     * @param segment 削除するセグメント
     * @return 更新されたProject
     * @throws IllegalStateException 最後の1セグメントを削除しようとした場合
     */
    fun deleteSegment(segment: VideoSegment): Project {
        if (segments.size <= 1) {
            throw IllegalStateException("Cannot delete the last segment")
        }

        val updatedSegments = segments
            .filter { it.id != segment.id }
            .mapIndexed { index, seg -> seg.copy(order = index + 1) }

        return copy(
            segments = updatedSegments,
            lastModified = System.currentTimeMillis()
        )
    }

    /**
     * セグメントをorder順にソートして返す
     *
     * @return ソートされたセグメントリスト
     */
    fun getSortedSegments(): List<VideoSegment> {
        return segments.sortedBy { it.order }
    }
}

/**
 * AppScreen - アプリケーションの画面遷移を管理するEnum
 */
enum class AppScreen {
    /**
     * プロジェクト一覧画面
     */
    PROJECTS,

    /**
     * カメラ撮影画面
     */
    CAMERA,

    /**
     * 動画再生画面
     */
    PLAYER
}

/**
 * SegmentTimeRange - セグメントのComposition内での時間範囲
 *
 * @property segmentIndex セグメントのインデックス
 * @property startTimeMs 開始時刻 (ミリ秒)
 * @property durationMs 長さ (ミリ秒)
 */
data class SegmentTimeRange(
    val segmentIndex: Int,
    val startTimeMs: Long,
    val durationMs: Long
) {
    /**
     * 終了時刻を取得 (ミリ秒)
     */
    val endTimeMs: Long get() = startTimeMs + durationMs

    /**
     * 指定された時刻がこのセグメントの範囲内かどうかを判定
     *
     * @param timeMs 判定する時刻 (ミリ秒)
     * @return 範囲内の場合true
     */
    fun contains(timeMs: Long): Boolean {
        return timeMs >= startTimeMs && timeMs < endTimeMs
    }
}
