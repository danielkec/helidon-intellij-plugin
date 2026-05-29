// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.thisLogger
import java.nio.file.Files
import java.nio.file.Path

internal object HelidonOciRegions {
  private const val OCI_CONFIG_DIR = ".oci"
  private const val REGIONS_CONFIG = "regions-config.json"
  private const val REGION_IDENTIFIER = "regionIdentifier"

  fun regionIdentifiers(): List<String> {
    val configuredRegions = configuredRegionIdentifiers()
    return configuredRegions.ifEmpty { BuiltInOciRegion.entries.map { it.regionIdentifier } }
  }

  private fun configuredRegionIdentifiers(): List<String> {
    val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() } ?: return emptyList()
    val path = Path.of(home, OCI_CONFIG_DIR, REGIONS_CONFIG)
    if (!Files.isRegularFile(path)) return emptyList()

    try {
      Files.newBufferedReader(path).use { reader ->
        return JsonParser.parseReader(reader)
          .takeIf { it.isJsonArray }
          ?.asJsonArray
          ?.mapNotNull { it.regionIdentifier() }
          ?.distinct()
          ?: emptyList()
      }
    }
    catch (e: Exception) {
      thisLogger().info("Cannot parse OCI regions config from $path", e)
      return emptyList()
    }
  }

  private fun JsonElement.regionIdentifier(): String? {
    val regionIdentifier = takeIf { it.isJsonObject }
      ?.asJsonObject
      ?.get(REGION_IDENTIFIER)
      ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
      ?.asString
      ?.trim()
    return regionIdentifier?.takeIf { it.isNotEmpty() }
  }
}

internal fun isOciRegionKeyName(keyName: String): Boolean {
  return keyName.startsWith("helidon.") && keyName.endsWith(".region")
}

