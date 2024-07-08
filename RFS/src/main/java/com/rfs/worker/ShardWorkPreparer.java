package com.rfs.worker;

import com.rfs.cms.IWorkCoordinator;
import com.rfs.cms.ScopedWorkCoordinator;
import com.rfs.common.FilterScheme;
import com.rfs.common.IndexMetadata;
import com.rfs.common.SnapshotRepo;
import com.rfs.tracing.RootWorkCoordinationContext;
import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts;
import org.opensearch.migrations.reindexer.tracing.IRootDocumentMigrationContext;
import lombok.Lombok;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;

/**
 * This class adds workitemes (leasable mutexes) via the WorkCoordinator so that future
 * runs of the DocumentsRunner can pick one of those items and migrate the documents for
 * that section of work.
 */
@Slf4j
public class ShardWorkPreparer {

    public static final String SHARD_SETUP_WORK_ITEM_ID = "shard_setup";

    public void run(ScopedWorkCoordinator scopedWorkCoordinator,
                    IndexMetadata.Factory metadataFactory,
                    String snapshotName,
                    List<String> indexAllowlist,
                    IRootDocumentMigrationContext rootContext)
            throws IOException, InterruptedException {
        // ensure that there IS an index to house the shared state that we're going to be manipulating
        scopedWorkCoordinator.workCoordinator
                .setup(rootContext.getWorkCoordinationContext()::createCoordinationInitializationStateContext);

        try (var context = rootContext.createDocsMigrationSetupContext()) {
            setupShardWorkItems(scopedWorkCoordinator, metadataFactory, snapshotName, indexAllowlist, context);
        }
    }

    private void setupShardWorkItems(ScopedWorkCoordinator scopedWorkCoordinator,
                                     IndexMetadata.Factory metadataFactory,
                                     String snapshotName,
                                     List<String> indexAllowlist,
                                     IDocumentMigrationContexts.IShardSetupContext context)
        throws IOException, InterruptedException
    {
        scopedWorkCoordinator.ensurePhaseCompletion(
                wc -> {
                    try {
                        return wc.createOrUpdateLeaseForWorkItem(SHARD_SETUP_WORK_ITEM_ID, Duration.ofMinutes(5),
                                context::createWorkAcquisitionContext);
                    } catch (Exception e) {
                        throw Lombok.sneakyThrow(e);
                    }
                },
                new IWorkCoordinator.WorkAcquisitionOutcomeVisitor<Void>() {
                    @Override
                    public Void onAlreadyCompleted() throws IOException {
                        return null;
                    }

                    @Override
                    public Void onAcquiredWork(IWorkCoordinator.WorkItemAndDuration workItem) throws IOException {
                        prepareShardWorkItems(scopedWorkCoordinator.workCoordinator, metadataFactory, snapshotName,
                                indexAllowlist, context);
                        return null;
                    }

                    @Override
                    public Void onNoAvailableWorkToBeDone() throws IOException {
                        return null;
                    }
                }, context::createWorkCompletionContext);
    }

    @SneakyThrows
    private static void prepareShardWorkItems(IWorkCoordinator workCoordinator,
                                              IndexMetadata.Factory metadataFactory,
                                              String snapshotName,
                                              List<String> indexAllowlist,
                                              IDocumentMigrationContexts.IShardSetupContext context) {
        log.info("Setting up the Documents Work Items...");
        SnapshotRepo.Provider repoDataProvider = metadataFactory.getRepoDataProvider();

        BiConsumer<String, Boolean> logger = (indexName, accepted) -> {
            if (!accepted) {
                log.info("Index " + indexName + " rejected by allowlist");
            }
        };
        repoDataProvider.getIndicesInSnapshot(snapshotName).stream()
            .filter(FilterScheme.filterIndicesByAllowList(indexAllowlist, logger))
            .peek(index -> {
                IndexMetadata.Data indexMetadata = metadataFactory.fromRepo(snapshotName, index.getName());
                log.info("Index " + indexMetadata.getName() + " has " + indexMetadata.getNumberOfShards() + " shards");
                IntStream.range(0, indexMetadata.getNumberOfShards()).forEach(shardId -> {
                    log.info("Creating Documents Work Item for index: " + indexMetadata.getName() + ", shard: " + shardId);
                    try {
                        workCoordinator.createUnassignedWorkItem(
                                IndexAndShard.formatAsWorkItemString(indexMetadata.getName(), shardId),
                                context::createShardWorkItemContext);
                    } catch (IOException e) {
                        throw Lombok.sneakyThrow(e);
                    }
                });
            })
            .count(); // Force the stream to execute
        
        log.info("Finished setting up the Documents Work Items.");
    }
}
