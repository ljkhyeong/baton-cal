package io.baton.cal.config

import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.net.URI
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties("baton.cal")
data class CalProperties(
    @field:Size(min = 32)
    @field:Pattern(regexp = "[^\\r\\n]+")
    val internalToken: String,
    val publicBaseUrl: URI = URI.create("http://localhost:8080"),
) {
    init {
        require(publicBaseUrl.scheme == "http" || publicBaseUrl.scheme == "https") {
            "publicBaseUrl must use http or https"
        }
        require(publicBaseUrl.query == null && publicBaseUrl.fragment == null) {
            "publicBaseUrl must not contain a query or fragment"
        }
        require(publicBaseUrl.userInfo == null) { "publicBaseUrl must not contain user info" }
    }
}