internal enum class BuiltInOciRegion(val regionIdentifier: String) {
  R1("r1"),
  US_RENTON_1("us-renton-1"),
  US_SCOTTSDALE_1("us-scottsdale-1"),
  AF_CASABLANCA_1("af-casablanca-1"),
  AF_JOHANNESBURG_1("af-johannesburg-1"),
  AF_KIGALI_1("af-kigali-1"),
  AP_CHUNCHEON_1("ap-chuncheon-1"),
  AP_HYDERABAD_1("ap-hyderabad-1"),
  AP_MELBOURNE_1("ap-melbourne-1"),
  AP_MUMBAI_1("ap-mumbai-1"),
  AP_OSAKA_1("ap-osaka-1"),
  AP_SEOUL_1("ap-seoul-1"),
  AP_SINGAPORE_1("ap-singapore-1"),
  AP_SINGAPORE_2("ap-singapore-2"),
  AP_SYDNEY_1("ap-sydney-1"),
  AP_TOKYO_1("ap-tokyo-1"),
  CA_MONTREAL_1("ca-montreal-1"),
  CA_TORONTO_1("ca-toronto-1"),
  EU_AMSTERDAM_1("eu-amsterdam-1"),
  EU_FRANKFURT_1("eu-frankfurt-1"),
  EU_MADRID_1("eu-madrid-1"),
  EU_MADRID_3("eu-madrid-3"),
  EU_MARSEILLE_1("eu-marseille-1"),
  EU_MILAN_1("eu-milan-1"),
  EU_PARIS_1("eu-paris-1"),
  EU_STOCKHOLM_1("eu-stockholm-1"),
  EU_ZURICH_1("eu-zurich-1"),
  IL_JERUSALEM_1("il-jerusalem-1"),
  ME_ABUDHABI_1("me-abudhabi-1"),
  ME_DUBAI_1("me-dubai-1"),
  ME_JEDDAH_1("me-jeddah-1"),
  ME_NEOM_1("me-neom-1"),
  ME_RIYADH_1("me-riyadh-1"),
  MX_MONTERREY_1("mx-monterrey-1"),
  MX_QUERETARO_1("mx-queretaro-1"),
  SA_BOGOTA_1("sa-bogota-1"),
  SA_SANTIAGO_1("sa-santiago-1"),
  SA_SAOPAULO_1("sa-saopaulo-1"),
  SA_VALPARAISO_1("sa-valparaiso-1"),
  SA_VINHEDO_1("sa-vinhedo-1"),
  UK_CARDIFF_1("uk-cardiff-1"),
  UK_LONDON_1("uk-london-1"),
  US_ASHBURN_1("us-ashburn-1"),
  US_CHICAGO_1("us-chicago-1"),
  US_PHOENIX_1("us-phoenix-1"),
  US_SALTLAKE_2("us-saltlake-2"),
  US_SANJOSE_1("us-sanjose-1"),
  US_LANGLEY_1("us-langley-1"),
  US_LUKE_1("us-luke-1"),
  US_GOV_ASHBURN_1("us-gov-ashburn-1"),
  US_GOV_CHICAGO_1("us-gov-chicago-1"),
  US_GOV_PHOENIX_1("us-gov-phoenix-1"),
  UK_GOV_CARDIFF_1("uk-gov-cardiff-1"),
  UK_GOV_LONDON_1("uk-gov-london-1"),
  US_TACOMA_1("us-tacoma-1"),
  US_GOV_FORTWORTH_1("us-gov-fortworth-1"),
  US_GOV_STERLING_2("us-gov-sterling-2"),
  US_GOV_RESTON_1("us-gov-reston-1"),
  US_GOV_SEATTLE_1("us-gov-seattle-1"),
  AP_CHIYODA_1("ap-chiyoda-1"),
  AP_IBARAKI_1("ap-ibaraki-1"),
  ME_DCC_MUSCAT_1("me-dcc-muscat-1"),
  ME_IBRI_1("me-ibri-1"),
  AP_DCC_CANBERRA_1("ap-dcc-canberra-1"),
  US_GOV_FORTWORTH_3("us-gov-fortworth-3"),
  US_GOV_PHOENIX_3("us-gov-phoenix-3"),
  US_GOV_STERLING_3("us-gov-sterling-3"),
  US_GOV_ASHBURN_2("us-gov-ashburn-2"),
  US_GOV_PHOENIX_2("us-gov-phoenix-2"),
  US_GOV_SALTLAKE_1("us-gov-saltlake-1"),
  EU_DCC_DUBLIN_1("eu-dcc-dublin-1"),
  EU_DCC_DUBLIN_2("eu-dcc-dublin-2"),
  EU_DCC_MILAN_1("eu-dcc-milan-1"),
  EU_DCC_MILAN_2("eu-dcc-milan-2"),
  EU_DCC_RATING_1("eu-dcc-rating-1"),
  EU_DCC_RATING_2("eu-dcc-rating-2"),
  AP_DCC_GAZIPUR_1("ap-dcc-gazipur-1"),
  US_WESTJORDAN_1("us-westjordan-1"),
  US_DCC_PHOENIX_1("us-dcc-phoenix-1"),
  US_DCC_PHOENIX_2("us-dcc-phoenix-2"),
  US_DCC_PHOENIX_4("us-dcc-phoenix-4"),
  EU_FRANKFURT_2("eu-frankfurt-2"),
  EU_MADRID_2("eu-madrid-2"),
  EU_JOVANOVAC_1("eu-jovanovac-1"),
  ME_DCC_DOHA_1("me-dcc-doha-1"),
  EU_DCC_ROME_1("eu-dcc-rome-1"),
  US_SOMERSET_1("us-somerset-1"),
  EU_DCC_ZURICH_1("eu-dcc-zurich-1"),
  AP_DCC_OSAKA_1("ap-dcc-osaka-1"),
  AP_DCC_TOKYO_1("ap-dcc-tokyo-1"),
  ME_ABUDHABI_3("me-abudhabi-3"),
  ME_DUBAI_3("me-dubai-3"),
  US_DCC_SWJORDAN_1("us-dcc-swjordan-1"),
  US_DCC_SWJORDAN_2("us-dcc-swjordan-2"),
  ME_ABUDHABI_2("me-abudhabi-2"),
  ME_DUBAI_2("me-dubai-2"),
  SOL_FENRIR_1("sol-fenrir-1"),
  SOL_HYPERION_1("sol-hyperion-1"),
  SOL_JANUS_1("sol-janus-1"),
  SOL_MIMAS_1("sol-mimas-1"),
  SOL_PHOEBE_1("sol-phoebe-1"),
  SOL_SATURN_1("sol-saturn-1"),
  SOL_TITAN_1("sol-titan-1"),
  SOL_JUPITER_1("sol-jupiter-1"),
  SOL_MERCURY_1("sol-mercury-1"),
  SOL_VENUS_1("sol-venus-1"),
  SOL_DEIMOS_1("sol-deimos-1"),
  SOL_MARS_1("sol-mars-1"),
  SOL_ARIEL_1("sol-ariel-1"),
  SOL_URANUS_1("sol-uranus-1"),
  SOL_NEPTUNE_1("sol-neptune-1"),
  SOL_TRITON_1("sol-triton-1"),
  SOL_HYDRA_1("sol-hydra-1"),
  SOL_PLUTO_1("sol-pluto-1"),
  SOL_CERES_1("sol-ceres-1"),
  SOL_PALLAS_1("sol-pallas-1"),
  SOL_PUCK_1("sol-puck-1"),
  SOL_CORDELIA_1("sol-cordelia-1"),
  SOL_UMBRIEL_1("sol-umbriel-1"),
  SOL_CALIBAN_1("sol-caliban-1"),
  SOL_OBERON_1("sol-oberon-1"),
  SOL_SETEBOS_1("sol-setebos-1"),
}
