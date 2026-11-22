package org.example.project.data

import org.example.project.dto.AnalyticsSummaryDto
import org.example.project.dto.CalendarEventDto
import org.example.project.dto.DocumentTemplateDto

interface AnalyticsRepository {
    suspend fun getSummary(): ApiResult<AnalyticsSummaryDto>
    suspend fun getCalendar(): ApiResult<List<CalendarEventDto>>
    suspend fun listDocumentTemplates(): ApiResult<List<DocumentTemplateDto>>
}
