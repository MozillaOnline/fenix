/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.components

import GeckoProvider
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.StrictMode
import androidx.core.content.ContextCompat
import io.sentry.Sentry
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.engine.gecko.fetch.GeckoViewFetchClient
import mozilla.components.browser.icons.BrowserIcons
import mozilla.components.browser.session.Session
import mozilla.components.browser.session.SessionManager
import mozilla.components.browser.session.engine.EngineMiddleware
import mozilla.components.browser.session.storage.SessionStorage
import mozilla.components.browser.session.undo.UndoMiddleware
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.browser.storage.sync.PlacesBookmarksStorage
import mozilla.components.browser.storage.sync.PlacesHistoryStorage
import mozilla.components.browser.storage.sync.RemoteTabsStorage
import mozilla.components.browser.thumbnails.ThumbnailsMiddleware
import mozilla.components.browser.thumbnails.storage.ThumbnailStorage
import mozilla.components.concept.base.crash.CrashReporting
import mozilla.components.concept.engine.DefaultSettings
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.mediaquery.PreferredColorScheme
import mozilla.components.concept.fetch.Client
import mozilla.components.feature.customtabs.store.CustomTabsServiceStore
import mozilla.components.feature.downloads.DownloadMiddleware
import mozilla.components.feature.logins.exceptions.LoginExceptionStorage
import mozilla.components.feature.media.MediaSessionFeature
import mozilla.components.feature.media.middleware.MediaMiddleware
import mozilla.components.feature.media.middleware.RecordingDevicesMiddleware
import mozilla.components.feature.pwa.ManifestStorage
import mozilla.components.feature.pwa.WebAppShortcutManager
import mozilla.components.feature.readerview.ReaderViewMiddleware
import mozilla.components.feature.recentlyclosed.RecentlyClosedMiddleware
import mozilla.components.feature.search.middleware.SearchMiddleware
import mozilla.components.feature.search.region.RegionMiddleware
import mozilla.components.feature.session.HistoryDelegate
import mozilla.components.feature.top.sites.DefaultTopSitesStorage
import mozilla.components.feature.top.sites.PinnedSiteStorage
import mozilla.components.feature.webcompat.WebCompatFeature
import mozilla.components.feature.webcompat.reporter.WebCompatReporterFeature
import mozilla.components.feature.webnotifications.WebNotificationFeature
import mozilla.components.lib.dataprotect.SecureAbove22Preferences
import mozilla.components.lib.dataprotect.generateEncryptionKey
import mozilla.components.service.digitalassetlinks.RelationChecker
import mozilla.components.service.digitalassetlinks.local.StatementApi
import mozilla.components.service.digitalassetlinks.local.StatementRelationChecker
import mozilla.components.service.location.LocationService
import mozilla.components.service.location.MozillaLocationService
import mozilla.components.service.sync.logins.SyncableLoginsStorage
import mozilla.components.support.locale.LocaleManager
import org.mozilla.fenix.AppRequestInterceptor
import org.mozilla.fenix.BuildConfig
import org.mozilla.fenix.Config
import org.mozilla.fenix.FeatureFlags.newMediaSessionApi
import org.mozilla.fenix.HomeActivity
import org.mozilla.fenix.R
import org.mozilla.fenix.TelemetryMiddleware
import org.mozilla.fenix.components.search.SearchMigration
import org.mozilla.fenix.downloads.DownloadService
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.ext.settings
import org.mozilla.fenix.media.MediaService
import org.mozilla.fenix.media.MediaSessionService
import org.mozilla.fenix.perf.StrictModeManager
import org.mozilla.fenix.perf.lazyMonitored
import org.mozilla.fenix.search.telemetry.ads.AdsTelemetry
import org.mozilla.fenix.search.telemetry.incontent.InContentTelemetry
import org.mozilla.fenix.settings.SupportUtils
import org.mozilla.fenix.settings.advanced.getSelectedLocale
import org.mozilla.fenix.utils.Mockable
import org.mozilla.fenix.utils.getUndoDelay

