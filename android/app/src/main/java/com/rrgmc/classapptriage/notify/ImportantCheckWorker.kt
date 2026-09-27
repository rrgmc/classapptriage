package com.rrgmc.classapptriage.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rrgmc.classapptriage.ClassAppTriageApp
import com.rrgmc.classapptriage.api.ClassAppClient
import com.rrgmc.classapptriage.api.UnauthorizedException

/**
 * Fetches one page of the unread folder of the selected inbox and notifies the
 * Important messages not seen by the previous check. The first check of an
 * inbox only records what is already unread, so enabling notifications does
 * not flood the shade with old messages.
 */
class ImportantCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as ClassAppTriageApp).container
        if (!container.settings.notifyImportant.value) return Result.success()
        val client = container.client() ?: return Result.success()
        val entityId = container.settings.entity.value?.id ?: return Result.success()
        val unread = try {
            client.messagesPage(entityId, limit = PAGE_SIZE, folder = ClassAppClient.FOLDER_UNREAD).messages
        } catch (e: UnauthorizedException) {
            container.sessionExpired()
            return Result.success()
        } catch (e: Exception) {
            return if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        }
        val unreadIds = unread.mapTo(HashSet()) { it.id }
        val seen = container.notifyState.seen(entityId)
        if (seen != null) {
            ImportantNotifier.cancelRead(applicationContext, unreadIds)
            ImportantNotifier.show(applicationContext, newImportant(unread, container.rulesStore.config.value, entityId, seen))
        }
        container.notifyState.save(entityId, unreadIds)
        return Result.success()
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val MAX_RETRIES = 3
    }
}
