package com.jarvis2.app.ai.gguf

/**
 * Catalogue des modeles GGUF optionnels proposes en remplacement de Gemma 3
 * 1B (retire -- verrouille derriere une licence Hugging Face, contrairement
 * a ceux-la). Chaque entree a ete verifiee individuellement via l'API
 * Hugging Face avant d'etre ajoutee ici :
 *  - GET https://huggingface.co/api/models/{repo} -> "gated": false
 *  - GET https://huggingface.co/api/models/{repo}/tree/main -> taille exacte
 *    en octets du fichier precis choisi (le champ gguf.totalFileSize de la
 *    1ere requete n'est PAS la taille d'une quantification particuliere).
 * Aucun ne necessite de compte, de jeton d'acces personnel ni de cle API.
 * Quantification Q4_K_M choisie par defaut pour les petits modeles : le
 * compromis taille/qualite habituel de la communaute llama.cpp. BONSAI_27B
 * fait exception (voir sa doc) : quantification native 1-bit du modele lui-
 * meme, pas un post-traitement Q4_K_M d'un GGUF F16 classique.
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
    ),

    /**
     * Bonsai 27B (PrismML, juillet 2026) : derive quantifie en 1-bit natif de
     * Qwen3.6-27B -- premier modele "classe 27B" tenant sur un telephone
     * (~3.8 Go pour la variante Q1_0 ici retenue, contre ~54 Go en F16).
     * Repo verifie non gated (GET /api/models/prism-ml/Bonsai-27B-gguf ->
     * "gated": false, license apache-2.0) ; taille exacte lue sur
     * /tree/main pour Bonsai-27B-Q1_0.gguf.
     *
     * A la difference des trois modeles ci-dessus, c'est un poids lourd :
     * bien plus gros et plus lent a charger/inferer sur telephone que Qwen
     * 1.5B/Phi-3.5 mini/Dolphin, et son architecture ("qwen35", attention
     * hybride + noyaux 1-bit dedies) est recente -- si la version de
     * llama.cpp embarquee dans Llamatik ne la supporte pas encore,
     * LlamaBridge.initGenerateModel() echouera proprement (voir
     * SelectableLlmEngine.prepare(), qui remonte alors l'erreur via
     * lastError sans planter l'appli). Ajoute a la demande explicite de
     * l'utilisateur, pas de mmproj (vision) embarque ici : cette entree
     * reste texte-seul comme les trois autres, en coherence avec le reste
     * du catalogue.
     */
    BONSAI_27B(
        id = "bonsai-27b",
        displayName = "Bonsai 27B (1-bit, PrismML)",
        repo = "prism-ml/Bonsai-27B-gguf",
        filename = "Bonsai-27B-Q1_0.gguf",
        sizeBytes = 3_803_452_480L,
        license = "Apache-2.0",
    ),

    /**
     * Gemma 4 E4B (Google, avril 2026) : contrairement a Gemma 3, publie
     * directement sous Apache-2.0 (plus de licence Hugging Face restrictive
     * -- voir la doc de classe et task #231/#263, raison initiale du retrait
     * de Gemma). "E4B" = 4.5 milliards de parametres effectifs (~8 Md avec
     * les embeddings, architecture elastique type Matformer), multimodal
     * texte/image/audio a l'entrainement, 128K tokens de contexte. Repo
     * verifie non gated. N'expose ici que le texte (pas de mmproj/vision --
     * meme choix que BONSAI_27B, coherence du catalogue).
     */
    GEMMA_4_E4B(
        id = "gemma4-e4b",
        displayName = "Gemma 4 E4B",
        repo = "bartowski/google_gemma-4-E4B-it-GGUF",
        filename = "google_gemma-4-E4B-it-Q4_K_M.gguf",
        sizeBytes = 5_405_168_384L,
        license = "Apache-2.0",
    ),

    /**
     * LFM2.5-VL 1.6B (Liquid AI) : reponse a la demande explicite de
     * l'utilisateur d'un modele local, multimodal, RAPIDE (comparable a la
     * vitesse d'inference cloud type Groq) -- c'est le point fort revendique
     * de cette famille (228 tok/s sur Apple M5 Max, <3.3 Go de RAM pour la
     * variante 3B ; celle-ci, 1.6B, est plus petite/rapide encore, pensee
     * pour l'edge/telephone). Licence "LFM Open License v1.0" (pas Apache/
     * MIT) : usage libre pour un particulier, restriction Commercial Use
     * uniquement au-dela de 10 M$ de revenu annuel -- sans objet ici.
     * Non gated, aucun compte requis. Texte seul expose (pas de mmproj),
     * comme les autres entrees.
     */
    LFM_2_5_VL_1_6B(
        id = "lfm2.5-vl-1.6b",
        displayName = "LFM2.5-VL 1.6B (Liquid AI, rapide)",
        repo = "LiquidAI/LFM2.5-VL-1.6B-GGUF",
        filename = "LFM2.5-VL-1.6B-Q4_K_M.gguf",
        sizeBytes = 730_896_256L,
        license = "LFM Open v1.0 (libre, <10M$/an)",
    );

    val downloadUrl: String get() = "https://huggingface.co/$repo/resolve/main/$filename"

    companion object {
        fun byId(id: String?): LocalGgufModel? = entries.firstOrNull { it.id == id }
    }
}
