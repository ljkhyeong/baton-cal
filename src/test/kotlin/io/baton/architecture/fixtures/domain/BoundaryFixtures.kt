package io.baton.architecture.fixtures.domain

import io.baton.cal.persistence.CalendarItemRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.RestController

@RestController
class RepositoryController(val repository: CalendarItemRepository)

class InfrastructureDomain(val repository: io.baton.cal.persistence.CalendarItemRepository)

@Service
class JdbcService(val jdbc: JdbcClient)

@Service
class ConstructingService {
    fun repository(jdbc: JdbcClient) = CalendarItemRepository(jdbc)
}

@Service
class InjectedService(val repository: CalendarItemRepository)
