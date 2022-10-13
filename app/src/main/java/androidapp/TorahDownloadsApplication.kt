package com.example.bottomsheeterrorreportingreproduction.androidapp

import KotlinFunctionLibrary
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import androidx.preference.PreferenceManager
import com.example.android.media.viewmodels.MainActivityViewModel
import com.example.android.media.viewmodels.NowPlayingFragmentViewModel
import androidapp.CONSTANTS
import androidapp.ShiurWithAllFilterMetadata
import com.example.bottomsheeterrorreportingreproduction.androidapp.media.media.library.ShiurQueue
import com.example.bottomsheeterrorreportingreproduction.androidapp.util.AndroidFunctionLibrary.ld
import com.example.bottomsheeterrorreportingreproduction.androidapp.util.AndroidFunctionLibrary.setThemeFromSettings
import com.example.bottomsheeterrorreportingreproduction.androidapp.util.Util
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import timber.log.Timber


lateinit var mEntireApplicationContext: Context

lateinit var preferencesManager: SharedPreferences
lateinit var mAppScope: CoroutineScope
lateinit var tdApplication: TorahDownloadsApplication

val gson by lazy { Gson() }

//Set default just in case they are accessed by the first network fragment before they are fetched from preference manager
var keepCache by KotlinFunctionLibrary.LazyMutable { true }
var showHowOldContentIs by KotlinFunctionLibrary.LazyMutable { true }
var cacheTimeInSeconds by KotlinFunctionLibrary.LazyMutable { defaultCacheStaleInSeconds.toInt() }
var ageToDisplayHowOldContentIs by KotlinFunctionLibrary.LazyMutable { defaultAgeToDisplayHowOldContentIs.toInt() }
val defaultCacheStaleInSeconds by lazy { "2419200" }
val defaultAgeToDisplayHowOldContentIs by lazy { "86400" }

//val shiurDownloadPreferenceOnlyWiFI by lazy { mEntireApplicationContext.getString(R.string.only_over_wifi_code) }
lateinit var mainActivityViewModel: MainActivityViewModel
lateinit var nowPlayingFragmentViewModel: NowPlayingFragmentViewModel
val shiurQueue = ShiurQueue()
private lateinit var shiurCurrentlyBeingPlayed: ShiurWithAllFilterMetadata
var shiurWaitingToBePlayed: ShiurWithAllFilterMetadata? =
    null //the previously playing shiur and its metadata needs to be saved to the db (in the function named saveRecentSongAndQueueToStorage) before shiurCurrentlyBeingPlayed can be updated - and because that is multithreaded, it uncertain when that will finish - so once that is done, it will read this variable, call setCurrentlyPlayingShiurInMemoryAndPersist(), and set this variable to null.

fun getCurrentlyPlayingShiur() =
    runCatching { shiurCurrentlyBeingPlayed }.getOrNull() //I don't want the backing field to be accessed directly, because it could throw an uninitialized exception.

fun setCurrentlyPlayingShiurInMemoryAndPersist(shiur: ShiurWithAllFilterMetadata) {
    Util.ld("setCurrentlyPlayingShiurInMemoryAndPersist(shiur=$shiur)")
    if (::shiurCurrentlyBeingPlayed.isInitialized) {
        Util.ld("shiurCurrentlyBeingPlayed initialized, saving progress: $shiurCurrentlyBeingPlayed")
    }
    shiur.isHistory = true
    shiurCurrentlyBeingPlayed = shiur
}

//val pastShiurSerializationVersions = listOf<String>()
class TorahDownloadsApplication : Application() {
    // Using by lazy so the database and the repository are only created when they're needed
    // rather than when the application starts

    @ExperimentalSerializationApi
    override fun onCreate() {
        super.onCreate()
        mEntireApplicationContext = this
        tdApplication = this
        mAppScope = CoroutineScope(SupervisorJob())
        preferencesManager =
            PreferenceManager.getDefaultSharedPreferences(this@TorahDownloadsApplication)
        mAppScope.launch(Dispatchers.IO) {
            keepCache = true
            cacheTimeInSeconds = defaultCacheStaleInSeconds.toInt()
            showHowOldContentIs = true
            ageToDisplayHowOldContentIs = defaultAgeToDisplayHowOldContentIs.toInt()
        }
        setThemeFromSettings()
//                benchmarkFile = File(externalFilesDirRoot, "Benchmarks.txt").createFile()
        mAppScope.launch(Dispatchers.Default) {//launch all pre-processing on a separate thread to improve startup time
            launch(Dispatchers.Default) {
                val networkCallback = object : ConnectivityManager.NetworkCallback() {

                    override fun onAvailable(network: Network) {
                        //TODO when adding ability to only use wifi or mobile data, here should probably be where to check wether this Network object is WiFi, if that is possible
                        ld("User gained network connection.")
                        CONSTANTS.isOnline = true
                    }

                    override fun onLost(network: Network) {
                        ld("User lost network connection.")
                        CONSTANTS.isOnline = false
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).registerDefaultNetworkCallback(
                        networkCallback
                    )
                } else {
                    (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
                        .registerNetworkCallback(
                            NetworkRequest
                                .Builder()
                                .build(),
                            networkCallback
                        )
                    /* TODO known bug with older version: "After further research, I have noticed that there’s been an issue with this callback that seems to call the `onLost()` everytime the user switches capabilities from Cellular to Wi-Fi." - https://evanschepsiror.medium.com/checking-androids-network-connectivity-with-network-callback-fdb8d24a920c
                    *
                    * Consider using:
                    * fun isNetworkAvailable(): Boolean {
                          val connectivityManager = application.getSystemService(Context.CONNECTIVITY_SERVICE)
                          return if (connectivityManager is ConnectivityManager) {
                              connectivityManager.activeNetworkInfo?.isConnected ?: false
                          } else false
                      }
                    * */
                    //TODO where should cm.unregisterNetworkCallback(ConnectivityManager.NetworkCallback()) be called to release the callback? This needs to be considered in light of keeping downloads going in the background. I am not sure if it needs to be called being that when the app is dismissed in Recents, the logs from the callback stop being written, so the system may be releasing the resources.
                }
            }
        }
        configureLogging()
    }

    /**
     * Initialize the logging mechanism.
     *
     * In development, this is a wrapper around the regular Android Log class so the logs can be seen in logcat/AndroidStudio.
     * In production, it logs to Sentry so that we can catch crashes and fix them.
     */
    private fun configureLogging(reportInDebug: Boolean = false) {
        Timber.plant(Timber.DebugTree())
    }
}