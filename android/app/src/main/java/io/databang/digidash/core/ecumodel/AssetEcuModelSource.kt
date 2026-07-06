package io.databang.digidash.core.ecumodel

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads the ECU models bundled in the APK under assets/ecu_models. */
class AssetEcuModelSource(
    private val context: Context,
    private val assetRoot: String = "ecu_models",
) : EcuModelSource {
    override suspend fun read(relativePath: String): String = withContext(Dispatchers.IO) {
        context.assets.open("$assetRoot/$relativePath").bufferedReader().use { it.readText() }
    }
}
