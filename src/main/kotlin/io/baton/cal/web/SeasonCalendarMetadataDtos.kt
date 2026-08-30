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
    @field:Pattern(regexp = "[^\\x{0}-\\x{8}\\x{B}-\\x{1F}\\x{7F}\\x{D800}-\\x{DFFF}]{1,512}")
    val displayName: String,
)

data class SeasonCalendarMetadataResponse(
    val seasonId: UUID,
    val revision: Int,
    val displayName: String,
)
