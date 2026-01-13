package app.mnema.user.media.client

import java.time.Instant
import java.util.UUID

data class MediaResolved(
    val mediaId: UUID,
    val kind: String? = null,
    val url: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val durationSeconds: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val expiresAt: Instant? = null
)
