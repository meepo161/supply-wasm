package org.example.project.realtime

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.example.project.dto.ErpEventDomain
import org.example.project.dto.ErpEventDto

class ErpEventBus {

    private val _events = MutableSharedFlow<ErpEventDto>(extraBufferCapacity = 128)
    val events: SharedFlow<ErpEventDto> = _events

    fun publish(domain: ErpEventDomain, action: String, entityId: Long? = null) {
        _events.tryEmit(ErpEventDto(domain = domain, action = action, entityId = entityId))
    }
}
