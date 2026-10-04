package io.baton.cal.web

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import org.hibernate.validator.constraints.Normalized
import java.text.Normalizer
import java.util.UUID

data class SeasonCalendarMetadataRequest(
    @field:Min(0)
    val revision: Int,
    @field:Normalized(form = Normalizer.Form.NFC)
    @field:Pattern(regexp = "$CONTRACT_TEXT_CHARACTER{1,512}")
    val displayName: String,
)

data class SeasonCalendarMetadataResponse(
    val seasonId: UUID,
    val revision: Int,
    val displayName: String,
)
