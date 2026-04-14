package ai.pipestream.test.support.semantic;

import ai.pipestream.data.v1.ChunkEmbedding;
import ai.pipestream.data.v1.NamedEmbedderConfig;
import ai.pipestream.data.v1.PipeDoc;
import ai.pipestream.data.v1.SearchMetadata;
import ai.pipestream.data.v1.SemanticChunk;
import ai.pipestream.data.v1.SemanticProcessingResult;
import ai.pipestream.data.v1.VectorDirective;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage invariants for the three-step semantic pipeline (see
 * pipestream-protos/docs/semantic-pipeline/DESIGN.md §5).
 *
 * This class is shared by all four semantic-pipeline consumers
 * (module-chunker, module-embedder, module-semantic-graph, and
 * pipestream-wiremock-server) as testImplementation so every test suite
 * agrees on the shape a PipeDoc must have at each stage.
 *
 * {@link #assertPostChunker(PipeDoc)} and {@link #assertPostEmbedder(PipeDoc)}
 * are implemented; {@code assertPostSemanticGraph} lands in a follow-up
 * commit on this branch.
 */
public final class SemanticPipelineInvariants {

    private SemanticPipelineInvariants() {}

    /**
     * Asserts that the given {@code doc} satisfies the post-chunker stage invariant
     * as defined in DESIGN.md §5.1 and §21.2.
     *
     * <p>Checks performed:
     * <ol>
     *   <li>{@code search_metadata} is set on the doc.</li>
     *   <li>For every {@code SemanticProcessingResult} in {@code semantic_results}:
     *     <ul>
     *       <li>{@code embedding_config_id} is empty (placeholder, not yet embedded).</li>
     *       <li>{@code source_field_name} is non-empty.</li>
     *       <li>{@code chunk_config_id} is non-empty.</li>
     *       <li>{@code chunks} is non-empty.</li>
     *       <li>For every {@code SemanticChunk}:
     *         <ul>
     *           <li>{@code embedding_info.text_content} is non-empty.</li>
     *           <li>{@code embedding_info.vector} is empty (not yet embedded).</li>
     *           <li>{@code chunk_id} is non-empty.</li>
     *           <li>{@code original_char_start_offset} is non-negative.</li>
     *           <li>{@code original_char_end_offset} >= {@code original_char_start_offset}.</li>
     *         </ul>
     *       </li>
     *       <li>{@code metadata} contains a {@code "directive_key"} entry (per §21.2).</li>
     *     </ul>
     *   </li>
     *   <li>For every unique {@code source_field_name} appearing in {@code semantic_results},
     *       at least one SPR with that source_field_name has {@code nlp_analysis} set
     *       (per DESIGN.md §5.1).</li>
     *   <li>{@code search_metadata.source_field_analytics[]} contains one entry per unique
     *       {@code (source_field, chunk_config_id)} pair present in {@code semantic_results}
     *       (per DESIGN.md §5.1).</li>
     *   <li>{@code semantic_results[]} is lex-sorted by
     *       {@code (source_field_name, chunk_config_id, embedding_config_id, result_id)}.</li>
     * </ol>
     *
     * <p>Note: an empty {@code semantic_results[]} is valid per DESIGN.md §5.1 when the
     * doc contained no source text matching any active directive. This method does not
     * enforce non-empty {@code semantic_results[]}; that check is the caller's
     * responsibility when it matters.
     *
     * @param doc the PipeDoc to validate
     * @throws AssertionError if any invariant is violated
     */
    public static void assertPostChunker(PipeDoc doc) {
        assertThat(doc.hasSearchMetadata())
                .as("post-chunker: search_metadata must be set on the PipeDoc")
                .isTrue();

        SearchMetadata sm = doc.getSearchMetadata();
        List<SemanticProcessingResult> results = sm.getSemanticResultsList();

        for (int i = 0; i < results.size(); i++) {
            SemanticProcessingResult spr = results.get(i);
            String sprContext = "post-chunker: semantic_results[" + i + "]"
                    + " (source_field_name='" + spr.getSourceFieldName()
                    + "', chunk_config_id='" + spr.getChunkConfigId()
                    + "', result_id='" + spr.getResultId() + "')";

            assertThat(spr.getEmbeddingConfigId())
                    .as(sprContext + ": embedding_config_id must be empty (placeholder SPR — not yet embedded)")
                    .isEmpty();

            assertThat(spr.getSourceFieldName())
                    .as(sprContext + ": source_field_name must be non-empty")
                    .isNotEmpty();

            assertThat(spr.getChunkConfigId())
                    .as(sprContext + ": chunk_config_id must be non-empty")
                    .isNotEmpty();

            assertThat(spr.getChunksList())
                    .as(sprContext + ": chunks must be non-empty")
                    .isNotEmpty();

            assertThat(spr.getMetadataMap())
                    .as(sprContext + ": metadata must contain 'directive_key' entry (per DESIGN.md §21.2)")
                    .containsKey("directive_key");

            List<SemanticChunk> chunks = spr.getChunksList();
            for (int j = 0; j < chunks.size(); j++) {
                SemanticChunk chunk = chunks.get(j);
                String chunkContext = sprContext + " chunk[" + j + "] (chunk_id='" + chunk.getChunkId() + "')";

                ChunkEmbedding embeddingInfo = chunk.getEmbeddingInfo();

                assertThat(embeddingInfo.getTextContent())
                        .as(chunkContext + ": embedding_info.text_content must be non-empty")
                        .isNotEmpty();

                assertThat(embeddingInfo.getVectorList())
                        .as(chunkContext + ": embedding_info.vector must be empty (chunk not yet embedded)")
                        .isEmpty();

                assertThat(chunk.getChunkId())
                        .as(chunkContext + ": chunk_id must be non-empty (deterministic ID required per §21.5)")
                        .isNotEmpty();

                int startOffset = embeddingInfo.hasOriginalCharStartOffset()
                        ? embeddingInfo.getOriginalCharStartOffset()
                        : 0;
                int endOffset = embeddingInfo.hasOriginalCharEndOffset()
                        ? embeddingInfo.getOriginalCharEndOffset()
                        : 0;

                assertThat(startOffset)
                        .as(chunkContext + ": embedding_info.original_char_start_offset must be >= 0")
                        .isGreaterThanOrEqualTo(0);

                assertThat(endOffset)
                        .as(chunkContext + ": embedding_info.original_char_end_offset must be >= original_char_start_offset")
                        .isGreaterThanOrEqualTo(startOffset);
            }
        }

        // §5.1: for every unique source_field_name in semantic_results,
        // at least one SPR with that source_field_name must have nlp_analysis set.
        Set<String> sourceFieldsInResults = results.stream()
                .map(SemanticProcessingResult::getSourceFieldName)
                .collect(Collectors.toSet());

        for (String sourceField : sourceFieldsInResults) {
            boolean hasNlpForSource = results.stream()
                    .filter(spr -> sourceField.equals(spr.getSourceFieldName()))
                    .anyMatch(SemanticProcessingResult::hasNlpAnalysis);
            assertThat(hasNlpForSource)
                    .as("post-chunker: at least one SPR with source_field_name='%s' "
                            + "must have nlp_analysis set (per DESIGN.md §5.1)", sourceField)
                    .isTrue();
        }

        // §5.1: source_field_analytics[] must contain an entry per unique
        // (source_field, chunk_config_id) pair present in semantic_results.
        Set<String> pairsInResults = results.stream()
                .map(spr -> spr.getSourceFieldName() + "|" + spr.getChunkConfigId())
                .collect(Collectors.toSet());

        Set<String> pairsInAnalytics = sm.getSourceFieldAnalyticsList().stream()
                .map(sfa -> sfa.getSourceField() + "|" + sfa.getChunkConfigId())
                .collect(Collectors.toSet());

        for (String pair : pairsInResults) {
            assertThat(pairsInAnalytics)
                    .as("post-chunker: source_field_analytics[] must contain an entry for "
                            + "(source_field, chunk_config_id)='%s' (per DESIGN.md §5.1)", pair)
                    .contains(pair);
        }

        assertLexSorted(sm, "post-chunker");
    }

    /**
     * Asserts that the given {@code doc} satisfies the post-embedder stage invariant
     * as defined in DESIGN.md §5.2.
     *
     * <p>Checks performed:
     * <ol>
     *   <li>{@code search_metadata} is set on the doc.</li>
     *   <li>For every {@code SemanticProcessingResult} in {@code semantic_results}:
     *     <ul>
     *       <li>{@code embedding_config_id} is non-empty (SPR is fully embedded, not a placeholder).</li>
     *       <li>{@code source_field_name} is non-empty.</li>
     *       <li>{@code chunk_config_id} is non-empty.</li>
     *       <li>{@code chunks} is non-empty.</li>
     *       <li>For every {@code SemanticChunk}:
     *         <ul>
     *           <li>{@code embedding_info.text_content} is non-empty (preserved from stage 1).</li>
     *           <li>{@code embedding_info.vector} is non-empty (chunk is embedded).</li>
     *           <li>{@code chunk_id} is non-empty (preserved from stage 1).</li>
     *           <li>{@code original_char_start_offset} is non-negative.</li>
     *           <li>{@code original_char_end_offset} &gt;= {@code original_char_start_offset}.</li>
     *         </ul>
     *       </li>
     *       <li>{@code metadata} contains a {@code "directive_key"} entry (preserved from stage 1 per §21.2).</li>
     *       <li>If {@code search_metadata.vector_set_directives} is still present on the doc,
     *           the SPR's {@code embedding_config_id} matches some {@code NamedEmbedderConfig.config_id}
     *           advertised in those directives.</li>
     *     </ul>
     *   </li>
     *   <li>Zero SPRs with {@code embedding_config_id == ""} (implied by the per-SPR check above).</li>
     *   <li>For every unique {@code source_field_name} appearing in {@code semantic_results},
     *       at least one SPR with that source_field_name has {@code nlp_analysis} set
     *       (preserved from stage 1).</li>
     *   <li>{@code search_metadata.source_field_analytics[]} contains one entry per unique
     *       {@code (source_field, chunk_config_id)} pair present in {@code semantic_results}
     *       (preserved from stage 1).</li>
     *   <li>{@code semantic_results[]} is lex-sorted.</li>
     * </ol>
     *
     * <p>Note: byte-identity of {@code chunk_id}, {@code text_content}, offsets, and
     * {@code chunk_analytics} relative to stage 1 (as required by DESIGN.md §5.2) cannot
     * be checked without the stage 1 doc as input. Callers that need byte-identity must
     * diff their own stage-1 input against the stage-2 output they are validating.
     *
     * <p>Note: an empty {@code semantic_results[]} is valid per DESIGN.md §5.2 when the
     * doc contained no source text matching any active directive. This method does not
     * enforce non-empty {@code semantic_results[]}.
     *
     * @param doc the PipeDoc to validate
     * @throws AssertionError if any invariant is violated
     */
    public static void assertPostEmbedder(PipeDoc doc) {
        assertThat(doc.hasSearchMetadata())
                .as("post-embedder: search_metadata must be set on the PipeDoc")
                .isTrue();

        SearchMetadata sm = doc.getSearchMetadata();
        List<SemanticProcessingResult> results = sm.getSemanticResultsList();

        // Collect all NamedEmbedderConfig.config_id values advertised in
        // vector_set_directives, so each SPR's embedding_config_id can be
        // cross-checked. If the doc has no vector_set_directives (e.g. they
        // were cleared after processing), the cross-check is skipped.
        Set<String> advertisedEmbedderConfigIds = Collections.emptySet();
        if (sm.hasVectorSetDirectives()) {
            advertisedEmbedderConfigIds = sm.getVectorSetDirectives().getDirectivesList().stream()
                    .map(VectorDirective::getEmbedderConfigsList)
                    .flatMap(List::stream)
                    .map(NamedEmbedderConfig::getConfigId)
                    .collect(Collectors.toSet());
        }

        for (int i = 0; i < results.size(); i++) {
            SemanticProcessingResult spr = results.get(i);
            String sprContext = "post-embedder: semantic_results[" + i + "]"
                    + " (source_field_name='" + spr.getSourceFieldName()
                    + "', chunk_config_id='" + spr.getChunkConfigId()
                    + "', embedding_config_id='" + spr.getEmbeddingConfigId()
                    + "', result_id='" + spr.getResultId() + "')";

            assertThat(spr.getEmbeddingConfigId())
                    .as(sprContext + ": embedding_config_id must be non-empty (SPR must be fully embedded at stage 2)")
                    .isNotEmpty();

            assertThat(spr.getSourceFieldName())
                    .as(sprContext + ": source_field_name must be non-empty")
                    .isNotEmpty();

            assertThat(spr.getChunkConfigId())
                    .as(sprContext + ": chunk_config_id must be non-empty")
                    .isNotEmpty();

            assertThat(spr.getChunksList())
                    .as(sprContext + ": chunks must be non-empty")
                    .isNotEmpty();

            assertThat(spr.getMetadataMap())
                    .as(sprContext + ": metadata must contain 'directive_key' entry (preserved from stage 1 per DESIGN.md §21.2)")
                    .containsKey("directive_key");

            if (!advertisedEmbedderConfigIds.isEmpty()) {
                assertThat(advertisedEmbedderConfigIds)
                        .as(sprContext + ": embedding_config_id must match a NamedEmbedderConfig "
                                + "advertised in vector_set_directives (per DESIGN.md §5.2)")
                        .contains(spr.getEmbeddingConfigId());
            }

            List<SemanticChunk> chunks = spr.getChunksList();
            for (int j = 0; j < chunks.size(); j++) {
                SemanticChunk chunk = chunks.get(j);
                String chunkContext = sprContext + " chunk[" + j + "] (chunk_id='" + chunk.getChunkId() + "')";

                ChunkEmbedding embeddingInfo = chunk.getEmbeddingInfo();

                assertThat(embeddingInfo.getTextContent())
                        .as(chunkContext + ": embedding_info.text_content must be non-empty (preserved from stage 1)")
                        .isNotEmpty();

                assertThat(embeddingInfo.getVectorList())
                        .as(chunkContext + ": embedding_info.vector must be populated at stage 2")
                        .isNotEmpty();

                assertThat(chunk.getChunkId())
                        .as(chunkContext + ": chunk_id must be non-empty (preserved from stage 1)")
                        .isNotEmpty();

                int startOffset = embeddingInfo.hasOriginalCharStartOffset()
                        ? embeddingInfo.getOriginalCharStartOffset()
                        : 0;
                int endOffset = embeddingInfo.hasOriginalCharEndOffset()
                        ? embeddingInfo.getOriginalCharEndOffset()
                        : 0;

                assertThat(startOffset)
                        .as(chunkContext + ": embedding_info.original_char_start_offset must be >= 0")
                        .isGreaterThanOrEqualTo(0);

                assertThat(endOffset)
                        .as(chunkContext + ": embedding_info.original_char_end_offset must be >= original_char_start_offset")
                        .isGreaterThanOrEqualTo(startOffset);
            }
        }

        // §5.2: nlp_analysis preserved from stage 1 — at least one SPR per unique
        // source_field_name must still have nlp_analysis set.
        Set<String> sourceFieldsInResults = results.stream()
                .map(SemanticProcessingResult::getSourceFieldName)
                .collect(Collectors.toSet());

        for (String sourceField : sourceFieldsInResults) {
            boolean hasNlpForSource = results.stream()
                    .filter(spr -> sourceField.equals(spr.getSourceFieldName()))
                    .anyMatch(SemanticProcessingResult::hasNlpAnalysis);
            assertThat(hasNlpForSource)
                    .as("post-embedder: at least one SPR with source_field_name='%s' "
                            + "must have nlp_analysis set (preserved from stage 1 per DESIGN.md §5.2)", sourceField)
                    .isTrue();
        }

        // §5.2: source_field_analytics[] preserved from stage 1 — entry per unique
        // (source_field, chunk_config_id) pair.
        Set<String> pairsInResults = results.stream()
                .map(spr -> spr.getSourceFieldName() + "|" + spr.getChunkConfigId())
                .collect(Collectors.toSet());

        Set<String> pairsInAnalytics = sm.getSourceFieldAnalyticsList().stream()
                .map(sfa -> sfa.getSourceField() + "|" + sfa.getChunkConfigId())
                .collect(Collectors.toSet());

        for (String pair : pairsInResults) {
            assertThat(pairsInAnalytics)
                    .as("post-embedder: source_field_analytics[] must contain an entry for "
                            + "(source_field, chunk_config_id)='%s' (preserved from stage 1 per DESIGN.md §5.2)", pair)
                    .contains(pair);
        }

        assertLexSorted(sm, "post-embedder");
    }

    /**
     * Verifies that {@code semantic_results[]} is sorted in lexicographic ascending order
     * on the tuple {@code (source_field_name, chunk_config_id, embedding_config_id, result_id)},
     * as required by DESIGN.md §5.1, §5.2, and §21.8.
     *
     * @param sm the SearchMetadata whose semantic_results to validate
     * @param stageLabel prefix for assertion messages (e.g. "post-chunker", "post-embedder")
     * @throws AssertionError if the list is not sorted
     */
    private static void assertLexSorted(SearchMetadata sm, String stageLabel) {
        List<SemanticProcessingResult> results = sm.getSemanticResultsList();

        Comparator<SemanticProcessingResult> lexOrder = Comparator
                .comparing(SemanticProcessingResult::getSourceFieldName)
                .thenComparing(SemanticProcessingResult::getChunkConfigId)
                .thenComparing(SemanticProcessingResult::getEmbeddingConfigId)
                .thenComparing(SemanticProcessingResult::getResultId);

        for (int i = 1; i < results.size(); i++) {
            SemanticProcessingResult prev = results.get(i - 1);
            SemanticProcessingResult curr = results.get(i);
            int cmp = lexOrder.compare(prev, curr);
            assertThat(cmp)
                    .as(stageLabel + ": semantic_results must be lex-sorted by "
                            + "(source_field_name, chunk_config_id, embedding_config_id, result_id). "
                            + "Element at index " + (i - 1) + " (source_field='" + prev.getSourceFieldName()
                            + "', chunk_config='" + prev.getChunkConfigId()
                            + "', embedding_config='" + prev.getEmbeddingConfigId()
                            + "', result_id='" + prev.getResultId() + "') "
                            + "must come before or equal element at index " + i
                            + " (source_field='" + curr.getSourceFieldName()
                            + "', chunk_config='" + curr.getChunkConfigId()
                            + "', embedding_config='" + curr.getEmbeddingConfigId()
                            + "', result_id='" + curr.getResultId() + "')")
                    .isLessThanOrEqualTo(0);
        }
    }
}
