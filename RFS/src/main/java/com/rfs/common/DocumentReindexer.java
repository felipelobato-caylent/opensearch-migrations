package com.rfs.common;

import java.time.Duration;
import java.util.List;

import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.document.Document;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@RequiredArgsConstructor
public class DocumentReindexer {
    private static final Logger logger = LogManager.getLogger(DocumentReindexer.class);
    private static final int MAX_BATCH_SIZE = 1000; // Arbitrarily chosen
    protected final OpenSearchClient client;

    public Mono<Void> reindex(String indexName,
                                     Flux<Document> documentStream,
                                     IDocumentMigrationContexts.IDocumentReindexContext context) {

        return documentStream
            .map(this::convertDocumentToBulkSection)  // Convert each Document to part of a bulk operation
            .buffer(MAX_BATCH_SIZE) // Collect until you hit the batch size
            .doOnNext(bulk -> logger.info(bulk.size() + " documents in current bulk request"))
            .map(this::convertToBulkRequestBody)  // Assemble the bulk request body from the parts
            .flatMap(bulkJson -> client.sendBulkRequest(indexName, bulkJson, context.createBulkRequest()) // Send the request
                .doOnSuccess(unused -> logger.debug("Batch succeeded"))
                .doOnError(error -> logger.error("Batch failed", error))
                .onErrorResume(e -> Mono.empty()) // Prevent the error from stopping the entire stream
            )
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(5)))
            .doOnComplete(() -> logger.debug("All batches processed"))
            .then();
    }

    private String convertDocumentToBulkSection(Document document) {
        String id = Uid.decodeId(document.getBinaryValue("_id").bytes);
        String source = document.getBinaryValue("_source").utf8ToString();
        String action = "{\"index\": {\"_id\": \"" + id + "\"}}";

        return action + "\n" + source;
    }

    private String convertToBulkRequestBody(List<String> bulkSections) {
        StringBuilder builder = new StringBuilder();
        for (String section : bulkSections) {
            builder.append(section).append("\n");
        }
        return builder.toString();
    }

    public void refreshAllDocuments(ConnectionDetails targetConnection,
                                           IDocumentMigrationContexts.IDocumentReindexContext context) throws Exception {
        // Send the request
        OpenSearchClient client = new OpenSearchClient(targetConnection);
        client.refresh(context.createRefreshContext());
    }
}
