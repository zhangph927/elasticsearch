/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.search.aggregations.bucket.histogram;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.util.CollectionUtil;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Rounding;
import org.elasticsearch.common.lease.Releasables;
import org.elasticsearch.common.util.LongHash;
import org.elasticsearch.search.DocValueFormat;
import org.elasticsearch.search.aggregations.Aggregator;
import org.elasticsearch.search.aggregations.AggregatorFactories;
import org.elasticsearch.search.aggregations.BucketOrder;
import org.elasticsearch.search.aggregations.InternalAggregation;
import org.elasticsearch.search.aggregations.LeafBucketCollector;
import org.elasticsearch.search.aggregations.LeafBucketCollectorBase;
import org.elasticsearch.search.aggregations.bucket.DeferableBucketAggregator;
import org.elasticsearch.search.aggregations.bucket.DeferringBucketCollector;
import org.elasticsearch.search.aggregations.bucket.MergingBucketsDeferringCollector;
import org.elasticsearch.search.aggregations.bucket.histogram.AutoDateHistogramAggregationBuilder.RoundingInfo;
import org.elasticsearch.search.aggregations.support.ValuesSource;
import org.elasticsearch.search.internal.SearchContext;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.function.Function;

/**
 * An aggregator for date values that attempts to return a specific number of
 * buckets, reconfiguring how it rounds dates to buckets on the fly as new
 * data arrives. 
 */
class AutoDateHistogramAggregator extends DeferableBucketAggregator {

    private final ValuesSource.Numeric valuesSource;
    private final DocValueFormat formatter;
    private final RoundingInfo[] roundingInfos;
    private final Function<Rounding, Rounding.Prepared> roundingPreparer;
    private int roundingIdx = 0;
    private Rounding.Prepared preparedRounding;

    private LongHash bucketOrds;
    private int targetBuckets;
    private MergingBucketsDeferringCollector deferringCollector;

    AutoDateHistogramAggregator(String name, AggregatorFactories factories, int numBuckets, RoundingInfo[] roundingInfos,
        Function<Rounding, Rounding.Prepared> roundingPreparer, @Nullable ValuesSource valuesSource, DocValueFormat formatter,
        SearchContext aggregationContext, Aggregator parent, Map<String, Object> metadata) throws IOException {

        super(name, factories, aggregationContext, parent, metadata);
        this.targetBuckets = numBuckets;
        this.valuesSource = (ValuesSource.Numeric) valuesSource;
        this.formatter = formatter;
        this.roundingInfos = roundingInfos;
        this.roundingPreparer = roundingPreparer;
        preparedRounding = roundingPreparer.apply(roundingInfos[roundingIdx].rounding);

        bucketOrds = new LongHash(1, aggregationContext.bigArrays());

    }

    @Override
    public ScoreMode scoreMode() {
        if (valuesSource != null && valuesSource.needsScores()) {
            return ScoreMode.COMPLETE;
        }
        return super.scoreMode();
    }

    @Override
    protected boolean shouldDefer(Aggregator aggregator) {
        return true;
    }

    @Override
    public DeferringBucketCollector getDeferringCollector() {
        deferringCollector = new MergingBucketsDeferringCollector(context, descendsFromGlobalAggregator(parent()));
        return deferringCollector;
    }

    @Override
    public LeafBucketCollector getLeafCollector(LeafReaderContext ctx,
            final LeafBucketCollector sub) throws IOException {
        if (valuesSource == null) {
            return LeafBucketCollector.NO_OP_COLLECTOR;
        }
        final SortedNumericDocValues values = valuesSource.longValues(ctx);
        return new LeafBucketCollectorBase(sub, values) {
            @Override
            public void collect(int doc, long bucket) throws IOException {
                assert bucket == 0;
                if (values.advanceExact(doc)) {
                    final int valuesCount = values.docValueCount();

                    long previousRounded = Long.MIN_VALUE;
                    for (int i = 0; i < valuesCount; ++i) {
                        long value = values.nextValue();
                        long rounded = preparedRounding.round(value);
                        assert rounded >= previousRounded;
                        if (rounded == previousRounded) {
                            continue;
                        }
                        long bucketOrd = bucketOrds.add(rounded);
                        if (bucketOrd < 0) { // already seen
                            bucketOrd = -1 - bucketOrd;
                            collectExistingBucket(sub, doc, bucketOrd);
                        } else {
                            collectBucket(sub, doc, bucketOrd);
                            while (roundingIdx < roundingInfos.length - 1
                                    && bucketOrds.size() > (targetBuckets * roundingInfos[roundingIdx].getMaximumInnerInterval())) {
                                increaseRounding();
                            }
                        }
                        previousRounded = rounded;
                    }
                }
            }

            private void increaseRounding() {
                try (LongHash oldBucketOrds = bucketOrds) {
                    LongHash newBucketOrds = new LongHash(1, context.bigArrays());
                    long[] mergeMap = new long[(int) oldBucketOrds.size()];
                    preparedRounding = roundingPreparer.apply(roundingInfos[++roundingIdx].rounding);
                    for (int i = 0; i < oldBucketOrds.size(); i++) {
                        long oldKey = oldBucketOrds.get(i);
                        long newKey = preparedRounding.round(oldKey);
                        long newBucketOrd = newBucketOrds.add(newKey);
                        if (newBucketOrd >= 0) {
                            mergeMap[i] = newBucketOrd;
                        } else {
                            mergeMap[i] = -1 - newBucketOrd;
                        }
                    }
                    mergeBuckets(mergeMap, newBucketOrds.size());
                    if (deferringCollector != null) {
                        deferringCollector.mergeBuckets(mergeMap);
                    }
                    bucketOrds = newBucketOrds;
                }
            }
        };
    }

    @Override
    public InternalAggregation[] buildAggregations(long[] owningBucketOrds) throws IOException {
        return buildAggregationsForVariableBuckets(owningBucketOrds, bucketOrds,
                (bucketValue, docCount, subAggregationResults) ->
                    new InternalAutoDateHistogram.Bucket(bucketValue, docCount, formatter, subAggregationResults),
                buckets -> {
                    // the contract of the histogram aggregation is that shards must return
                    // buckets ordered by key in ascending order
                    CollectionUtil.introSort(buckets, BucketOrder.key(true).comparator());

                    // value source will be null for unmapped fields
                    InternalAutoDateHistogram.BucketInfo emptyBucketInfo = new InternalAutoDateHistogram.BucketInfo(roundingInfos,
                            roundingIdx, buildEmptySubAggregations());

                    return new InternalAutoDateHistogram(name, buckets, targetBuckets, emptyBucketInfo, formatter, metadata(), 1);
                });
    }

    @Override
    public InternalAggregation buildEmptyAggregation() {
        InternalAutoDateHistogram.BucketInfo emptyBucketInfo = new InternalAutoDateHistogram.BucketInfo(roundingInfos, roundingIdx,
                buildEmptySubAggregations());
        return new InternalAutoDateHistogram(name, Collections.emptyList(), targetBuckets, emptyBucketInfo, formatter, metadata(), 1);
    }

    @Override
    public void doClose() {
        Releasables.close(bucketOrds);
    }
}
