/*
 *
 * Copyright (C) 2009 The Simple File Server Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS-IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.sfs.nodes.all.segment;

import com.google.common.base.Optional;
import io.vertx.core.logging.Logger;
import org.sfs.Server;
import org.sfs.VertxContext;
import org.sfs.filesystem.volume.DigestBlob;
import org.sfs.filesystem.volume.ReadStreamBlob;
import org.sfs.io.PipedEndableWriteStream;
import org.sfs.io.PipedReadStream;
import org.sfs.nodes.Nodes;
import org.sfs.nodes.ReplicaGroup;
import org.sfs.nodes.XNode;
import org.sfs.nodes.all.blobreference.DeleteBlobReference;
import org.sfs.rx.Defer;
import org.sfs.rx.Holder2;
import org.sfs.vo.PersistentServiceDef;
import org.sfs.vo.TransientBlobReference;
import org.sfs.vo.TransientSegment;
import org.sfs.vo.XVolume;
import rx.Observable;
import rx.functions.Func1;

import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.FluentIterable.from;
import static com.google.common.collect.Iterables.concat;
import static io.vertx.core.logging.LoggerFactory.getLogger;
import static java.lang.Math.abs;
import static org.sfs.rx.Defer.empty;
import static org.sfs.rx.RxHelper.combineSinglesDelayError;
import static org.sfs.rx.RxHelper.iterate;
import static org.sfs.util.Limits.NOT_SET;
import static org.sfs.util.MessageDigestFactory.SHA512;
import static rx.Observable.just;
import static rx.Observable.zip;

public class RebalanceSegment implements Func1<TransientSegment, Observable<Boolean>> {

    private static final Logger LOGGER = getLogger(RebalanceSegment.class);
    private VertxContext<Server> vertxContext;
    private Nodes nodes;
    private List<PersistentServiceDef> dataNodes;

    public RebalanceSegment(VertxContext<Server> vertxContext, List<PersistentServiceDef> copyOfDataNodes) {
        this.vertxContext = vertxContext;
        this.nodes = vertxContext.verticle().nodes();
        this.dataNodes = copyOfDataNodes;
    }

    @Override
    public Observable<Boolean> call(final TransientSegment transientSegment) {
        if (transientSegment.isTinyData()) {
            return just(true);
        } else {
            return reBalance(transientSegment);
        }
    }


    protected Observable<Boolean> reBalance(TransientSegment transientSegment) {
        List<TransientBlobReference> primaries =
                from(transientSegment.verifiedAckdPrimaryBlobs())
                        .filter(input -> {
                            Optional<Integer> verifyFailCount = input.getVerifyFailCount();
                            return !verifyFailCount.isPresent() || verifyFailCount.get() <= 0;
                        })
                        .toList();
        List<TransientBlobReference> replicas =
                from(transientSegment.verifiedAckdReplicaBlobs())
                        .filter(input -> {
                            Optional<Integer> verifyFailCount = input.getVerifyFailCount();
                            return !verifyFailCount.isPresent() || verifyFailCount.get() <= 0;
                        })
                        .toList();

        int numberOfObjectReplicasRequestedOnContainer = transientSegment.getParent().getParent().getParent().getObjectReplicas();

        int numberOfExpectedPrimaries = nodes.getNumberOfPrimaries();
        int numberOfExpectedReplicas = NOT_SET == numberOfObjectReplicasRequestedOnContainer ? nodes.getNumberOfReplicas() : numberOfObjectReplicasRequestedOnContainer;

        checkState(numberOfExpectedPrimaries > 0 || numberOfExpectedReplicas > 0, "Number of primary + replica volumes must be greater than zero");

        int numberOfExistingPrimaries = primaries.size();
        int numberOfExistingReplicas = replicas.size();

        int numberOfPrimariesNeeded = numberOfExpectedPrimaries - numberOfExistingPrimaries;
        int numberOfReplicasNeeded = numberOfExpectedReplicas - numberOfExistingReplicas;


        Observable<Boolean> oBalanceDownPrimaries =
                empty()
                        .filter(aVoid -> numberOfPrimariesNeeded < 0)
                        .flatMap(aVoid -> balanceDown(primaries, abs(numberOfPrimariesNeeded)))
                        .onErrorResumeNext(throwable -> {
                            LOGGER.error("Handling Balance Down Primaries Exception", throwable);
                            return Defer.just(false);
                        })
                        .singleOrDefault(false);

        Observable<Boolean> oBalanceDownReplicas =
                empty()
                        .filter(aVoid -> numberOfReplicasNeeded < 0)
                        .flatMap(aVoid -> balanceDown(replicas, abs(numberOfReplicasNeeded)))
                        .onErrorResumeNext(throwable -> {
                            LOGGER.error("Handling Balance Down Replicas Exception", throwable);
                            return Defer.just(false);
                        })
                        .singleOrDefault(false);

        Observable<Boolean> oBalanceUp =
                empty()
                        .filter(aVoid -> numberOfPrimariesNeeded > 0 || numberOfReplicasNeeded > 0)
                        .flatMap(aVoid -> {
                            Set<String> usedVolumeIds =
                                    from(concat(primaries, replicas))
                                            .transform(input -> input.getVolumeId().get())
                                            .toSet();
                            return balanceUp(transientSegment, usedVolumeIds, numberOfPrimariesNeeded, numberOfReplicasNeeded);
                        })
                        .onErrorResumeNext(throwable -> {
                            LOGGER.error("Handling Balance Up Exception", throwable);
                            return Defer.just(false);
                        })
                        .singleOrDefault(false);

        return zip(
                oBalanceUp,
                oBalanceDownPrimaries,
                oBalanceDownReplicas,
                (balanceDownPrimaries, balanceDownReplicas, balanceUp) ->
                        balanceDownPrimaries || balanceDownReplicas || balanceUp);
    }

    protected Observable<Boolean> balanceDown(List<TransientBlobReference> blobs, int delta) {
        checkState(delta > 0, "Delta must be greater than 0");
        checkState(blobs.size() >= delta, "Number of blobs must be >= %s but was %s", delta, blobs.size());

        AtomicInteger counter = new AtomicInteger(0);
        return iterate(
                blobs,
                transientBlobReference ->
                        just(transientBlobReference)
                                .flatMap(new DeleteBlobReference(vertxContext))
                                .doOnNext(deleted -> {
                                    transientBlobReference.setDeleted(deleted);
                                    if (deleted) {
                                        counter.incrementAndGet();
                                    }
                                })
                                .map(deleted -> counter.get() < delta))
                .map(aborted -> counter.get() > 0);
    }

    protected Observable<Boolean> balanceUp(TransientSegment transientSegment, Set<String> usedVolumeIds, int numberOfPrimariesNeeded, int numberOfReplicasNeeded) {
        return Defer.just(transientSegment)
                .flatMap(new GetSegmentReadStream(vertxContext, true))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .flatMap(holder -> {
                    ReadStreamBlob readStreamBlob = holder.value1();

                    Iterable<PersistentServiceDef> oCandidateNodes =
                            from(dataNodes)
                                    .transform(PersistentServiceDef::copy)
                                    .transform(persistentServiceDef -> {
                                        Iterator<XVolume<? extends XVolume>> iterator = persistentServiceDef.getVolumes().iterator();
                                        while (iterator.hasNext()) {
                                            XVolume<? extends XVolume> volume = iterator.next();
                                            if (usedVolumeIds.contains(volume.getId().get())) {
                                                iterator.remove();
                                            }
                                        }
                                        return persistentServiceDef;
                                    })
                                    .filter(persistentServiceDef -> !persistentServiceDef.getVolumes().isEmpty());

                    ReplicaGroup replicaGroup = new ReplicaGroup(vertxContext, numberOfPrimariesNeeded, numberOfReplicasNeeded, nodes.isAllowSameNode());

                    PipedReadStream pipedReadStream = new PipedReadStream();
                    PipedEndableWriteStream pipedEndableWriteStream = new PipedEndableWriteStream(pipedReadStream);
                    Observable<Void> producer = readStreamBlob.produce(pipedEndableWriteStream);

                    Observable<List<Holder2<XNode<? extends XNode>, DigestBlob>>> consumer = replicaGroup.consume(oCandidateNodes, readStreamBlob.getLength(), SHA512, pipedReadStream);

                    return combineSinglesDelayError(producer, consumer, (aVoid, holders) -> {
                        for (Holder2<XNode<?>, DigestBlob> response : holders) {
                            DigestBlob digestBlob = response.value1();
                            transientSegment.newBlob()
                                    .setVolumeId(digestBlob.getVolume())
                                    .setVolumePrimary(digestBlob.isPrimary())
                                    .setVolumeReplica(digestBlob.isReplica())
                                    .setPosition(digestBlob.getPosition())
                                    .setReadLength(digestBlob.getLength())
                                    .setReadSha512(digestBlob.getDigest(SHA512).get());
                        }
                        return null;
                    });

                })
                .map(aVoid -> transientSegment)
                // Don't ack the segments since writing these to the index
                // is being done as part of a bulk update. The next run
                // of the bulk update will see these records that are not ackd
                // and will ack them if they can be verified. If these
                // records where ackd here it would be possible for volumes
                // to end up with records that are marked as ackd in the volume
                // but not recorded in the index. This strategy allows the volume garabge collector
                // to purge the data from its local store if this index update
                // fails to persist
                .map(transientSegment1 -> true)
                .singleOrDefault(false);
    }
}

