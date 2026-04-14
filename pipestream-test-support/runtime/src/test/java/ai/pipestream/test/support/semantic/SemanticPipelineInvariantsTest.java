package ai.pipestream.test.support.semantic;

import ai.pipestream.data.v1.ChunkEmbedding;
import ai.pipestream.data.v1.PipeDoc;
import ai.pipestream.data.v1.SearchMetadata;
import ai.pipestream.data.v1.SemanticChunk;
import ai.pipestream.data.v1.SemanticProcessingResult;
import com.google.protobuf.Value;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies both the happy path and the failure path for
 * {@link SemanticPipelineInvariants#assertPostChunker(PipeDoc)}.
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
     * and chunk_config_id, at least one valid chunk, and a directive_key in metadata.
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
                .build();
    }

    /**
     * Builds a minimal valid {@link PipeDoc} with a single SPR that passes
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
        SearchMetadata sm = SearchMetadata.newBuilder()
                .addSemanticResults(sprBody)
                .addSemanticResults(sprAbstract)
                .build();

        PipeDoc doc = PipeDoc.newBuilder()
                .setDocId("doc-004")
                .setSearchMetadata(sm)
                .build();

        assertThat(doc.getSearchMetadata().getSemanticResultsList()).hasSize(2);

        assertThatThrownBy(() -> SemanticPipelineInvariants.assertPostChunker(doc))
                .as("a PipeDoc whose semantic_results are not lex-sorted must fail the post-chunker assertion")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("lex-sorted");
    }
}
