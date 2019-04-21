/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.internal.index.label;

import org.junit.Rule;
import org.junit.Test;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import org.neo4j.index.internal.gbptree.Seeker;
import org.neo4j.index.internal.gbptree.ValueMerger;
import org.neo4j.index.internal.gbptree.ValueMergers;
import org.neo4j.index.internal.gbptree.Writer;
import org.neo4j.storageengine.api.NodeLabelUpdate;
import org.neo4j.test.rule.RandomRule;

import static java.lang.Integer.max;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.neo4j.collection.PrimitiveLongCollections.EMPTY_LONG_ARRAY;
import static org.neo4j.collection.PrimitiveLongCollections.asArray;
import static org.neo4j.internal.index.label.LabelScanReader.NO_ID;
import static org.neo4j.internal.index.label.NativeLabelScanStoreIT.flipRandom;
import static org.neo4j.internal.index.label.NativeLabelScanStoreIT.getLabels;
import static org.neo4j.internal.index.label.NativeLabelScanStoreIT.nodesWithLabel;

public class NativeLabelScanWriterTest
{
    private static final int LABEL_COUNT = 5;
    private static final int NODE_COUNT = 10_000;
    private static final Comparator<LabelScanKey> KEY_COMPARATOR = new LabelScanLayout();

    @Rule
    public final RandomRule random = new RandomRule();

    @Test
    public void shouldAddLabels() throws Exception
    {
        // GIVEN
        ControlledInserter inserter = new ControlledInserter();
        long[] expected = new long[NODE_COUNT];
        try ( NativeLabelScanWriter writer = new NativeLabelScanWriter( max( 5, NODE_COUNT / 100 ), NativeLabelScanWriter.EMPTY ) )
        {
            writer.initialize( inserter );

            // WHEN
            for ( int i = 0; i < NODE_COUNT * 3; i++ )
            {
                NodeLabelUpdate update = randomUpdate( expected );
                writer.write( update );
            }
        }

        // THEN
        for ( int i = 0; i < LABEL_COUNT; i++ )
        {
            long[] expectedNodeIds = nodesWithLabel( expected, i );
            long[] actualNodeIds = asArray( new LabelScanValueIterator( inserter.nodesFor( i ), NO_ID ) );
            assertArrayEquals( "For label " + i, expectedNodeIds, actualNodeIds );
        }
    }

    @Test
    public void shouldNotAcceptUnsortedLabels() throws Exception
    {
        // GIVEN
        ControlledInserter inserter = new ControlledInserter();
        boolean failed = false;
        try ( NativeLabelScanWriter writer = new NativeLabelScanWriter( 1, NativeLabelScanWriter.EMPTY ) )
        {
            writer.initialize( inserter );

            // WHEN
            writer.write( NodeLabelUpdate.labelChanges( 0, EMPTY_LONG_ARRAY, new long[] {2, 1} ) );
            // we can't do the usual "fail( blabla )" here since the actual write will happen
            // when closing this writer, i.e. in the curly bracket below.
        }
        catch ( IllegalArgumentException e )
        {
            // THEN
            assertTrue( e.getMessage().contains( "unsorted" ) );
            failed = true;
        }

        assertTrue( failed );
    }

    private NodeLabelUpdate randomUpdate( long[] expected )
    {
        int nodeId = random.nextInt( expected.length );
        long labels = expected[nodeId];
        long[] before = getLabels( labels );
        int changeCount = random.nextInt( 4 ) + 1;
        for ( int i = 0; i < changeCount; i++ )
        {
            labels = flipRandom( labels, LABEL_COUNT, random.random() );
        }
        expected[nodeId] = labels;
        return NodeLabelUpdate.labelChanges( nodeId, before, getLabels( labels ) );
    }

    private static class ControlledInserter implements Writer<LabelScanKey,LabelScanValue>
    {
        private final Map<Integer,Map<LabelScanKey,LabelScanValue>> data = new HashMap<>();

        @Override
        public void close()
        {   // Nothing to close
        }

        @Override
        public void put( LabelScanKey key, LabelScanValue value )
        {
            merge( key, value, ValueMergers.overwrite() );
        }

        @Override
        public void merge( LabelScanKey key, LabelScanValue value, ValueMerger<LabelScanKey,LabelScanValue> amender )
        {
            // Clone since these instances are reused between calls, internally in the writer
            key = clone( key );
            value = clone( value );

            Map<LabelScanKey,LabelScanValue> forLabel =
                    data.computeIfAbsent( key.labelId, labelId -> new TreeMap<>( KEY_COMPARATOR ) );
            LabelScanValue existing = forLabel.get( key );
            if ( existing == null )
            {
                forLabel.put( key, value );
            }
            else
            {
                amender.merge( key, key, existing, value );
            }
        }

        @Override
        public void mergeIfExists( LabelScanKey labelScanKey, LabelScanValue labelScanValue, ValueMerger<LabelScanKey,LabelScanValue> valueMerger )
        {
            throw new UnsupportedOperationException( "Should not be called" );
        }

        private static LabelScanValue clone( LabelScanValue value )
        {
            LabelScanValue result = new LabelScanValue();
            result.bits = value.bits;
            return result;
        }

        private static LabelScanKey clone( LabelScanKey key )
        {
            return new LabelScanKey( key.labelId, key.idRange );
        }

        @Override
        public LabelScanValue remove( LabelScanKey key )
        {
            throw new UnsupportedOperationException( "Should not be called" );
        }

        @SuppressWarnings( "unchecked" )
        Seeker<LabelScanKey,LabelScanValue> nodesFor( int labelId )
        {
            Map<LabelScanKey,LabelScanValue> forLabel = data.get( labelId );
            if ( forLabel == null )
            {
                forLabel = Collections.emptyMap();
            }

            Map.Entry<LabelScanKey,LabelScanValue>[] entries = forLabel.entrySet().toArray( new Entry[0] );
            return new Seeker<>()
            {
                private int arrayIndex = -1;

                @Override
                public LabelScanKey key()
                {
                    return entries[arrayIndex].getKey();
                }

                @Override
                public LabelScanValue value()
                {
                    return entries[arrayIndex].getValue();
                }

                @Override
                public boolean next()
                {
                    arrayIndex++;
                    return arrayIndex < entries.length;
                }

                @Override
                public void close()
                {   // Nothing to close
                }
            };
        }
    }
}