/**
 * Component group for all core browser functionality.
 */
@Mockable
@Suppress("LargeClass")
class Core(
    private val context: Context,
    private val crashReporter: CrashReporting,
    strictMode: StrictModeManager
) {
    /**
     * The browser engine component initialized based on the build
     * configuration (see build variants).
     */
    val engine: Engine by lazyMonitored {
        val defaultSettings = DefaultSettings(
            requestInterceptor = requestInterceptor,
            remoteDebuggingEnabled = context.settings().isRemoteDebuggingEnabled &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M,
            testingModeEnabled = false,
            trackingProtectionPolicy = trackingProtectionPolicyFactory.createTrackingProtectionPolicy(),
            historyTrackingDelegate = HistoryDelegate(lazyHistoryStorage),
            preferredColorScheme = getPreferredColorScheme(),
            automaticFontSizeAdjustment = context.settings().shouldUseAutoSize,
            fontInflationEnabled = context.settings().shouldUseAutoSize,
            suspendMediaWhenInactive = false,
            forceUserScalableContent = context.settings().forceEnableZoom,
            loginAutofillEnabled = context.settings().shouldAutofillLogins,
            clearColor = ContextCompat.getColor(
                context,
                R.color.foundation_normal_theme
            )
        )

        GeckoEngine(
            context,
            defaultSettings,
            GeckoProvider.getOrCreateRuntime(
                context,
                lazyPasswordsStorage,
                trackingProtectionPolicyFactory.createTrackingProtectionPolicy()
            )
        ).also {
            WebCompatFeature.install(it)

            /**
             * There are some issues around localization to be resolved, as well as questions around
             * the capacity of the WebCompat team, so the "Report site issue" feature should stay
             * disabled in Fenix Release builds for now.
             * This is consistent with both Fennec and Firefox Desktop.
             */
            if (Config.channel.isNightlyOrDebug || Config.channel.isBeta) {
                WebCompatReporterFeature.install(it, "fenix")
            }
        }
    }

    /**
     * Passed to [engine] to intercept requests for app links,
     * and various features triggered by page load requests.
     *
     * NB: This does not need to be lazy as it is initialized
     * with the engine on startup.
     */
    val requestInterceptor = AppRequestInterceptor(context)

    /**
     * [Client] implementation to be used for code depending on `concept-fetch``
     */
    val client: Client by lazyMonitored {
        GeckoViewFetchClient(
            context,
            GeckoProvider.getOrCreateRuntime(
                context,
                lazyPasswordsStorage,
                trackingProtectionPolicyFactory.createTrackingProtectionPolicy()
            )
        )
    }

    val sessionStorage: SessionStorage by lazyMonitored {
        SessionStorage(context, engine = engine)
    }

    private val locationService: LocationService by lazyMonitored {
        if(Config.channel.isMozillaOnline){
            LocationService.defaultChina()
        } else if (Config.channel.isDebug || BuildConfig.MLS_TOKEN.isEmpty()) {
            LocationService.default()
        } else {
            MozillaLocationService(context, client, BuildConfig.MLS_TOKEN)
        }
    }

    /**
     * The [BrowserStore] holds the global [BrowserState].
     */
    val store by lazyMonitored {
        val middlewareList =
            mutableListOf(
                RecentlyClosedMiddleware(context, RECENTLY_CLOSED_MAX, engine),
                DownloadMiddleware(context, DownloadService::class.java),
                ReaderViewMiddleware(),
                TelemetryMiddleware(
                    context.settings(),
                    adsTelemetry,
                    metrics
                ),
                ThumbnailsMiddleware(thumbnailStorage),
                UndoMiddleware(::lookupSessionManager, context.getUndoDelay()),
                RegionMiddleware(context, locationService),
                SearchMiddleware(
                    context,
                    additionalBundledSearchEngineIds = listOf("reddit", "youtube"),
                    migration = SearchMigration(context)
                ),
                RecordingDevicesMiddleware(context)
            )

        if (!newMediaSessionApi) {
            middlewareList.add(MediaMiddleware(context, MediaService::class.java))
        }

        BrowserStore(
            middleware = middlewareList + EngineMiddleware.create(engine, ::findSessionById)
        )
    }

    private fun lookupSessionManager(): SessionManager {
        return sessionManager
    }

    private fun findSessionById(tabId: String): Session? {
        return sessionManager.findSessionById(tabId)
    }

    /**
     * The [CustomTabsServiceStore] holds global custom tabs related data.
     */
    val customTabsStore by lazyMonitored { CustomTabsServiceStore() }

    /**
     * The [RelationChecker] checks Digital Asset Links relationships for Trusted Web Activities.
     */
    val relationChecker: RelationChecker by lazyMonitored {
        StatementRelationChecker(StatementApi(client))
    }

    /**
     * The session manager component provides access to a centralized registry of
     * all browser sessions (i.e. tabs). It is initialized here to persist and restore
     * sessions from the [SessionStorage], and with a default session (about:blank) in
     * case all sessions/tabs are closed.
     */
    val sessionManager by lazyMonitored {
        SessionManager(engine, store).also {
            // Install the "icons" WebExtension to automatically load icons for every visited website.
            icons.install(engine, store)

            // Install the "ads" WebExtension to get the links in an partner page.
            adsTelemetry.install(engine, store)

            // Install the "cookies" WebExtension and tracks user interaction with SERPs.
            searchTelemetry.install(engine, store)

            WebNotificationFeature(
                context, engine, icons, R.drawable.ic_status_logo,
                permissionStorage.permissionsStorage, HomeActivity::class.java
            )

            if (newMediaSessionApi) {
                MediaSessionFeature(context, MediaSessionService::class.java, store).start()
            }
        }
    }

    /**
     * Icons component for loading, caching and processing website icons.
     */
    val icons by lazyMonitored {
        BrowserIcons(context, client)
    }

    val metrics by lazyMonitored {
        context.components.analytics.metrics
    }

    val adsTelemetry by lazyMonitored {
        AdsTelemetry(metrics)
    }

    val searchTelemetry by lazyMonitored {
        InContentTelemetry(metrics)
    }

    /**
     * Shortcut component for managing shortcuts on the device home screen.
     */
    val webAppShortcutManager by lazyMonitored {
        WebAppShortcutManager(
            context,
            client,
            webAppManifestStorage
        )
    }

    // Lazy wrappers around storage components are used to pass references to these components without
    // initializing them until they're accessed.
    // Use these for startup-path code, where we don't want to do any work that's not strictly necessary.
    // For example, this is how the GeckoEngine delegates (history, logins) are configured.
    // We can fully initialize GeckoEngine without initialized our storage.
    val lazyHistoryStorage = lazyMonitored { PlacesHistoryStorage(context, crashReporter) }
    val lazyBookmarksStorage = lazyMonitored { PlacesBookmarksStorage(context) }
    val lazyPasswordsStorage = lazyMonitored { SyncableLoginsStorage(context, passwordsEncryptionKey) }

    /**
     * The storage component to sync and persist tabs in a Firefox Sync account.
     */
    val lazyRemoteTabsStorage = lazyMonitored { RemoteTabsStorage() }

    // For most other application code (non-startup), these wrappers are perfectly fine and more ergonomic.
    val historyStorage: PlacesHistoryStorage get() = lazyHistoryStorage.value
    val bookmarksStorage: PlacesBookmarksStorage get() = lazyBookmarksStorage.value
    val passwordsStorage: SyncableLoginsStorage get() = lazyPasswordsStorage.value

    val tabCollectionStorage by lazyMonitored {
        TabCollectionStorage(
            context,
            strictMode
        )
    }

    /**
     * A storage component for persisting thumbnail images of tabs.
     */
    val thumbnailStorage by lazyMonitored { ThumbnailStorage(context) }

    val pinnedSiteStorage by lazyMonitored { PinnedSiteStorage(context) }

    val topSitesStorage by lazyMonitored {
        val defaultTopSites = mutableListOf<Pair<String, String>>()

        strictMode.resetAfter(StrictMode.allowThreadDiskReads()) {
            if (!context.settings().defaultTopSitesAdded) {
                if (Config.channel.isMozillaOnline) {
                    defaultTopSites.add(
                        Pair(
                            context.getString(R.string.default_top_site_baidu),
                            SupportUtils.BAIDU_URL
                        )
                    )

                    defaultTopSites.add(
                        Pair(
                            context.getString(R.string.default_top_site_jd),
                            SupportUtils.JD_URL
                        )
                    )
                } else {
                    defaultTopSites.add(
                        Pair(
                            context.getString(R.string.default_top_site_google),
                            SupportUtils.GOOGLE_URL
                        )
                    )

                    if (LocaleManager.getSelectedLocale(context).language == "en") {
                        defaultTopSites.add(
                            Pair(
                                context.getString(R.string.pocket_pinned_top_articles),
                                SupportUtils.POCKET_TRENDING_URL
                            )
                        )
                    }

                    defaultTopSites.add(
                        Pair(
                            context.getString(R.string.default_top_site_wikipedia),
                            SupportUtils.WIKIPEDIA_URL
                        )
                    )
                }

                context.settings().defaultTopSitesAdded = true
            }
        }

        DefaultTopSitesStorage(
            pinnedSiteStorage,
            historyStorage,
            defaultTopSites
        )
    }

    val permissionStorage by lazyMonitored { PermissionStorage(context) }

    val webAppManifestStorage by lazyMonitored { ManifestStorage(context) }

    val loginExceptionStorage by lazyMonitored { LoginExceptionStorage(context) }

    /**
     * Shared Preferences that encrypt/decrypt using Android KeyStore and lib-dataprotect for 23+
     * only on Nightly/Debug for now, otherwise simply stored.
     * See https://github.com/mozilla-mobile/fenix/issues/8324
     */
    private fun getSecureAbove22Preferences() =
        SecureAbove22Preferences(
            context = context,
            name = KEY_STORAGE_NAME,
            forceInsecure = !Config.channel.isNightlyOrDebug
        )

    private val passwordsEncryptionKey by lazyMonitored {
        getSecureAbove22Preferences().getString(PASSWORDS_KEY)
            ?: generateEncryptionKey(KEY_STRENGTH).also {
                if (context.settings().passwordsEncryptionKeyGenerated &&
                    isSentryEnabled()
                ) {
                    // We already had previously generated an encryption key, but we have lost it
                    Sentry.capture("Passwords encryption key for passwords storage was lost and we generated a new one")
                }
                context.settings().recordPasswordsEncryptionKeyGenerated()
                getSecureAbove22Preferences().putString(PASSWORDS_KEY, it)
            }
    }

    val trackingProtectionPolicyFactory = TrackingProtectionPolicyFactory(context.settings())

    /**
     * Sets Preferred Color scheme based on Dark/Light Theme Settings or Current Configuration
     */
    fun getPreferredColorScheme(): PreferredColorScheme {
        val inDark =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
        return when {
            context.settings().shouldUseDarkTheme -> PreferredColorScheme.Dark
            context.settings().shouldUseLightTheme -> PreferredColorScheme.Light
            inDark -> PreferredColorScheme.Dark
            else -> PreferredColorScheme.Light
        }
    }

    companion object {
        private const val KEY_STRENGTH = 256
        private const val KEY_STORAGE_NAME = "core_prefs"
        private const val PASSWORDS_KEY = "passwords"
        private const val RECENTLY_CLOSED_MAX = 10
    }
}
