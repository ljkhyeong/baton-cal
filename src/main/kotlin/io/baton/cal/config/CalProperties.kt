package io.baton.cal.config

import java.net.URI
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("baton.cal")
class CalProperties(
    val internalToken: String,
    val publicBaseUrl: URI = URI.create("http://localhost:8080"),
) {
    init {
        require(
            internalToken.length >= 32 && internalToken.none { it == '\r' || it == '\n' },
        ) { "internalToken은 줄 바꿈 없이 32자 이상이어야 한다" }
        require(publicBaseUrl.scheme == "http" || publicBaseUrl.scheme == "https") {
            "publicBaseUrl은 http 또는 https를 사용해야 한다"
        }
        require(!publicBaseUrl.isOpaque && publicBaseUrl.host != null) {
            "publicBaseUrl은 호스트가 있는 계층형 URI여야 한다"
        }
        require(publicBaseUrl.query == null && publicBaseUrl.fragment == null) {
            "publicBaseUrl에는 쿼리나 프래그먼트를 넣을 수 없다"
        }
        require(publicBaseUrl.userInfo == null) { "publicBaseUrl에는 사용자 정보를 넣을 수 없다" }
    }
}
