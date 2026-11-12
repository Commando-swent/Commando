package com.android.sample.model

// AI assistance: OpenAI Codex.
/**
 * Shared eligibility for requester tracking; Firestore rules enforce the same persisted statuses.
 */
fun OrderStatus.isActiveForTracking(): Boolean =
    this == OrderStatus.PENDING ||
        this == OrderStatus.ACCEPTED ||
        this == OrderStatus.READY_FOR_HANDOFF
