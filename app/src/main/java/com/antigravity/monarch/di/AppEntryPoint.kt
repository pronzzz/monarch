package com.antigravity.monarch.di

import com.antigravity.monarch.data.local.DataResetter
import com.antigravity.monarch.domain.repository.ProfileRepository
import com.antigravity.monarch.domain.usecase.ProgressionService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Access to app singletons from non-injected code (instrumented tests, framework callbacks). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppEntryPoint {
    fun resetter(): DataResetter
    fun profiles(): ProfileRepository
    fun progression(): ProgressionService
}
