package io.baton.cal.config

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration(proxyBeanMethods = false)
@Profile("prod")
class ProductionConfiguration(properties: CalProperties) {
    init {
        require(properties.publicBaseUrl.scheme == "https") {
            "prod 프로필의 publicBaseUrl은 HTTPS를 사용해야 한다"
        }
    }
}
