package com.rrgmc.classapptriage.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.rrgmc.classapptriage.R
import com.rrgmc.classapptriage.api.Message
import com.rrgmc.classapptriage.ui.MainActivity
import java.util.concurrent.TimeUnit

/**
 * Hourly check for new Important messages. Runs as a WorkManager periodic job
 * (JobScheduler), so the system batches it with other work and defers it in
 * Doze; it only runs with a network connection and when the battery is not low.
 */
object ImportantNotifier {
    private const val WORK_NAME = "important-check"
    private const val CHANNEL_ID = "important"
    private const val GROUP = "important"
    private const val SUMMARY_ID = 0
    private const val MESSAGE_ID = 1
    private const val TAG_PREFIX = "message:"

    /** Intent extra with the message id to open from a notification. */
    const val EXTRA_MESSAGE_ID = "message_id"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<ImportantCheckWorker>(1, TimeUnit.HOURS, 15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        NotificationManagerCompat.from(context).cancelAll()
    }

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID, context.getString(R.string.notify_channel), NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.notify_channel_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Posts one notification per message in [messages], grouped under a summary. */
    fun show(context: Context, messages: List<Message>) {
        if (messages.isEmpty() || !canNotify(context)) return
        val nm = NotificationManagerCompat.from(context)
        try {
            for (m in messages) {
                val sender = m.entity?.fullname.orEmpty().ifEmpty { context.getString(R.string.app_name) }
                val n = builder(context, openIntent(context, m.id))
                    .setContentTitle(sender)
                    .setContentText(m.summary)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(m.summary))
                    .setSubText(m.label?.title?.ifEmpty { null })
                    .build()
                nm.notify(TAG_PREFIX + m.id, MESSAGE_ID, n)
            }
            val count = activeMessageIds(context).size
            val title = context.resources.getQuantityString(R.plurals.notify_new_important, count, count)
            val summary = builder(context, openIntent(context, null))
                .setContentTitle(title)
                .setStyle(NotificationCompat.InboxStyle().setSummaryText(title))
                .setGroupSummary(true)
                .build()
            nm.notify(SUMMARY_ID, summary)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }

    /** Removes the notifications of messages no longer in [unreadIds] (read elsewhere). */
    fun cancelRead(context: Context, unreadIds: Set<Long>) {
        val nm = NotificationManagerCompat.from(context)
        val active = activeMessageIds(context)
        for (id in active - unreadIds) nm.cancel(TAG_PREFIX + id, MESSAGE_ID)
        if (active.none { it in unreadIds }) nm.cancel(SUMMARY_ID)
    }

    private fun activeMessageIds(context: Context): Set<Long> =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .mapNotNullTo(HashSet()) { n -> n.tag?.takeIf { it.startsWith(TAG_PREFIX) }?.removePrefix(TAG_PREFIX)?.toLongOrNull() }

    private fun builder(context: Context, intent: PendingIntent) = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setGroup(GROUP)
        .setContentIntent(intent)
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_EMAIL)

    private fun openIntent(context: Context, messageId: Long?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (messageId != null) putExtra(EXTRA_MESSAGE_ID, messageId)
        }
        return PendingIntent.getActivity(
            context, messageId?.hashCode() ?: 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
