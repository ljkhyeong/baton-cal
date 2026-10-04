package io.baton.cal.web

import java.util.UUID

data class ProjectionRebuildResponse(
    val seasonId: UUID,
    val etag: String,
    val itemCount: Int,
)
