package com.mediasaver.feature.status

import com.mediasaver.core.model.StatusMediaType
import com.mediasaver.core.model.WhatsAppStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusUiTest {

    @Test
    fun `filteredStatuses correctly separates images and videos based on filter`() {
        val imageStatus = WhatsAppStatus(
            id = "1",
            fileName = "status1.jpg",
            uriString = "content://status1",
            mediaType = StatusMediaType.IMAGE,
            fileSize = 1024L,
            lastModified = 1000L
        )

        val videoStatus = WhatsAppStatus(
            id = "2",
            fileName = "status2.mp4",
            uriString = "content://status2",
            mediaType = StatusMediaType.VIDEO,
            fileSize = 5000L,
            lastModified = 2000L
        )

        val state = StatusUiState(
            statuses = listOf(imageStatus, videoStatus),
            filter = StatusFilter.ALL
        )

        assertEquals(2, state.filteredStatuses.size)

        val imageOnlyState = state.copy(filter = StatusFilter.IMAGES)
        assertEquals(1, imageOnlyState.filteredStatuses.size)
        assertEquals(StatusMediaType.IMAGE, imageOnlyState.filteredStatuses.first().mediaType)

        val videoOnlyState = state.copy(filter = StatusFilter.VIDEOS)
        assertEquals(1, videoOnlyState.filteredStatuses.size)
        assertEquals(StatusMediaType.VIDEO, videoOnlyState.filteredStatuses.first().mediaType)
    }
}
