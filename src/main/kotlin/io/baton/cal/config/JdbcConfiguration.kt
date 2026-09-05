package io.baton.cal.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator
import org.springframework.jdbc.support.SQLExceptionTranslator

@Configuration(proxyBeanMethods = false)
class JdbcConfiguration {
    @Bean
    fun jdbcExceptionTranslator(): SQLExceptionTranslator = SQLErrorCodeSQLExceptionTranslator("PostgreSQL")
}
