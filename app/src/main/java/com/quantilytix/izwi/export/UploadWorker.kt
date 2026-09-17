package com.quantilytix.izwi.export

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.quantilytix.izwi.IzwiApplication
import com.quantilytix.izwi.data.UploadStatus
import com.quantilytix.izwi.network.RelayClient
import com.quantilytix.izwi.network.UploadOutcome
import com.quantilytix.izwi.session.SessionManager
import java.io.File

/**
 * One explicit, user-triggered upload attempt for a single bounded batch.
 * WorkManager only retries *this* enqueued attempt on transient failure —
 * there is no periodic or automatic background sync, per PLAN_B's sync
 * screen requirement.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_BATCH_ID = "batch_id"
    }

    override suspend fun doWork(): Result {
        val batchId = inputData.getString(KEY_BATCH_ID) ?: return Result.failure()
        val db = (applicationContext as IzwiApplication).database
        val batch = db.batchDao().get(batchId) ?: return Result.failure()

        val zipFile = File(batch.zipPath)
        if (!zipFile.exists()) {
            db.batchDao().update(batch.copy(uploadStatus = UploadStatus.FAILED, errorMessage = "export file missing, re-export batch"))
            return Result.failure()
        }

        db.batchDao().update(batch.copy(uploadStatus = UploadStatus.QUEUED, lastAttemptAtUtc = SessionManager.nowUtcIso()))
        db.clipDao().setUploadStatusForBatch(batchId, UploadStatus.QUEUED)

        return when (val outcome = RelayClient().uploadBatch(zipFile)) {
            is UploadOutcome.Success -> {
                db.batchDao().update(
                    batch.copy(
                        uploadStatus = UploadStatus.UPLOADED,
                        prUrl = outcome.result.pullRequestUrl,
                        lastAttemptAtUtc = SessionManager.nowUtcIso(),
                        errorMessage = null,
                    )
                )
                db.clipDao().setUploadStatusForBatch(batchId, UploadStatus.UPLOADED)
                Result.success()
            }
            is UploadOutcome.Rejected -> {
                db.batchDao().update(
                    batch.copy(
                        uploadStatus = UploadStatus.FAILED,
                        errorMessage = outcome.message,
                        lastAttemptAtUtc = SessionManager.nowUtcIso(),
                    )
                )
                db.clipDao().setUploadStatusForBatch(batchId, UploadStatus.FAILED)
                // Not retryable: the relay validated the batch and rejected it on content grounds.
                Result.failure()
            }
            is UploadOutcome.NetworkError -> {
                db.batchDao().update(
                    batch.copy(
                        uploadStatus = UploadStatus.FAILED,
                        errorMessage = outcome.message,
                        lastAttemptAtUtc = SessionManager.nowUtcIso(),
                    )
                )
                db.clipDao().setUploadStatusForBatch(batchId, UploadStatus.FAILED)
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        }
    }
}
