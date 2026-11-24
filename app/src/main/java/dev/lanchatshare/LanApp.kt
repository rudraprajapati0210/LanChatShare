package dev.lanchatshare

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.lanchatshare.data.ProfileRepository
import javax.inject.Singleton

private val Context.profileStore by preferencesDataStore("profile")

@HiltAndroidApp class LanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            android.os.StrictMode.setVmPolicy(android.os.StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().penaltyLog().build())
        }
    }
}

@Module @InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides @Singleton fun trust(@ApplicationContext context: Context) = dev.lanchatshare.connectivity.TrustStore(context)
    @Provides @Singleton fun profiles(@ApplicationContext context: Context) = ProfileRepository(context.profileStore)
}


