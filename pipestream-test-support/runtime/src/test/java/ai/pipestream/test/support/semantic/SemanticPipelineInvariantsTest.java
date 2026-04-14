package ai.pipestream.test.support.semantic;

import ai.pipestream.data.v1.CentroidMetadata;
import ai.pipestream.data.v1.ChunkEmbedding;
import ai.pipestream.data.v1.GranularityLevel;
import ai.pipestream.data.v1.NamedEmbedderConfig;
import ai.pipestream.data.v1.NlpDocumentAnalysis;
import ai.pipestream.data.v1.PipeDoc;
import ai.pipestream.data.v1.SearchMetadata;
import ai.pipestream.data.v1.SemanticChunk;
import ai.pipestream.data.v1.SemanticProcessingResult;
import ai.pipestream.data.v1.SourceFieldAnalytics;
import ai.pipestream.data.v1.VectorDirective;
import ai.pipestream.data.v1.VectorSetDirectives;
import com.google.protobuf.Value;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the happy path and failure paths for all three stage invariants in
 * {@link SemanticPipelineInvariants}:
 * {@link SemanticPipelineInvariants#assertPostChunker(PipeDoc)},
 * {@link SemanticPipelineInvariants#assertPostEmbedder(PipeDoc)}, and
 * {@link SemanticPipelineInvariants#assertPostSemanticGraph(PipeDoc)}.
 */
class SemanticPipelineInvariantsTest {

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Builds a minimal {@link ChunkEmbedding} that satisfies all post-chunker
     * chunk-level invariants: non-empty text_content, empty vector, non-negative
     * and ordered offsets.
     */
    private static ChunkEmbedding validChunkEmbedding(String text, int startOffset, int endOffset) {
        return ChunkEmbedding.newBuilder()
                .setTextContent(text)
                // vector intentionally omitted (empty = not yet embedded)
                .setOriginalCharStartOffset(startOffset)
                .setOriginalCharEndOffset(endOffset)
                .build();
    }

    /**
     * Builds a minimal {@link SemanticChunk} that satisfies all post-chunker
     * chunk-level invariants.
     */
    private static SemanticChunk validChunk(String chunkId, String text, int startOffset, int endOffset) {
        return SemanticChunk.newBuilder()
                .setChunkId(chunkId)
                .setChunkNumber(0)
                .setEmbeddingInfo(validChunkEmbedding(text, startOffset, endOffset))
                .build();
    }

    /**
     * Builds a {@link SemanticProcessingResult} that satisfies all post-chunker
     * SPR-level invariants: empty embedding_config_id, non-empty source_field_name
     * and chunk_config_id, at least one valid chunk, a directive_key in metadata,
     * and nlp_analysis set (required per DESIGN.md §5.1).
     */
    private static SemanticProcessingResult validSpr(
            String resultId,
            String sourceFieldName,
            String chunkConfigId,
            String directiveKey) {
        return SemanticProcessingResult.newBuilder()
                .setResultId(resultId)
                .setSourceFieldName(sourceFieldName)
                .setChunkConfigId(chunkConfigId)
                .setEmbeddingConfigId("") // placeholder — not yet embedded
                .addChunks(validChunk("chunk-0", "Hello world, this is a test chunk.", 0, 34))
                .putMetadata("directive_key", Value.newBuilder().setStringValue(directiveKey).build())
                .setNlpAnalysis(NlpDocumentAnalysis.getDefaultInstance())
                .build();
    }

    /**
     * Builds a minimal {@link SourceFieldAnalytics} entry for the given
     * (source_field, chunk_config_id) pair, satisfying the post-chunker
     * requirement that source_field_analytics[] has one entry per unique pair
     * present in semantic_results.
     */
    private static SourceFieldAnalytics validSourceFieldAnalytics(String sourceField, String chunkConfigId) {
        return SourceFieldAnalytics.newBuilder()
                .setSourceField(sourceField)
                .setChunkConfigId(chunkConfigId)
                .build();
    }

    /**
     * Builds a {@link SemanticChunk} with a populated float vector of the given
     * dimension. The vector values are deterministic (sin-based) so tests are
     * reproducible and diffable.
     */
    private static SemanticChunk validStage2Chunk(String chunkId, String text, int startOffset, int endOffset, int dim) {
        ChunkEmbedding.Builder embedding = ChunkEmbedding.newBuilder()
                .setTextContent(text)
                .setOriginalCharStartOffset(startOffset)
                .setOriginalCharEndOffset(endOffset);
        for (int i = 0; i < dim; i++) {
            embedding.addVector((float) Math.sin((double) i + text.hashCode()));
        }
        return SemanticChunk.newBuilder()
                .setChunkId(chunkId)
                .setChunkNumber(0)
                .setEmbeddingInfo(embedding.build())
                .build();
    }

    /**
     * Builds a stage-2 {@link SemanticProcessingResult}: non-empty embedding_config_id,
     * populated vector on every chunk, nlp_analysis preserved, directive_key preserved.
     */
    private static SemanticProcessingResult validStage2Spr(
            String resultId,
            String sourceFieldName,
            String chunkConfigId,
            String embeddingConfigId,
            String directiveKey,
            int vectorDimension) {
        return SemanticProcessingResult.newBuilder()
                .setResultId(resultId)
                .setSourceFieldName(sourceFieldName)
                .setChunkConfigId(chunkConfigId)
                .setEmbeddingConfigId(embeddingConfigId)
                .addChunks(validStage2Chunk("chunk-0", "Hello world, this is a test chunk.", 0, 34, vectorDimension))
                .putMetadata("directive_key", Value.newBuilder().setStringValue(directiveKey).build())
                .setNlpAnalysis(NlpDocumentAnalysis.getDefaultInstance())
                .build();
    }

    /**
     * Builds a {@link VectorSetDirectives} advertising the given embedder config ids
     * on a single directive keyed on the given source label. Used so stage-2 SPRs
     * can pass the directive-lookup invariant in post-embedder checks.
     */
    private static VectorSetDirectives directivesAdvertising(String sourceLabel, String... embedderConfigIds) {
        VectorDirective.Builder directive = VectorDirective.newBuilder()
                .setSourceLabel(sourceLabel)
                .setCelSelector("document." + sourceLabel);
        for (String id : embedderConfigIds) {
            directive.addEmbedderConfigs(NamedEmbedderConfig.newBuilder().setConfigId(id).build());
        }
        return VectorSetDirectives.newBuilder()
                .addDirectives(directive.build())
                .build();
    }

    /**
     * Builds a minimal valid stage-3 centroid {@link SemanticProcessingResult}:
     * chunk_config_id ends in "_centroid", centroid_metadata set with valid
     * granularity and positive source_vector_count, exactly one chunk with a
     * populated vector. Inherits the parent SPR's embedding_config_id and
     * directive_key so the post-graph structural checks still pass.
     */
    private static SemanticProcessingResult validCentroidSpr(
            String sourceFieldName,
            String chunkConfigIdWithCentroidSuffix,
            String embeddingConfigId,
            String directiveKey,
            GranularityLevel granularity,
            int sourceVectorCount,
            int vectorDimension) {
        return SemanticProcessingResult.newBuilder()
                .setResultId("stage3:docHash123:" + sourceFieldName + ":" + chunkConfigIdWithCentroidSuffix + ":" + embeddingConfigId)
                .setSourceFieldName(sourceFieldName)
                .setChunkConfigId(chunkConfigIdWithCentroidSuffix)
                .setEmbeddingConfigId(embeddingConfigId)
                .addChunks(validStage2Chunk("centroid-0", "Representative centroid text", 0, 28, vectorDimension))
                .putMetadata("directive_key", Value.newBuilder().setStringValue(directiveKey).build())
                .setCentroidMetadata(CentroidMetadata.newBuilder()
                        .setGranularity(granularity)
                        .setSourceVectorCount(sourceVectorCount)
                        .build())
                .build();
    }

    /**
     * Builds a minimal valid stage-3 semantic-boundary {@link SemanticProcessingResult}:
     * chunk_config_id="semantic", granularity=SEMANTIC_CHUNK, non-empty
     * semantic_config_id, and the given number of chunks each with populated vectors.
     */
    private static SemanticProcessingResult validBoundarySpr(
            String sourceFieldName,
            String embeddingConfigId,
            String directiveKey,
            String semanticConfigId,
            int chunkCount,
            int vectorDimension) {
        SemanticProcessingResult.Builder builder = SemanticProcessingResult.newBuilder()
                .setResultId("stage3:docHash123:" + sourceFieldName + ":semantic:" + embeddingConfigId)
                .setSourceFieldName(sourceFieldName)
                .setChunkConfigId("semantic")
                .setEmbeddingConfigId(embeddingConfigId)
                .setGranularity(GranularityLevel.GRANULARITY_LEVEL_SEMANTIC_CHUNK)
                .setSemanticConfigId(semanticConfigId)
                .putMetadata("directive_key", Value.newBuilder().setStringValue(directiveKey).build());
        for (int i = 0; i < chunkCount; i++) {
            builder.addChunks(validStage2Chunk("semantic-" + i, "Boundary chunk " + i, 0, 20, vectorDimension));
        }
        return builder.build();
    }

    /**
     * Builds a minimal valid stage-3 {@link PipeDoc}: one preserved stage-2 SPR
     * (for source_field=body, chunk_config=sentence_v1, embedder=minilm), one
     * centroid SPR for the document, one semantic-boundary SPR, matching
     * source_field_analytics for the stage-2 pair only, and directives that
     * advertise the embedder config id. Passes
     * {@link SemanticPipelineInvariants#assertPostSemanticGraph(PipeDoc)}.
     *
     * <p>The result list is lex-sorted by (source_field, chunk_config_id,
     * embedding_config_id, result_id). At source_field="body":
     * document_centroid &lt; semantic &lt; sentence_v1, so the centroid comes first,
     * boundary second, stage-2 third.
     */
    private static PipeDoc validPostGraphDoc() {
        SemanticProcessingResult centroid = validCentroidSpr(
                "body",
                "document_centroid",
                "minilm",
                "sha256b64url-abc123",
                GranularityLevel.GRANULARITY_LEVEL_DOCUMENT,
                4,
                4);

        SemanticProcessingResult boundary = validBoundarySpr(
                "body",
                "minilm",
                "sha256b64url-abc123",
                "semantic_v1",
                3,
                4);

        SemanticProcessingResult stage2Spr = validStage2Spr(
                "stage2:docHash123:body:sentence_v1:minilm",
                "body",
                "sentence_v1",
                "minilm",
                "sha256b64url-abc123",
                4);

        SearchMetadata sm = SearchMetadata.newBuilder()
                .addSemanticResults(centroid)
                .addSemanticResults(boundary)
                .addSemanticResults(stage2Spr)
                .addSourceFieldAnalytics(validSourceFieldAnalytics("body", "sentence_v1"))
                .setVectorSetDirectives(directivesAdvertising("body", "minilm"))
                .build();

        return PipeDoc.newBuilder()
                .setDocId("doc-stage3-001")
                .setSearchMetadata(sm)
                .build();
    }

    /**
     * Builds a minimal valid stage-2 {@link PipeDoc} with a single SPR, a matching
     * source_field_analytics entry, and a vector_set_directives block that advertises
     * the SPR's embedding_config_id. Passes
     * {@link SemanticPipelineInvariants#assertPostEmbedder(PipeDoc)}.
     */
    private static PipeDoc validPostEmbedderDoc() {
        SemanticProcessingResult spr = validStage2Spr(
                "stage2:docHash123:body:sentence_v1:minilm",
                "body",
                "sentence_v1",
                "minilm",
                "sha256b64url-abc123",
                4);

        SearchMetadata sm = SearchMetadata.newBuilder()
                .addSemanticResults(spr)
                .addSourceFieldAnalytics(validSourceFieldAnalytics("body", "sentence_v1"))
                .setVectorSetDirectives(directivesAdvertising("body", "minilm"))
                .build();

        return PipeDoc.newBuilder()
                .setDocId("doc-stage2-001")
                .setSearchMetadata(sm)
                .build();
    }

    /**
     * Builds a minimal valid {@link PipeDoc} with a single SPR and a matching
     * source_field_analytics entry that passes
     * {@link SemanticPipelineInvariants#assertPostChunker(PipeDoc)}.
     */
    private static PipeDoc validPostChunkerDoc() {
        SemanticProcessingResult spr = validSpr(
                "stage1:docHash123:body:sentence_v1:",
                "body",
                "sentence_v1",
                "sha256b64url-abc123");

        SearchMetadata sm = SearchMetadata.newBuilder()
                .addSemanticResults(spr)
                .addSourceFieldAnalytics(validSourceFieldAnalytics("body", "sentence_v1"))
                .build();

        return PipeDoc.newBuilder()
                .setDocId("doc-001")
                .setSearchMetadata(sm)
                .build();
    }

    // ---------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------

    @Test
    void validPostChunkerDocPasses() {
        PipeDoc doc = validPostChunkerDoc();

        assertThatCode(() -> SemanticPipelineInvariants.assertPostChunker(doc))
                .as("a PipeDoc that satisfies all post-chunker invariants should not throw any exception")
                .doesNotThrowAnyException();
    }

    @Test
    void invalidPostChunkerDocFails_missingDirectiveKey() {
        // Build an SPR that is missing the required directive_key in its metadata.
        SemanticProcessingResult sprWithoutDirectiveKey = SemanticProcessingResult.newBuilder()
                .setResultId("stage1:docHash123:body:sentence_v1:")
                .setSourceFieldName("body")
                .setChunkConfigId("sentence_v1")
                .setEmbeddingConfigId("") // still a placeholder
                .addChunks(validChunk("chunk-0", "Some text content here.", 0, 23))
                // deliberately omit directive_key from metadata
                .putMetadata("some_other_key", Value.newBuilder().setStringValue("irrelevant").build())
                .build();

        SearchMetadata sm = SearchMetadata.newBuilder()
                .addSemanticResults(sprWithoutDirectiveKey)
                .build();

        PipeDoc doc = PipeDoc.newBuilder()
                .setDocId("doc-002")
                .setSearchMetadata(sm)
                .build();

        assertThatThrownBy(() -> SemanticPipelineInvariants.assertPostChunker(doc))
                .as("a PipeDoc whose SPR metadata is missing 'directive_key' must fail the post-chunker assertion")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("directive_key");
    }

    @Test
    void invalidPostChunkerDocFails_nonEmptyVector() {
        // Build an SPR whose chunk already has an embedding vector — that is invalid
        // at the post-chunker stage; vectors must be empty placeholders.
        ChunkEmbedding embeddingWithVector = ChunkEmbedding.newBuilder()
                .setTextContent("Pre-embedded text — this is wrong at stage 1.")
                .addVector(0.1f)
                .addVector(0.2f)
                .addVector(0.3f)
                .setOriginalCharStartOffset(0)
                .setOriginalCharEndOffset(46)
                .build();

        SemanticChunk chunkWithVector = SemanticChunk.newBuilder()
                .setChunkId("chunk-premature-embed")
                .setChunkNumber(0)
                .setEmbeddingInfo(embeddingWithVector)
                .build();

        SemanticProcessingResult sprWithVector = SemanticProcessingResult.newBuilder()
                .setResultId("stage1:docHash123:body:sentence_v1:")
                .setSourceFieldName("body")
                .setChunkConfigId("sentence_v1")
                .setEmbeddingConfigId("")
                .addChunks(chunkWithVector)
                .putMetadata("directive_key", Value.newBuilder().setStringValue("sha256b64url-abc123").build())
                .build();

        SearchMetadata sm = SearchMetadata.newBuilder()
                .addSemanticResults(sprWithVector)
                .build();

        PipeDoc doc = PipeDoc.newBuilder()
                .setDocId("doc-003")
                .setSearchMetadata(sm)
                .build();

        assertThatThrownBy(() -> SemanticPipelineInvariants.assertPostChunker(doc))
                .as("a PipeDoc whose chunk has a non-empty vector at stage 1 must fail the post-chunker assertion")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("vector must be empty");
    }

    @Test
    void invalidPostChunkerDocFails_outOfOrderResults() {
        // Build two SPRs that are NOT lex-sorted: second has a source_field_name that
        // sorts before the first alphabetically.
        SemanticProcessingResult sprBody = validSpr(
                "stage1:docHash:body:sentence_v1:",
                "body",
                "sentence_v1",
                "key-body");

        SemanticProcessingResult sprAbstract = validSpr(
                "stage1:docHash:abstract:sentence_v1:",
                "abstract",
                "sentence_v1",
                "key-abstract");

        // Deliberately put body before abstract — wrong lex order.
        // Include source_field_analytics for both pairs so that check passes
        // and the lex-sort check is the one that fires.
        SearchMetadata sm = SearchMetadata.newBuilder()
                .addSemanticResults(sprBody)
                .addSemanticResults(sprAbstract)
                .addSourceFieldAnalytics(validSourceFieldAnalytics("body", "sentence_v1"))
                .addSourceFieldAnalytics(validSourceFieldAnalytics("abstract", "sentence_v1"))
                .build();

        PipeDoc doc = PipeDoc.newBuilder()
                .setDocId("doc-004")
                .setSearchMetadata(sm)
                .build();

        assertThat(doc.getSearchMetadata().getSemanticResultsList())
                .as("test fixture sanity: outOfOrderResults doc should contain exactly 2 SPRs (body before abstract)")
                .hasSize(2);

        assertThatThrownBy(() -> SemanticPipelineInvariants.assertPostChunker(doc))
                .as("a PipeDoc whose semantic_results are not lex-sorted must fail the post-chunker assertion")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("lex-sorted");
    }

    @Test
    void invalidPostChunkerDocFails_missingSearchMetadata() {
        // A bare PipeDoc with no search_metadata set at all must fail
        // the very first guard in assertPostChunker.
        PipeDoc doc = PipeDoc.newBuilder()
                .setDocId("doc-007")
                // deliberately NOT calling .setSearchMetadata(...)
                .build();

        assertThatThrownBy(() -> SemanticPipelineInvariants.assertPostChunker(doc))
                .as("a PipeDoc with no search_metadata set must fail the post-chunker assertion")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("search_metadata must be set");
    }

    @Test
    void invalidPostChunkerDocFails_missingNlpAnalysis() {
        // Build an SPR WITHOUT nlp_analysis — explicit omission of setNlpAnalysis().
        SemanticProcessingResult sprWithoutNlp = SemanticProcessingResult.newBuilder()
                .setResultId("stage1:docHash123:body:sentence_v1:")
                .setSourceFieldName("body")
                .setChunkConfigId("sentence_v1")
                .setEmbeddingConfigId("")
                .addChunks(validChunk("chunk-0", "Hello world, this is a test chunk.", 0, 34))
                .putMetadata("directive_key", Value.newBuilder().setStringValue("sha256b64url-abc123").build())
                // deliberately NOT calling .setNlpAnalysis(...)
                .build();

        SearchMetadata sm = SearchMetadata.newBuilder()
                .addSemanticResults(sprWithoutNlp)
                .addSourceFieldAnalytics(validSourceFieldAnalytics("body", "sentence_v1"))
                .build();

        PipeDoc doc = PipeDoc.newBuilder()
                .setDocId("doc-005")
                .setSearchMetadata(sm)
                .build();

        assertThatThrownBy(() -> SemanticPipelineInvariants.assertPostChunker(doc))
                .as("a PipeDoc where no SPR for source_field='body' has nlp_analysis must "
                        + "fail the post-chunker assertion")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("nlp_analysis");
    }

    @Test
    void invalidPostChunkerDocFails_missingSourceFieldAnalytics() {
        // Build a valid SPR but DO NOT add the corresponding source_field_analytics entry.
        SemanticProcessingResult spr = validSpr(
                "stage1:docHash123:body:sentence_v1:",
                "body",
                "sentence_v1",
                "sha256b64url-abc123");

        SearchMetadata sm = SearchMetadata.newBuilder()
                .addSemanticResults(spr)
                // deliberately NOT adding source_field_analytics for (body, sentence_v1)
                .build();

        PipeDoc doc = PipeDoc.newBuilder()
                .setDocId("doc-006")
                .setSearchMetadata(sm)
                .build();

        assertThatThrownBy(() -> SemanticPipelineInvariants.assertPostChunker(doc))
                .as("a PipeDoc missing a source_field_analytics entry for its (body, sentence_v1) "
                        + "pair must fail the post-chunker assertion")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("source_field_analytics");
    }

    // ---------------------------------------------------------------------------
    // assertPostEmbedder tests
    // ---------------------------------------------------------------------------

    @Test
    void validPostEmbedderDocPasses() {
        PipeDoc doc = validPostEmbedderDoc();

        assertThatCode(() -> SemanticPipelineInvariants.assertPostEmbedder(doc))
                .as("a PipeDoc that satisfies all post-embedder invariants should not throw any exception")
                .doesNotThrowAnyException();
    }

    @Test
    void invalidPostEmbedderDocFails_emptyEmbeddingConfigId() {
        // Build an SPR that still looks like a stage-1 placeholder — embedding_config_id
        // is empty. Post-embedder must reject this.
        SemanticProcessingResult placeholderSpr = SemanticProcessingResult.newBuilder()
                .setResultId("stage1:docHash123:body:sentence_v1:")
                .setSourceFieldName("body")
                .setChunkConfigId("sentence_v1")
                .setEmbeddingConfigId("") // still a placeholder — wrong at stage 2
                .addChunks(validStage2Chunk("chunk-0", "Already embedded text", 0, 21, 4))
                .putMetadata("directive_key", Value.newBuilder().setStringValue("sha256b64url-abc123").build())
                .setNlpAnalysis(NlpDocumentAnalysis.getDefaultInstance())
                .build();

        SearchMetadata sm = SearchMetadata.newBuilder()
                .addSemanticResults(placeholderSpr)
                .addSourceFieldAnalytics(validSourceFieldAnalytics("body", "sentence_v1"))
                .setVectorSetDirectives(directivesAdvertising("body", "minilm"))
                .build();

        PipeDoc doc = PipeDoc.newBuilder()
                .setDocId("doc-stage2-002")
                .setSearchMetadata(sm)
                .build();

        assertThatThrownBy(() -> SemanticPipelineInvariants.assertPostEmbedder(doc))
                .as("a PipeDoc whose SPR still has empty embedding_config_id must fail the post-embedder assertion")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("embedding_config_id must be non-empty");
    }

    @Test
    void invalidPostEmbedderDocFails_emptyVector() {
        // Build an SPR whose chunk has non-empty embedding_config_id but an empty vector.
        // Post-embedder must reject this because the embed step should have populated it.
        ChunkEmbedding emptyVectorEmbedding = ChunkEmbedding.newBuilder()
                .setTextContent("text without vector at stage 2")
                // vector deliberately empty
                .setOriginalCharStartOffset(0)
                .setOriginalCharEndOffset(30)
                .build();

        SemanticChunk chunkWithoutVector = SemanticChunk.newBuilder()
                .setChunkId("chunk-0")
                .setChunkNumber(0)
                .setEmbeddingInfo(emptyVectorEmbedding)
                .build();

        SemanticProcessingResult spr = SemanticProcessingResult.newBuilder()
                .setResultId("stage2:docHash123:body:sentence_v1:minilm")
                .setSourceFieldName("body")
                .setChunkConfigId("sentence_v1")
                .setEmbeddingConfigId("minilm")
                .addChunks(chunkWithoutVector)
                .putMetadata("directive_key", Value.newBuilder().setStringValue("sha256b64url-abc123").build())
                .setNlpAnalysis(NlpDocumentAnalysis.getDefaultInstance())
                .build();

        SearchMetadata sm = SearchMetadata.newBuilder()
                .addSemanticResults(spr)
                .addSourceFieldAnalytics(validSourceFieldAnalytics("body", "sentence_v1"))
                .setVectorSetDirectives(directivesAdvertising("body", "minilm"))
                .build();

        PipeDoc doc = PipeDoc.newBuilder()
                .setDocId("doc-stage2-003")
                .setSearchMetadata(sm)
                .build();

        assertThatThrownBy(() -> SemanticPipelineInvariants.assertPostEmbedder(doc))
                .as("a PipeDoc whose stage-2 SPR chunk has an empty vector must fail the post-embedder assertion")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("vector must be populated");
    }

    @Test
    void invalidPostEmbedderDocFails_embeddingConfigIdNotAdvertised() {
        // Build an SPR with embedding_config_id="phantom" but the directives only
        // advertise "minilm". Post-embedder must reject because the SPR references
        // an embedder config that no VectorDirective advertises.
        SemanticProcessingResult spr = validStage2Spr(
                "stage2:docHash123:body:sentence_v1:phantom",
                "body",
                "sentence_v1",
                "phantom",
                "sha256b64url-abc123",
                4);

        SearchMetadata sm = SearchMetadata.newBuilder()
                .addSemanticResults(spr)
                .addSourceFieldAnalytics(validSourceFieldAnalytics("body", "sentence_v1"))
                .setVectorSetDirectives(directivesAdvertising("body", "minilm")) // does NOT include "phantom"
                .build();

        PipeDoc doc = PipeDoc.newBuilder()
                .setDocId("doc-stage2-004")
                .setSearchMetadata(sm)
                .build();

        assertThatThrownBy(() -> SemanticPipelineInvariants.assertPostEmbedder(doc))
                .as("a PipeDoc whose SPR embedding_config_id is not advertised in any VectorDirective "
                        + "must fail the post-embedder assertion")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("must match a NamedEmbedderConfig");
    }

    @Test
    void validPostEmbedderDocWithoutDirectivesPasses() {
        // If vector_set_directives is cleared after stage 1 processing, the
        // advertised-config-id cross-check must be silently skipped rather than
        // failing. This test exercises that guard.
        PipeDoc doc = validPostEmbedderDoc().toBuilder()
                .setSearchMetadata(validPostEmbedderDoc().getSearchMetadata().toBuilder()
                        .clearVectorSetDirectives()
                        .build())
                .build();

        assertThatCode(() -> SemanticPipelineInvariants.assertPostEmbedder(doc))
                .as("a valid post-embedder doc without vector_set_directives should still pass "
                        + "(the advertised-config-id cross-check is skipped when directives are absent)")
                .doesNotThrowAnyException();
    }

    @Test
    void invalidPostEmbedderDocFails_outOfOrderResults() {
        // Two valid stage-2 SPRs deliberately inserted in wrong lex order
        // (body before abstract). Source analytics entries + directives present
        // for both so the lex-sort check is the one that fires.
        SemanticProcessingResult sprBody = validStage2Spr(
                "stage2:docHash:body:sentence_v1:minilm",
                "body",
                "sentence_v1",
                "minilm",
                "sha256b64url-body",
                4);
        SemanticProcessingResult sprAbstract = validStage2Spr(
                "stage2:docHash:abstract:sentence_v1:minilm",
                "abstract",
                "sentence_v1",
                "minilm",
                "sha256b64url-abstract",
                4);

        SearchMetadata sm = SearchMetadata.newBuilder()
                .addSemanticResults(sprBody)
                .addSemanticResults(sprAbstract)
                .addSourceFieldAnalytics(validSourceFieldAnalytics("body", "sentence_v1"))
                .addSourceFieldAnalytics(validSourceFieldAnalytics("abstract", "sentence_v1"))
                .setVectorSetDirectives(directivesAdvertising("body", "minilm"))
                .build();

        PipeDoc doc = PipeDoc.newBuilder()
                .setDocId("doc-stage2-006")
                .setSearchMetadata(sm)
                .build();

        assertThatThrownBy(() -> SemanticPipelineInvariants.assertPostEmbedder(doc))
                .as("a post-embedder doc whose semantic_results are not lex-sorted must fail")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("lex-sorted");
    }

    @Test
    void invalidPostEmbedderDocFails_missingSearchMetadata() {
        // Bare PipeDoc with no search_metadata must fail the first guard.
        PipeDoc doc = PipeDoc.newBuilder()
                .setDocId("doc-stage2-005")
                .build();

        assertThatThrownBy(() -> SemanticPipelineInvariants.assertPostEmbedder(doc))
                .as("a PipeDoc with no search_metadata set must fail the post-embedder assertion")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("search_metadata must be set");
    }

    // ---------------------------------------------------------------------------
    // assertPostSemanticGraph tests
    // ---------------------------------------------------------------------------

    @Test
    void validPostGraphDocPasses() {
        PipeDoc doc = validPostGraphDoc();

        assertThatCode(() -> SemanticPipelineInvariants.assertPostSemanticGraph(doc))
                .as("a PipeDoc that satisfies all post-semantic-graph invariants should not throw any exception")
                .doesNotThrowAnyException();
    }

    @Test
    void invalidPostGraphDocFails_centroidMissingCentroidMetadata() {
        // Build a centroid SPR (chunk_config_id="document_centroid") but do NOT
        // set centroid_metadata. Must fail the post-graph centroid-specific check.
        SemanticProcessingResult badCentroid = SemanticProcessingResult.newBuilder()
                .setResultId("stage3:docHash123:body:document_centroid:minilm")
                .setSourceFieldName("body")
                .setChunkConfigId("document_centroid")
                .setEmbeddingConfigId("minilm")
                .addChunks(validStage2Chunk("centroid-0", "Centroid without metadata", 0, 25, 4))
                .putMetadata("directive_key", Value.newBuilder().setStringValue("sha256b64url-abc123").build())
                // deliberately NOT calling setCentroidMetadata
                .build();

        SemanticProcessingResult stage2Spr = validStage2Spr(
                "stage2:docHash123:body:sentence_v1:minilm",
                "body",
                "sentence_v1",
                "minilm",
                "sha256b64url-abc123",
                4);

        SearchMetadata sm = SearchMetadata.newBuilder()
                .addSemanticResults(badCentroid)
                .addSemanticResults(stage2Spr)
                .addSourceFieldAnalytics(validSourceFieldAnalytics("body", "sentence_v1"))
                .setVectorSetDirectives(directivesAdvertising("body", "minilm"))
                .build();

        PipeDoc doc = PipeDoc.newBuilder()
                .setDocId("doc-stage3-002")
                .setSearchMetadata(sm)
                .build();

        assertThatThrownBy(() -> SemanticPipelineInvariants.assertPostSemanticGraph(doc))
                .as("a centroid SPR without centroid_metadata must fail the post-graph assertion")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("centroid_metadata");
    }

    @Test
    void invalidPostGraphDocFails_centroidZeroSourceVectorCount() {
        // Centroid SPR with centroid_metadata set but source_vector_count=0.
        SemanticProcessingResult badCentroid = validCentroidSpr(
                "body",
                "document_centroid",
                "minilm",
                "sha256b64url-abc123",
                GranularityLevel.GRANULARITY_LEVEL_DOCUMENT,
                0, // must be strictly positive
                4);

        SemanticProcessingResult stage2Spr = validStage2Spr(
                "stage2:docHash123:body:sentence_v1:minilm",
                "body",
                "sentence_v1",
                "minilm",
                "sha256b64url-abc123",
                4);

        SearchMetadata sm = SearchMetadata.newBuilder()
                .addSemanticResults(badCentroid)
                .addSemanticResults(stage2Spr)
                .addSourceFieldAnalytics(validSourceFieldAnalytics("body", "sentence_v1"))
                .setVectorSetDirectives(directivesAdvertising("body", "minilm"))
                .build();

        PipeDoc doc = PipeDoc.newBuilder()
                .setDocId("doc-stage3-003")
                .setSearchMetadata(sm)
                .build();

        assertThatThrownBy(() -> SemanticPipelineInvariants.assertPostSemanticGraph(doc))
                .as("a centroid SPR with source_vector_count=0 must fail the post-graph assertion")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("source_vector_count");
    }

    @Test
    void invalidPostGraphDocFails_boundaryMissingSemanticConfigId() {
        // Boundary SPR (chunk_config_id="semantic") with no semantic_config_id.
        SemanticProcessingResult badBoundary = SemanticProcessingResult.newBuilder()
                .setResultId("stage3:docHash123:body:semantic:minilm")
                .setSourceFieldName("body")
                .setChunkConfigId("semantic")
                .setEmbeddingConfigId("minilm")
                .setGranularity(GranularityLevel.GRANULARITY_LEVEL_SEMANTIC_CHUNK)
                // deliberately NOT setting semantic_config_id
                .addChunks(validStage2Chunk("semantic-0", "Boundary chunk", 0, 14, 4))
                .putMetadata("directive_key", Value.newBuilder().setStringValue("sha256b64url-abc123").build())
                .build();

        SemanticProcessingResult stage2Spr = validStage2Spr(
                "stage2:docHash123:body:sentence_v1:minilm",
                "body",
                "sentence_v1",
                "minilm",
                "sha256b64url-abc123",
                4);

        SearchMetadata sm = SearchMetadata.newBuilder()
                .addSemanticResults(badBoundary)
                .addSemanticResults(stage2Spr)
                .addSourceFieldAnalytics(validSourceFieldAnalytics("body", "sentence_v1"))
                .setVectorSetDirectives(directivesAdvertising("body", "minilm"))
                .build();

        PipeDoc doc = PipeDoc.newBuilder()
                .setDocId("doc-stage3-004")
                .setSearchMetadata(sm)
                .build();

        assertThatThrownBy(() -> SemanticPipelineInvariants.assertPostSemanticGraph(doc))
                .as("a semantic-boundary SPR with empty semantic_config_id must fail the post-graph assertion")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("semantic_config_id");
    }

    @Test
    void invalidPostGraphDocFails_boundaryWrongGranularity() {
        // Boundary SPR (chunk_config_id="semantic") with granularity=PARAGRAPH
        // instead of the required SEMANTIC_CHUNK.
        SemanticProcessingResult badBoundary = SemanticProcessingResult.newBuilder()
                .setResultId("stage3:docHash123:body:semantic:minilm")
                .setSourceFieldName("body")
                .setChunkConfigId("semantic")
                .setEmbeddingConfigId("minilm")
                .setGranularity(GranularityLevel.GRANULARITY_LEVEL_PARAGRAPH) // wrong
                .setSemanticConfigId("semantic_v1")
                .addChunks(validStage2Chunk("semantic-0", "Boundary chunk", 0, 14, 4))
                .putMetadata("directive_key", Value.newBuilder().setStringValue("sha256b64url-abc123").build())
                .build();

        SemanticProcessingResult stage2Spr = validStage2Spr(
                "stage2:docHash123:body:sentence_v1:minilm",
                "body",
                "sentence_v1",
                "minilm",
                "sha256b64url-abc123",
                4);

        SearchMetadata sm = SearchMetadata.newBuilder()
                .addSemanticResults(badBoundary)
                .addSemanticResults(stage2Spr)
                .addSourceFieldAnalytics(validSourceFieldAnalytics("body", "sentence_v1"))
                .setVectorSetDirectives(directivesAdvertising("body", "minilm"))
                .build();

        PipeDoc doc = PipeDoc.newBuilder()
                .setDocId("doc-stage3-005")
                .setSearchMetadata(sm)
                .build();

        assertThatThrownBy(() -> SemanticPipelineInvariants.assertPostSemanticGraph(doc))
                .as("a semantic-boundary SPR with granularity != SEMANTIC_CHUNK must fail the post-graph assertion")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("GRANULARITY_LEVEL_SEMANTIC_CHUNK");
    }

    @Test
    void invalidPostGraphDocFails_boundaryExceedsMaxChunkCap() {
        // Boundary SPR with chunkCount > MAX_SEMANTIC_CHUNKS_PER_DOC_DEFAULT (50).
        int overCap = SemanticPipelineInvariants.MAX_SEMANTIC_CHUNKS_PER_DOC_DEFAULT + 1;
        SemanticProcessingResult badBoundary = validBoundarySpr(
                "body",
                "minilm",
                "sha256b64url-abc123",
                "semantic_v1",
                overCap,
                4);

        SemanticProcessingResult stage2Spr = validStage2Spr(
                "stage2:docHash123:body:sentence_v1:minilm",
                "body",
                "sentence_v1",
                "minilm",
                "sha256b64url-abc123",
                4);

        SearchMetadata sm = SearchMetadata.newBuilder()
                .addSemanticResults(badBoundary)
                .addSemanticResults(stage2Spr)
                .addSourceFieldAnalytics(validSourceFieldAnalytics("body", "sentence_v1"))
                .setVectorSetDirectives(directivesAdvertising("body", "minilm"))
                .build();

        PipeDoc doc = PipeDoc.newBuilder()
                .setDocId("doc-stage3-006")
                .setSearchMetadata(sm)
                .build();

        assertThatThrownBy(() -> SemanticPipelineInvariants.assertPostSemanticGraph(doc))
                .as("a semantic-boundary SPR whose chunk count exceeds the default cap (%d) must fail the post-graph assertion",
                        SemanticPipelineInvariants.MAX_SEMANTIC_CHUNKS_PER_DOC_DEFAULT)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("chunk count must be <=");
    }

    @Test
    void invalidPostGraphDocFails_centroidMultipleChunks() {
        // Centroid SPR with TWO chunks — must fail the "exactly one chunk" rule.
        SemanticProcessingResult multiChunkCentroid = SemanticProcessingResult.newBuilder()
                .setResultId("stage3:docHash123:body:document_centroid:minilm")
                .setSourceFieldName("body")
                .setChunkConfigId("document_centroid")
                .setEmbeddingConfigId("minilm")
                .addChunks(validStage2Chunk("centroid-0", "First centroid chunk", 0, 20, 4))
                .addChunks(validStage2Chunk("centroid-1", "Second centroid chunk", 0, 21, 4))
                .putMetadata("directive_key", Value.newBuilder().setStringValue("sha256b64url-abc123").build())
                .setCentroidMetadata(CentroidMetadata.newBuilder()
                        .setGranularity(GranularityLevel.GRANULARITY_LEVEL_DOCUMENT)
                        .setSourceVectorCount(4)
                        .build())
                .build();

        SemanticProcessingResult stage2Spr = validStage2Spr(
                "stage2:docHash123:body:sentence_v1:minilm",
                "body",
                "sentence_v1",
                "minilm",
                "sha256b64url-abc123",
                4);

        SearchMetadata sm = SearchMetadata.newBuilder()
                .addSemanticResults(multiChunkCentroid)
                .addSemanticResults(stage2Spr)
                .addSourceFieldAnalytics(validSourceFieldAnalytics("body", "sentence_v1"))
                .setVectorSetDirectives(directivesAdvertising("body", "minilm"))
                .build();

        PipeDoc doc = PipeDoc.newBuilder()
                .setDocId("doc-stage3-008")
                .setSearchMetadata(sm)
                .build();

        assertThatThrownBy(() -> SemanticPipelineInvariants.assertPostSemanticGraph(doc))
                .as("a centroid SPR with more than one chunk must fail the post-graph assertion")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("exactly one chunk");
    }

    @Test
    void invalidPostGraphDocFails_outOfOrderResults() {
        // Build two valid stage-2 SPRs in wrong lex order. No centroid/boundary
        // at source_field="abstract" so the analytics-pair check on preserved
        // SPRs covers both, and the lex-sort check is what fires.
        SemanticProcessingResult sprBody = validStage2Spr(
                "stage2:docHash:body:sentence_v1:minilm",
                "body",
                "sentence_v1",
                "minilm",
                "sha256b64url-body",
                4);
        SemanticProcessingResult sprAbstract = validStage2Spr(
                "stage2:docHash:abstract:sentence_v1:minilm",
                "abstract",
                "sentence_v1",
                "minilm",
                "sha256b64url-abstract",
                4);

        SearchMetadata sm = SearchMetadata.newBuilder()
                .addSemanticResults(sprBody)
                .addSemanticResults(sprAbstract)
                .addSourceFieldAnalytics(validSourceFieldAnalytics("body", "sentence_v1"))
                .addSourceFieldAnalytics(validSourceFieldAnalytics("abstract", "sentence_v1"))
                .setVectorSetDirectives(directivesAdvertising("body", "minilm"))
                .build();

        PipeDoc doc = PipeDoc.newBuilder()
                .setDocId("doc-stage3-009")
                .setSearchMetadata(sm)
                .build();

        assertThatThrownBy(() -> SemanticPipelineInvariants.assertPostSemanticGraph(doc))
                .as("a post-graph doc whose semantic_results are not lex-sorted must fail")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("lex-sorted");
    }

    @Test
    void invalidPostGraphDocFails_missingSearchMetadata() {
        PipeDoc doc = PipeDoc.newBuilder()
                .setDocId("doc-stage3-007")
                .build();

        assertThatThrownBy(() -> SemanticPipelineInvariants.assertPostSemanticGraph(doc))
                .as("a PipeDoc with no search_metadata set must fail the post-graph assertion")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("search_metadata must be set");
    }
}
