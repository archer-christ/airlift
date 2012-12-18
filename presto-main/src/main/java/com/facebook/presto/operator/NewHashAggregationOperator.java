package com.facebook.presto.operator;

import com.facebook.presto.block.Block;
import com.facebook.presto.block.BlockBuilder;
import com.facebook.presto.block.BlockCursor;
import com.facebook.presto.block.uncompressed.UncompressedBlock;
import com.facebook.presto.operator.aggregation.FixedWidthAggregationFunction;
import com.facebook.presto.slice.Slice;
import com.facebook.presto.slice.Slices;
import com.facebook.presto.sql.planner.plan.AggregationNode.Step;
import com.facebook.presto.tuple.TupleInfo;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenCustomHashMap;
import it.unimi.dsi.fastutil.longs.LongHash.Strategy;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static com.facebook.presto.operator.SyntheticAddress.decodeSliceIndex;
import static com.facebook.presto.operator.SyntheticAddress.decodeSliceOffset;
import static com.facebook.presto.operator.SyntheticAddress.encodeSyntheticAddress;
import static com.google.common.base.Preconditions.checkState;

/**
 * Group input data and produce a single block for each sequence of identical values.
 */
public class NewHashAggregationOperator
        implements Operator
{
    private final Operator source;
    private final int groupByChannel;
    private final List<AggregationFunctionDefinition> functionDefinitions;
    private final List<TupleInfo> tupleInfos;
    private final Step step;

    public NewHashAggregationOperator(Operator source,
            int groupByChannel,
            Step step,
            List<AggregationFunctionDefinition> functionDefinitions)
    {
        this.step = step;
        Preconditions.checkNotNull(source, "source is null");
        Preconditions.checkArgument(groupByChannel >= 0, "groupByChannel is negative");
        Preconditions.checkNotNull(functionDefinitions, "functionDefinitions is null");

        this.source = source;
        this.groupByChannel = groupByChannel;
        this.functionDefinitions = ImmutableList.copyOf(functionDefinitions);

        ImmutableList.Builder<TupleInfo> tupleInfos = ImmutableList.builder();
        tupleInfos.add(source.getTupleInfos().get(groupByChannel));
        for (AggregationFunctionDefinition functionDefinition : functionDefinitions) {
            if (step != Step.PARTIAL) {
                tupleInfos.add(functionDefinition.getFunction().getFinalTupleInfo());
            }
            else {
                tupleInfos.add(functionDefinition.getFunction().getIntermediateTupleInfo());
            }
        }
        this.tupleInfos = tupleInfos.build();
    }

    @Override
    public int getChannelCount()
    {
        return tupleInfos.size();
    }

    @Override
    public List<TupleInfo> getTupleInfos()
    {
        return tupleInfos;
    }

    @Override
    public PageIterator iterator(OperatorStats operatorStats)
    {
        return new HashAggregationIterator(tupleInfos, source, groupByChannel, step, 100_000, functionDefinitions, operatorStats);
    }

    public static class AggregationFunctionDefinition
    {
        public static AggregationFunctionDefinition aggregation(FixedWidthAggregationFunction function, int channel)
        {
            return new AggregationFunctionDefinition(function, channel);
        }

        private final FixedWidthAggregationFunction function;
        private final int channel;

        private AggregationFunctionDefinition(FixedWidthAggregationFunction function, int channel)
        {
            this.function = function;
            this.channel = channel;
        }

        public FixedWidthAggregationFunction getFunction()
        {
            return function;
        }

        public int getChannel()
        {
            return channel;
        }
    }

    private static class HashAggregationIterator
            extends AbstractPageIterator
    {
        private static final int LOOKUP_SLICE_INDEX = 0xFF_FF_FF_FF;

        private final List<Aggregator> aggregates;
        private final Iterator<UncompressedBlock> groupByBlocksIterator;
        private int currentPosition;

        public HashAggregationIterator(List<TupleInfo> tupleInfos,
                Operator source,
                int groupChannel,
                Step step,
                int expectedGroups,
                List<AggregationFunctionDefinition> functionDefinitions,
                OperatorStats operatorStats)
        {
            super(tupleInfos);

            // wrapper each function with an aggregator
            ImmutableList.Builder<Aggregator> builder = ImmutableList.builder();
            for (AggregationFunctionDefinition functionDefinition : functionDefinitions) {
                builder.add(new Aggregator(functionDefinition, step));
            }
            aggregates = builder.build();

            // initialize hash
            TupleInfo groupByTupleInfo = source.getTupleInfos().get(groupChannel);
            SliceHashStrategy hashStrategy = new SliceHashStrategy(groupByTupleInfo);
            Long2IntOpenCustomHashMap addressToGroupId = new Long2IntOpenCustomHashMap(expectedGroups, hashStrategy);
            addressToGroupId.defaultReturnValue(-1);

            // allocate the first group by (key side) slice
            Slice slice = Slices.allocate((int) BlockBuilder.DEFAULT_MAX_BLOCK_SIZE.toBytes());
            hashStrategy.addSlice(slice);
            BlockBuilder blockBuilder = new BlockBuilder(groupByTupleInfo, slice.length(), slice.getOutput());

            int nextGroupId = 0;

            List<UncompressedBlock> groupByBlocks = new ArrayList<>();
            BlockCursor[] cursors = new BlockCursor[source.getChannelCount()];
            PageIterator iterator = source.iterator(operatorStats);
            while (iterator.hasNext()) {
                Page page = iterator.next();
                Block[] blocks = page.getBlocks();
                Slice groupBySlice = ((UncompressedBlock) blocks[groupChannel]).getSlice();
                hashStrategy.setLookupSlice(groupBySlice);

                for (int i = 0; i < blocks.length; i++) {
                    cursors[i] = blocks[i].cursor();
                }

                int rows = page.getPositionCount();
                for (int position = 0; position < rows; position++) {
                    for (BlockCursor cursor : cursors) {
                        checkState(cursor.advanceNextPosition());
                    }

                    // lookup the group id (row number of the key)
                    int rawOffset = cursors[groupChannel].getRawOffset();
                    int groupId = addressToGroupId.get(encodeSyntheticAddress(LOOKUP_SLICE_INDEX, rawOffset));
                    if (groupId < 0) {
                        // new group

                        // copy group by tuple (key) to hash
                        int length = groupByTupleInfo.size(groupBySlice, rawOffset);
                        if (blockBuilder.writableBytes() < length) {
                            UncompressedBlock block = blockBuilder.build();
                            groupByBlocks.add(block);
                            slice = Slices.allocate(Math.max((int) BlockBuilder.DEFAULT_MAX_BLOCK_SIZE.toBytes(), length));
                            blockBuilder = new BlockBuilder(groupByTupleInfo, slice.length(), slice.getOutput());
                            hashStrategy.addSlice(slice);
                        }
                        int groupByValueRawOffset = blockBuilder.size();
                        blockBuilder.appendTuple(groupBySlice, rawOffset, length);

                        // record group id in hash
                        groupId = nextGroupId++;
                        addressToGroupId.put(encodeSyntheticAddress(groupByBlocks.size(), groupByValueRawOffset), groupId);

                        // initialize the value
                        for (Aggregator aggregate : aggregates) {
                            aggregate.initialize(groupId);
                        }
                    }

                    // process the row
                    for (Aggregator aggregate : aggregates) {
                        aggregate.addValue(cursors, groupId);
                    }
                }

                for (BlockCursor cursor : cursors) {
                    checkState(!cursor.advanceNextPosition());
                }
            }

            // add the last block if it is not empty
            if (!blockBuilder.isEmpty()) {
                UncompressedBlock block = blockBuilder.build();
                groupByBlocks.add(block);
            }

            groupByBlocksIterator = groupByBlocks.iterator();
        }

        @Override
        protected Page computeNext()
        {
            // if no more data, return null
            if (!groupByBlocksIterator.hasNext()) {
                endOfData();
                return null;
            }

            // build  the page channel at at time
            Block[] blocks = new Block[getChannelCount()];
            blocks[0] = groupByBlocksIterator.next();
            int pagePositionCount = blocks[0].getPositionCount();
            for (int channel = 1; channel < getChannelCount(); channel++) {
                Aggregator aggregator = aggregates.get(channel - 1);
                // todo there is no need to eval for intermediates since buffer is already in block form
                BlockBuilder blockBuilder = new BlockBuilder(aggregator.getTupleInfo());
                for (int position = 0; position < pagePositionCount; position++) {
                    aggregator.evaluate(currentPosition + position, blockBuilder);
                }
                blocks[channel] = blockBuilder.build();
            }

            Page page = new Page(blocks);
            currentPosition += pagePositionCount;
            return page;
        }

        private static class Aggregator
        {
            private final FixedWidthAggregationFunction function;
            private final int channel;
            private final Step step;
            private final int fixedWithSize;
            private final int sliceSize;
            private final List<Slice> slices = new ArrayList<>();
            private int maxPosition;

            private Aggregator(AggregationFunctionDefinition functionDefinition, Step step)
            {
                this.function = functionDefinition.getFunction();
                channel = functionDefinition.getChannel();
                this.step = step;
                fixedWithSize = this.function.getIntermediateTupleInfo().getFixedSize();
                sliceSize = fixedWithSize * 1024;
                Slice slice = Slices.allocate(sliceSize);
                slices.add(slice);
                maxPosition = sliceSize / fixedWithSize;
            }

            public TupleInfo getTupleInfo()
            {
                // if this is a partial, the output is an intermediate value
                if (step == Step.PARTIAL) {
                    return function.getIntermediateTupleInfo();
                }
                else {
                    return function.getFinalTupleInfo();
                }
            }

            public void initialize(int position)
            {
                // add more slices if necessary
                while (position >= maxPosition) {
                    Slice slice = Slices.allocate(sliceSize);
                    slices.add(slice);
                    maxPosition += sliceSize / fixedWithSize;
                }

                int globalOffset = position * fixedWithSize;

                int sliceIndex = globalOffset / sliceSize; // todo do this with shifts?
                Slice slice = slices.get(sliceIndex);
                int sliceOffset = globalOffset - (sliceIndex * sliceSize);
                function.initialize(slice, sliceOffset);
            }

            public void addValue(BlockCursor[] cursors, int position)
            {
                int globalOffset = position * fixedWithSize;

                int sliceIndex = globalOffset / sliceSize; // todo do this with shifts?
                Slice slice = slices.get(sliceIndex);
                int sliceOffset = globalOffset - (sliceIndex * sliceSize);

                // if this is a final aggregation, the input is an intermediate value
                if (step == Step.FINAL) {
                    function.addIntermediate(cursors[channel], slice, sliceOffset);
                }
                else {
                    function.addInput(cursors[channel], slice, sliceOffset);
                }
            }

            public void evaluate(int position, BlockBuilder output)
            {
                int offset = position * fixedWithSize;

                int sliceIndex = offset / sliceSize; // todo do this with shifts
                Slice slice = slices.get(sliceIndex);
                int sliceOffset = offset - (sliceIndex * sliceSize);

                // if this is a partial, the output is an intermediate value
                if (step == Step.PARTIAL) {
                    function.evaluateIntermediate(slice, sliceOffset, output);
                }
                else {
                    function.evaluateFinal(slice, sliceOffset, output);
                }
            }
        }

        @Override
        protected void doClose()
        {
        }

        public static class SliceHashStrategy
                implements Strategy
        {
            private final TupleInfo tupleInfo;
            private final List<Slice> slices;
            private Slice lookupSlice;

            public SliceHashStrategy(TupleInfo tupleInfo)
            {
                this.tupleInfo = tupleInfo;
                this.slices = ObjectArrayList.wrap(new Slice[10240], 0);
            }

            public void setLookupSlice(Slice lookupSlice)
            {
                this.lookupSlice = lookupSlice;
            }

            public void addSlice(Slice slice)
            {
                slices.add(slice);
            }

            @Override
            public int hashCode(long sliceAddress)
            {
                Slice slice = getSliceForSyntheticAddress(sliceAddress);
                int offset = (int) sliceAddress;
                int length = tupleInfo.size(slice, offset);
                int hashCode = slice.hashCode(offset, length);
                return hashCode;
            }

            @Override
            public boolean equals(long leftSliceAddress, long rightSliceAddress)
            {
                Slice leftSlice = getSliceForSyntheticAddress(leftSliceAddress);
                int leftOffset = decodeSliceOffset(leftSliceAddress);
                int leftLength = tupleInfo.size(leftSlice, leftOffset);

                Slice rightSlice = getSliceForSyntheticAddress(rightSliceAddress);
                int rightOffset = decodeSliceOffset(rightSliceAddress);
                int rightLength = tupleInfo.size(rightSlice, rightOffset);

                return leftSlice.equals(leftOffset, leftLength, rightSlice, rightOffset, rightLength);

            }

            private Slice getSliceForSyntheticAddress(long sliceAddress)
            {
                int sliceIndex = decodeSliceIndex(sliceAddress);
                Slice slice;
                if (sliceIndex == LOOKUP_SLICE_INDEX) {
                    slice = lookupSlice;
                }
                else {
                    slice = slices.get(sliceIndex);
                }
                return slice;
            }
        }
    }

}