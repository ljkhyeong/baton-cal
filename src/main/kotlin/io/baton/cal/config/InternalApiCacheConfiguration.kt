package io.baton.cal.config

import org.springframework.context.annotation.Configuration
import org.springframework.http.CacheControl
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.mvc.WebContentInterceptor

/** 내부 API의 성공·오류 응답은 상태 확인 뒤 재사용되지 않도록 모두 `Cache-Control: no-store`로 보낸다. */
@Configuration(proxyBeanMethods = false)
class InternalApiCacheConfiguration : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(
            WebContentInterceptor().apply { addCacheMapping(CacheControl.noStore(), "/internal/**") },
        )
    }
}
