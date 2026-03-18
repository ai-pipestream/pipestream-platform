package ai.pipestream.quarkus.djl.serving.runtime;

import java.util.List;

/**
 * Canonical DJL model names expected to be available at runtime.
 */
final class DjlExpectedModels {

    private DjlExpectedModels() {
    }

    static final List<String> EXPECTED_MODELS = List.of(
            "minilm",
            "mpnet",
            "all_distilroberta_v1",
            "paraphrase_MiniLM_L3_v2",
            "paraphrase_multilingual_MiniLM_L12_v2",
            "e5_small_v2",
            "e5_large_v2",
            "multi_qa_MiniLM_L6_cos_v1",
            "bge_m3"
    );
}
