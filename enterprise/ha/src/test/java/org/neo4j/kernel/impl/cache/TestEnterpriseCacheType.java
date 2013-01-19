/**
 * Copyright (c) 2002-2013 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.cache;

import static org.junit.Assert.*;
import static org.neo4j.kernel.ha.HaSettings.server_id;

import org.junit.After;
import org.junit.Test;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.graphdb.factory.HighlyAvailableGraphDatabaseFactory;
import org.neo4j.kernel.GraphDatabaseAPI;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.test.TargetDirectory;

public class TestEnterpriseCacheType
{
    @Test
    public void defaultEmbeddedGraphDbShouldUseGcr() throws Exception
    {
        // GIVEN
        // -- an embedded graph database with default cache type config
        db = (GraphDatabaseAPI) new GraphDatabaseFactory().newEmbeddedDatabase( storeDir );

        // THEN
        // -- the selected cache type should be GCR
        assertEquals( "gcr", getCacheTypeUsed() );
    }
    
    @Test
    public void defaultHaGraphDbShouldUseGcr() throws Exception
    {
        // GIVEN
        // -- an HA graph database with default cache type config
        db = (GraphDatabaseAPI) new HighlyAvailableGraphDatabaseFactory().newHighlyAvailableDatabaseBuilder( storeDir )
                .setConfig( server_id, "1" ).newGraphDatabase();

        // THEN
        // -- the selected cache type should be GCR
        assertEquals( "gcr", getCacheTypeUsed() );
    }

    private String getCacheTypeUsed()
    {
        return db.getDependencyResolver().resolveDependency( Config.class ).get( GraphDatabaseSettings.cache_type );
    }

    @After
    public void after() throws Exception
    {
        if ( db != null )
            db.shutdown();
    }

    private String storeDir = TargetDirectory.forTest( getClass() ).graphDbDir( true ).getAbsolutePath();
    private GraphDatabaseAPI db;
}
