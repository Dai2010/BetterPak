package com.dai2010.betterpak.data

import com.dai2010.betterpak.domain.CloudProvider
import com.dai2010.betterpak.domain.CloudProviderId
import com.dai2010.betterpak.domain.CloudTokenProvider

class CloudProviderRegistry(tokenProvider: CloudTokenProvider) {
    private val providers: Map<CloudProviderId, CloudProvider> = mapOf(
        CloudProviderId.ONEDRIVE to OneDriveProvider(tokenProvider),
        CloudProviderId.GOOGLE_DRIVE to GoogleDriveProvider(tokenProvider),
    )

    fun provider(id: CloudProviderId): CloudProvider =
        providers[id] ?: error("未注册的云端 provider")
}
