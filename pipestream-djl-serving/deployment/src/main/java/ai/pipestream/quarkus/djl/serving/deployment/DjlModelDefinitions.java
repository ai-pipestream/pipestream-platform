package ai.pipestream.quarkus.djl.serving.deployment;

import java.util.List;

/**
 * Static definitions of all embedding models supported by DJL Serving.
 * <p>
 * This mirrors the EmbeddingModel enum in module-embedder but lives in the
 * deployment module to avoid a cross-module dependency. Keep in sync manually.
 */
final class DjlModelDefinitions {

    private DjlModelDefinitions() {}

    record ModelDefinition(
            String enumName,
            String djlServingName,
            String archiveFileName,
            int dimensions,
            String djlUri,
            boolean pythonHandler
    ) {}

    // Archive filenames must match what's in S3 (HuggingFace-style names with hyphens)
    static final List<ModelDefinition> ALL_MODELS = List.of(
            new ModelDefinition(
                    "ALL_MINILM_L6_V2", "minilm", "all-MiniLM-L6-v2",
                    384, "djl://ai.djl.huggingface.pytorch/sentence-transformers/all-MiniLM-L6-v2", false),
            new ModelDefinition(
                    "ALL_MPNET_BASE_V2", "mpnet", "all-mpnet-base-v2",
                    768, "djl://ai.djl.huggingface.pytorch/sentence-transformers/all-mpnet-base-v2", true),
            new ModelDefinition(
                    "ALL_DISTILROBERTA_V1", "all_distilroberta_v1", "all-distilroberta-v1",
                    768, "djl://ai.djl.huggingface.pytorch/sentence-transformers/all-distilroberta-v1", true),
            new ModelDefinition(
                    "PARAPHRASE_MINILM_L3_V2", "paraphrase_MiniLM_L3_v2", "paraphrase-MiniLM-L3-v2",
                    384, "djl://ai.djl.huggingface.pytorch/sentence-transformers/paraphrase-MiniLM-L3-v2", false),
            new ModelDefinition(
                    "PARAPHRASE_MULTILINGUAL_MINILM_L12_V2", "paraphrase_multilingual_MiniLM_L12_v2", "paraphrase-multilingual-MiniLM-L12-v2",
                    384, "djl://ai.djl.huggingface.pytorch/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2", false),
            new ModelDefinition(
                    "E5_SMALL_V2", "e5_small_v2", "e5-small-v2",
                    384, "djl://ai.djl.huggingface.pytorch/intfloat/e5-small-v2", true),
            new ModelDefinition(
                    "E5_LARGE_V2", "e5_large_v2", "e5-large-v2",
                    1024, "djl://ai.djl.huggingface.pytorch/intfloat/e5-large-v2", true),
            new ModelDefinition(
                    "MULTI_QA_MINILM_L6_COS_V1", "multi_qa_MiniLM_L6_cos_v1", "multi-qa-MiniLM-L6-cos-v1",
                    384, "djl://ai.djl.huggingface.pytorch/sentence-transformers/multi-qa-MiniLM-L6-cos-v1", false),
            new ModelDefinition(
                    "BGE_M3", "bge_m3", "bge_m3",
                    1024, "", true)
    );
}
