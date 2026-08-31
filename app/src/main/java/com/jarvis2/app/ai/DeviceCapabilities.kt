package com.jarvis2.app.ai

/**
 * Nombre de threads CPU a utiliser pour l'inference locale (llama.cpp, via
 * Llamatik/LlamaBridge.updateGenerateParams). Auparavant fixe en dur a 4
 * pour SmolVLM2 comme pour les modeles GGUF selectionnables, ce qui laissait
 * de la puissance CPU inutilisee sur les telephones recents (8+ coeurs) --
 * un des deux leviers de vitesse reels disponibles ici, l'autre etant
 * flashAttention (voir SmolVlmEngine/SelectableLlmEngine). Le GPU n'est
 * volontairement PAS utilise (gpuLayers reste a 0) : le backend natif
 * precompile fourni par Llamatik 1.10.1 pour Android est CPU-only -- aucun
 * backend GPU (Vulkan/OpenCL) n'y est compile (issue upstream ouverte et non
 * resolue : https://github.com/ferranpons/Llamatik/issues/168, "Allow
 * passing extra CMake flags to the native builds (GPU backends)"). Mettre
 * gpuLayers a autre chose que 0 n'aurait donc aucun effet reel avec cette
 * version de la librairie.
 *
 * Garde 2 coeurs libres pour l'UI/le systeme plutot que de saturer tous les
 * coeurs disponibles, ce qui degraderait la fluidite du reste de l'appli
 * pendant une generation (l'utilisateur doit pouvoir scroller/taper pendant
 * que Jarvis reflechit). Borne a [2, 6] : en dessous de 2 la generation est
 * trop lente, au-dela de 6 le gain marginal ne justifie plus de saturer un
 * telephone (chauffe/latence tactile) pour un gain de vitesse modeste au-dela
 * de ce nombre de coeurs sur la plupart des puces mobiles actuelles.
 */
fun recommendedInferenceThreads(): Int {
    val cores = Runtime.getRuntime().availableProcessors()
    return (cores - 2).coerceIn(2, 6)
}
