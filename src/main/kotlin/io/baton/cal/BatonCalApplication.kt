package io.baton.cal

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@ConfigurationPropertiesScan
@SpringBootApplication
class BatonCalApplication

fun main(args: Array<String>) {
    runApplication<BatonCalApplication>(*args)
}
