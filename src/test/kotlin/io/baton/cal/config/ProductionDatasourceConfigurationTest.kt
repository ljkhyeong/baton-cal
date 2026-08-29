package io.baton.cal.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource

class ProductionDatasourceConfigurationTest {
    @Test
    fun `운영 데이터베이스 기본값을 막고 관리 포트를 분리한다`() {
        val properties = YamlPropertiesFactoryBean().apply {
            setResources(ClassPathResource("application-prod.yml"))
        }.getObject()

        assertThat(properties)
            .containsEntry("spring.datasource.url", "${'$'}{DATABASE_URL}")
            .containsEntry("spring.datasource.username", "${'$'}{DATABASE_USERNAME}")
            .containsEntry("spring.datasource.password", "${'$'}{DATABASE_PASSWORD}")
            .containsEntry("management.server.port", "${'$'}{MANAGEMENT_SERVER_PORT:8081}")
    }
}
