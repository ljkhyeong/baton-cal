package io.baton.cal.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource

class ProductionDatasourceConfigurationTest {
    @Test
    fun `운영 데이터베이스 설정은 로컬 기본값을 상속하지 않는다`() {
        val properties = YamlPropertiesFactoryBean().apply {
            setResources(ClassPathResource("application-prod.yml"))
        }.getObject()

        assertThat(properties)
            .containsEntry("spring.datasource.url", "${'$'}{DATABASE_URL}")
            .containsEntry("spring.datasource.username", "${'$'}{DATABASE_USERNAME}")
            .containsEntry("spring.datasource.password", "${'$'}{DATABASE_PASSWORD}")
    }
}
