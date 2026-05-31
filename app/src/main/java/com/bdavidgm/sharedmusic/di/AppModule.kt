package com.bdavidgm.sharedmusic.di

import android.content.Context
import com.bdavidgm.sharedmusic.audio.AudioPlayerController
import com.bdavidgm.sharedmusic.audio.TrackBuffer
import com.bdavidgm.sharedmusic.wifi.HotspotManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAudioPlayerController(@ApplicationContext context: Context): AudioPlayerController =
        AudioPlayerController(context)

    @Provides
    @Singleton
    fun provideTrackBuffer(@ApplicationContext context: Context): TrackBuffer =
        TrackBuffer(context)

    @Provides
    @Singleton
    fun provideHotspotManager(@ApplicationContext context: Context): HotspotManager =
        HotspotManager(context)
}
