package com.jarvis2.app.ai.gguf

/**
 * Catalogue des modeles GGUF optionnels proposes en remplacement de Gemma 3
 * 1B (retire -- verrouille derriere une licence Hugging Face, contrairement
 * a ces trois-la). Chaque entree a ete verifiee individuellement via l'API
 * Hugging Face avant d'etre ajoutee ici :
 *  - GET https://huggingface.co/api/models/{repo} -> "gated": false
 *  - GET https://huggingface.co/api/models/{repo}/tree/main -> taille exacte
 *    en octets du fichier Q4_K_M precis (le champ gguf.totalFileSize de la
 *    1ere requete n'est PAS la taille d'une quantification particuliere).
 * Aucun des trois ne necessite de compte, de jeton d'acces personnel ni de
 * cle API -- exactement ce que l'utilisateur a demande en remplacement de
 * Gemma. Quantification Q4_K_M choisie pour chacun : le compromis
 * taille/qualite habituel de la communaute llama.cpp.
 */
enum class LocalGgufModel(
    val id: String,
    val displayName: String,
    val repo: String,
    val filename: String,
    val sizeBytes: Long,
    val license: String,
) {
    QWEN_2_5_1_5B(
        id = "qwen2.5-1.5b",
        displayName = "Qwen 2.5 1.5B Instruct",
        repo = "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
        filename = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        sizeBytes = 1_117_320_736L,
        license = "Apache-2.0",
    ),
    PHI_3_5_MINI(
        id = "phi-3.5-mini",
        displayName = "Phi-3.5 mini Instruct",
        repo = "bartowski/Phi-3.5-mini-instruct-GGUF",
        filename = "Phi-3.5-mini-instruct-Q4_K_M.gguf",
        sizeBytes = 2_393_232_672L,
        license = "MIT",
    ),
    DOLPHIN_3_QWEN_2_5_1_5B(
        id = "dolphin3-qwen2.5-1.5b",
        displayName = "Dolphin 3.0 (Qwen2.5 1.5B)",
        repo = "bartowski/Dolphin3.0-Qwen2.5-1.5B-GGUF",
        filename = "Dolphin3.0-Qwen2.5-1.5B-Q4_K_M.gguf",
        sizeBytes = 986_051_648L,
        license = "Apache-2.0",
    );

    val downloadUrl: String get() = "https://huggingface.co/$repo/resolve/main/$filename"

    companion object {
        fun byId(id: String?): LocalGgufModel? = entries.firstOrNull { it.id == id }
    }
}
