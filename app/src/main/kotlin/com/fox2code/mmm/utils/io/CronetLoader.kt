package com.fox2code.mmm.utils.io

import android.content.Context
import android.content.pm.PackageManager
import timber.log.Timber

/**
 * Utility for loading Cronet engine builder through reflection
 * without direct dependency on Google Play Services
 *
 * Of course, actually getting the provider does rely on GMS but we have a fallback packaged in the app.
 */
class CronetLoader {
    companion object {
        private const val GMS_PROVIDER_NAME = "Google-Play-Services-Cronet-Provider"
        private const val GMS_PACKAGE = "com.google.android.gms"
        private const val DYNAMITE_MODULE_CLASS = "com.google.android.gms.dynamite.DynamiteModule"
        private const val DYNAMITE_MODULE_ID = "com.google.android.gms.cronet_dynamite"

        /**
         * Get a Cronet engine builder via reflection, attempting to install GMS provider first
         *
         * @param context Application context
         * @return CronetEngine.Builder instance or null if unavailable
         */
        fun getCronetEngineBuilder(context: Context): Any? {
            // Check if GMS is installed
            val gmsInstalled = isGmsInstalled(context)

            if (gmsInstalled) {
                Timber.d("GMS is installed, attempting to load Cronet provider")
                // Try to initialize GMS provider
                try {
                    installGmsProviderViaReflection(context)
                } catch (e: Exception) {
                    Timber.w("Failed to install GMS Cronet provider: ${e.message}")
                    false
                }
            } else {
                Timber.d("GMS is not installed, skipping GMS provider initialization")
            }

            // Whether GMS was initialized or not, try to get available providers
            return getProviderBuilder(context)
        }

        /**
         * Check if Google Play Services is installed on the device
         */
        private fun isGmsInstalled(context: Context): Boolean {
            return try {
                // Try to create a package context for GMS
                context.createPackageContext(
                    GMS_PACKAGE, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
                )
                true
            } catch (e: PackageManager.NameNotFoundException) {
                Timber.d("Google Play Services not installed")
                false
            } catch (e: Exception) {
                Timber.d("Error checking for Google Play Services: ${e.message}")
                false
            }
        }

        /**
         * Attempt to install GMS Cronet provider using reflection
         */
        private fun installGmsProviderViaReflection(context: Context): Boolean {
            try {
                // Try to load Dynamite module class
                val dynamiteClass = try {
                    Class.forName(DYNAMITE_MODULE_CLASS)
                } catch (e: ClassNotFoundException) {
                    Timber.d("DynamiteModule class not found")
                    return false
                }

                // Find the load method
                val loadMethod = try {
                    // We need to find the right version of the load method
                    dynamiteClass.getDeclaredMethods().firstOrNull { method ->
                        method.name == "load" && method.parameterTypes.size == 3 && method.parameterTypes[0] == Context::class.java && method.parameterTypes[2] == String::class.java
                    }
                } catch (e: Exception) {
                    Timber.d("Could not find appropriate load method: ${e.message}")
                    return false
                }

                if (loadMethod == null) {
                    Timber.d("Could not find appropriate load method")
                    return false
                }

                // Get the PREFER_REMOTE constant
                val preferRemoteObj = try {
                    val fields = dynamiteClass.declaredFields
                    val preferRemoteField = fields.firstOrNull { field ->
                        field.name == "PREFER_REMOTE"
                    }

                    if (preferRemoteField == null) {
                        Timber.d("PREFER_REMOTE field not found")
                        return false
                    }

                    preferRemoteField.get(null)
                } catch (e: Exception) {
                    Timber.d("Error getting PREFER_REMOTE: ${e.message}")
                    return false
                }

                try {
                    // Attempt to load the Cronet dynamite module
                    loadMethod.invoke(null, context, preferRemoteObj, DYNAMITE_MODULE_ID)
                    Timber.d("Successfully loaded GMS Cronet dynamite module")
                    return true
                } catch (e: Exception) {
                    Timber.d("Failed to load Cronet dynamite module: ${e.message}")
                    return false
                }

            } catch (e: Exception) {
                Timber.d("Error during GMS provider installation: ${e.message}")
                return false
            }
        }

        /**
         * Get a builder from available providers, prioritizing GMS
         */
        private fun getProviderBuilder(context: Context): Any? {
            try {
                // Try to access the CronetProvider class
                val providerClass = try {
                    Class.forName("org.chromium.net.CronetProvider")
                } catch (e: ClassNotFoundException) {
                    Timber.d("Cronet API not available")
                    return null
                }

                // Get all available providers
                val getAllProvidersMethod =
                    providerClass.getMethod("getAllProviders", Context::class.java)
                val providers =
                    getAllProvidersMethod.invoke(null, context) as? List<*> ?: emptyList<Any>()

                if (providers.isEmpty()) {
                    Timber.d("No Cronet providers found")
                    return null
                }

                // Get method references we'll need
                val isEnabledMethod = providerClass.getMethod("isEnabled")
                val getNameMethod = providerClass.getMethod("getName")
                val createBuilderMethod = providerClass.getMethod("createBuilder")

                // Get fallback name constant
                val fallbackName = try {
                    val field = providerClass.getField("PROVIDER_NAME_FALLBACK")
                    field.get(null) as String
                } catch (e: Exception) {
                    "fallback"
                }

                // Get native name constant
                val nativeName = try {
                    val field = providerClass.getField("PROVIDER_NAME_APP_PACKAGED")
                    field.get(null) as String
                } catch (e: Exception) {
                    "APP_PACKAGED"
                }

                // Filter and categorize providers
                val gmsProviders = mutableListOf<Any>()
                val nativeProviders = mutableListOf<Any>()
                val otherProviders = mutableListOf<Any>()

                for (provider in providers) {
                    try {
                        val isEnabled = isEnabledMethod.invoke(provider) as Boolean
                        val name = getNameMethod.invoke(provider) as String

                        if (isEnabled && name != fallbackName && provider != null) {
                            when (name) {
                                GMS_PROVIDER_NAME -> gmsProviders.add(provider)
                                nativeName -> nativeProviders.add(provider)
                                else -> otherProviders.add(provider)
                            }
                            Timber.d("Found enabled provider: $name")
                        }
                    } catch (e: Exception) {
                        Timber.d("Error checking provider: ${e.message}")
                    }
                }

                // Try providers in order of preference
                val orderedProviders = gmsProviders + nativeProviders + otherProviders

                for (provider in orderedProviders) {
                    try {
                        val name = getNameMethod.invoke(provider) as String
                        val builder = createBuilderMethod.invoke(provider)
                        Timber.d("Successfully created Cronet builder using: $name")
                        return builder
                    } catch (e: Exception) {
                        Timber.w("Failed to create builder from provider: ${e.message}")
                    }
                }

                Timber.d("Could not create Cronet builder from any provider")

            } catch (e: Exception) {
                Timber.e(e, "Error getting Cronet providers")
            }

            return null
        }
    }
}