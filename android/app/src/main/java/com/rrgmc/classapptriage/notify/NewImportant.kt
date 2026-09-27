package com.rrgmc.classapptriage.notify

import com.rrgmc.classapptriage.api.Message
import com.rrgmc.classapptriage.triage.Category
import com.rrgmc.classapptriage.triage.Config

/**
 * The messages in [unread] worth a notification: classified Important by
 * [config], not sent from the inbox [entityId] itself, and not in [seen]
 * (already notified or present when notifications were enabled).
 */
fun newImportant(unread: List<Message>, config: Config, entityId: Long, seen: Set<Long>): List<Message> =
    unread.filter {
        it.id !in seen && it.entity?.id != entityId && config.classify(it).category == Category.IMPORTANT
    }
