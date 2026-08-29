package io.baton.cal.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource

class ProductionDatasourceConfigurationTest {
    @Test
    fun `운영 필수 설정은 외부에서 받고 관리 포트를 분리한다`() {
        val properties = YamlPropertiesFactoryBean().apply {
            setResources(ClassPathResource("application-prod.yml"))
        }.getObject()

        assertThat(properties)
            .containsEntry("spring.datasource.url", "${'$'}{DATABASE_URL}")
            .containsEntry("spring.datasource.username", "${'$'}{DATABASE_USERNAME}")
            .containsEntry("spring.datasource.password", "${'$'}{DATABASE_PASSWORD}")
            .containsEntry("baton.cal.public-base-url", "${'$'}{BATON_CAL_PUBLIC_BASE_URL}")
            .containsEntry(
                "baton.cal.subscription-generation",
                "${'$'}{BATON_CAL_SUBSCRIPTION_GENERATION}",
            )
            .containsEntry("management.server.port", "${'$'}{MANAGEMENT_SERVER_PORT:8081}")
    }
}
